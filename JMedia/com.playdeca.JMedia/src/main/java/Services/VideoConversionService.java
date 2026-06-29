package Services;

import Models.Video;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class VideoConversionService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoConversionService.class);

    private static final List<String> MP4_COMPATIBLE_AUDIO_CODECS = List.of(
        "aac", "mp3", "ac3", "eac3"
    );

    private static final List<String> TEXT_SUBTITLE_CODECS = List.of(
        "subrip", "ass", "ssa", "mov_text", "text"
    );

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    MediaAnalysisService mediaAnalysisService;

    // ── Job tracking ──────────────────────────────────────────────────────

    public static class ConversionJob {
        public final String jobId;
        public final Long videoId;
        public volatile Status status = Status.QUEUED;
        public volatile int progressPercent;
        public volatile String message = "";
        public volatile String errorMessage;
        public final long startTime;
        public volatile long endTime;
        public volatile Process process;

        public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

        ConversionJob(String jobId, Long videoId) {
            this.jobId = jobId;
            this.videoId = videoId;
            this.startTime = System.currentTimeMillis();
        }
    }

    private record SubtitleProbeResult(List<Integer> textStreams, List<Integer> imageStreams) {}

    private final ConcurrentHashMap<String, ConversionJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> videoToJob = new ConcurrentHashMap<>();
    private final ScheduledExecutorService conversionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "video-conversion");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conversion-cleanup");
        t.setDaemon(true);
        return t;
    });

    // ── Batch queue support ─────────────────────────────────────────────────

    private final ConcurrentLinkedQueue<Long> pendingQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean queueProcessing = false;

    public static class BatchInfo {
        public final String batchId;
        public final List<Long> videoIds;
        public final AtomicInteger completed = new AtomicInteger(0);
        public final AtomicInteger failed = new AtomicInteger(0);
        public volatile boolean active = true;
        public volatile boolean cancelled = false;

        BatchInfo(String batchId, List<Long> videoIds) {
            this.batchId = batchId;
            this.videoIds = videoIds;
        }

        public int total() { return videoIds.size(); }
        public int remaining() { return total() - completed.get() - failed.get(); }
        public int processed() { return completed.get() + failed.get(); }
    }

    private final ConcurrentHashMap<String, BatchInfo> batches = new ConcurrentHashMap<>();

    /**
     * Queues all eligible (non-MP4) videos for sequential conversion.
     * Returns a batch ID that can be polled for overall progress.
     */
    public String startBatchConversion(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return null;
        String batchId = "batch-" + System.currentTimeMillis();
        BatchInfo batch = new BatchInfo(batchId, new ArrayList<>(videoIds));
        batches.put(batchId, batch);
        // Enqueue all video IDs
        for (Long id : videoIds) {
            // Skip if already queued or running
            String existingJobId = videoToJob.get(id);
            if (existingJobId != null) {
                ConversionJob existing = jobs.get(existingJobId);
                if (existing != null && (existing.status == ConversionJob.Status.QUEUED || existing.status == ConversionJob.Status.RUNNING)) {
                    continue;
                }
            }
            pendingQueue.add(id);
        }
        // Kick off processing if idle
        processQueue();
        return batchId;
    }

    public BatchInfo getBatchInfo(String batchId) {
        return batches.get(batchId);
    }

    private void processQueue() {
        if (queueProcessing) return; // already processing from a prior trigger
        // The executor is single-threaded, so if we submit now it runs after the
        // current job completes (or immediately if idle).  We just need to pick
        // the next item and call startConversion.
        Long nextId = pendingQueue.poll();
        if (nextId == null) return;
        queueProcessing = true;
        conversionExecutor.submit(() -> {
            try {
                ConversionJob job = doStartConversion(nextId);
                if (job != null) {
                    // Wait for completion so the executor thread stays occupied
                    // and processes one after another.
                    // runConversion is called inside doStartConversion's submit.
                }
            } finally {
                queueProcessing = false;
                // Chain next
                processQueue();
            }
        });
    }

    private ConversionJob doStartConversion(Long videoId) {
        String existingJobId = videoToJob.get(videoId);
        if (existingJobId != null) {
            ConversionJob existing = jobs.get(existingJobId);
            if (existing != null && (existing.status == ConversionJob.Status.QUEUED || existing.status == ConversionJob.Status.RUNNING)) {
                return existing;
            }
        }

        Video video = videoService.findById(videoId);
        if (video == null) return null;

        String jobId = "conv-" + videoId + "-" + System.currentTimeMillis();
        ConversionJob job = new ConversionJob(jobId, videoId);
        jobs.put(jobId, job);
        videoToJob.put(videoId, jobId);

        try {
            runConversion(job, video);
        } catch (Exception e) {
            LOG.error("Conversion failed for video {}: {}", videoId, e.getMessage(), e);
            job.status = ConversionJob.Status.FAILED;
            job.errorMessage = e.getMessage();
            job.endTime = System.currentTimeMillis();
        }

        // Update batch info if this belongs to a batch
        updateBatchForVideo(videoId, job.status);

        return job;
    }

    private void updateBatchForVideo(Long videoId, ConversionJob.Status status) {
        for (BatchInfo batch : batches.values()) {
            if (!batch.active || batch.cancelled) continue;
            if (batch.videoIds.contains(videoId)) {
                if (status == ConversionJob.Status.COMPLETED) {
                    batch.completed.incrementAndGet();
                } else if (status == ConversionJob.Status.FAILED) {
                    batch.failed.incrementAndGet();
                }
                if (batch.processed() >= batch.total()) {
                    batch.active = false;
                }
                break;
            }
        }
    }

    @PostConstruct
    void init() {
        cleanupExecutor.scheduleAtFixedRate(this::cleanupOldJobs, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        conversionExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    // ── Public API ────────────────────────────────────────────────────────

    public ConversionJob startConversion(Long videoId) {
        // Check if already converting
        String existingJobId = videoToJob.get(videoId);
        if (existingJobId != null) {
            ConversionJob existing = jobs.get(existingJobId);
            if (existing != null && (existing.status == ConversionJob.Status.QUEUED || existing.status == ConversionJob.Status.RUNNING)) {
                return existing;
            }
        }

        Video video = videoService.findById(videoId);
        if (video == null) return null;

        String jobId = "conv-" + videoId + "-" + System.currentTimeMillis();
        ConversionJob job = new ConversionJob(jobId, videoId);
        jobs.put(jobId, job);
        videoToJob.put(videoId, jobId);

        conversionExecutor.submit(() -> {
            try {
                runConversion(job, video);
            } catch (Exception e) {
                LOG.error("Conversion failed for video {}: {}", videoId, e.getMessage(), e);
                job.status = ConversionJob.Status.FAILED;
                job.errorMessage = e.getMessage();
                job.endTime = System.currentTimeMillis();
            }
        });

        return job;
    }

    public ConversionJob getJobStatus(String jobId) {
        return jobs.get(jobId);
    }

    // ── Core conversion logic ─────────────────────────────────────────────

    private void runConversion(ConversionJob job, Video video) throws Exception {
        job.status = ConversionJob.Status.RUNNING;
        job.message = "Starting conversion...";
        job.progressPercent = 0;

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found. Please install FFmpeg and restart.");
        }

        // Resolve input file path
        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path inputPath;
        if (video.path != null) {
            Path raw = Paths.get(video.path);
            if (raw.isAbsolute()) {
                inputPath = raw;
            } else if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                inputPath = Paths.get(videoLibraryPath, video.path);
            } else {
                inputPath = raw;
            }
        } else {
            throw new IOException("Video has no file path");
        }

        File inputFile = inputPath.toFile();
        if (!inputFile.exists()) {
            throw new IOException("Input file not found: " + inputPath);
        }

        // Check disk space (conservative: need at least 1GB free)
        File parentDir = inputFile.getParentFile();
        if (parentDir != null && parentDir.exists()) {
            long freeBytes = parentDir.getFreeSpace();
            long inputSize = inputFile.length();
            if (freeBytes < inputSize + 1_073_741_824L) { // input size + 1GB buffer
                throw new IOException("Insufficient disk space. Need at least " +
                        String.format(Locale.ROOT, "%.1f GB", (inputSize + 1_073_741_824L) / 1_073_741_824.0) +
                        " free, but only " + String.format(Locale.ROOT, "%.1f GB", freeBytes / 1_073_741_824.0) + " available.");
            }
        }

        // Build output path
        String baseName = video.filename != null
                ? video.filename.replaceFirst("\\.[^.]+$", "")
                : inputFile.getName().replaceFirst("\\.[^.]+$", "");
        Path outputPath = inputPath.getParent().resolve(baseName + ".mp4");
        Path tempOutput = inputPath.getParent().resolve("." + baseName + ".tmp.mp4");

        // Determine hardware acceleration
        boolean useHardware = isHardwareAccelerationEnabled();
        String hardwareDecoder = useHardware ? discoveryService.getHardwareDecoder(video.videoCodec) : null;
        List<String> hwEncoders = discoveryService.getAvailableHardwareEncoders();

        // Build encoder attempt list (same pattern as TranscodingService: HW encoders first, libx264 last)
        List<String> attemptEncoders = new ArrayList<>();
        if (useHardware && hwEncoders != null) {
            for (String enc : hwEncoders) {
                if (enc.startsWith("h264")) {
                    attemptEncoders.add(enc);
                }
            }
        }
        attemptEncoders.add("libx264");

        // Probe subtitle streams — text go into MP4, image get extracted as .sup
        String ffprobePath = discoveryService.findFFprobeExecutable();
        SubtitleProbeResult subtitleProbe = (ffprobePath != null)
                ? probeSubtitleStreams(ffprobePath, inputFile.getAbsolutePath())
                : new SubtitleProbeResult(new ArrayList<>(), new ArrayList<>());
        List<Integer> textSubtitleStreams = subtitleProbe.textStreams();
        List<Integer> imageSubtitleStreams = subtitleProbe.imageStreams();

        Exception lastException = null;
        boolean conversionStarted = false;

        for (String encoder : attemptEncoders) {
            if (conversionStarted) break; // successful, skip remaining

            boolean isHardwareAttempt = !encoder.equals("libx264");
            String preset;
            if (isHardwareAttempt) {
                if (encoder.contains("nvenc")) preset = "fast";
                else if (encoder.contains("amf")) preset = "speed";
                else if (encoder.contains("qsv") || encoder.contains("videotoolbox")) preset = "fast";
                else preset = "medium";
            } else {
                preset = "veryfast";
            }

            try {
                List<String> command = buildFfmpegCommand(ffmpegPath, inputFile, tempOutput, video,
                        encoder, isHardwareAttempt, hardwareDecoder, preset, textSubtitleStreams);
                runFfmpegProcess(job, command, video, inputFile, outputPath, tempOutput);
                conversionStarted = true; // FFmpeg completed successfully
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Encoder '{}' failed for video {}: {}", encoder, video.id, e.getMessage());
                if (isHardwareAttempt) {
                    discoveryService.recordEncoderFailure(encoder);
                }
                // Clean up partial temp file
                try { Files.deleteIfExists(tempOutput); } catch (IOException ignored) {}
                // If this was the last attempt (libx264), propagate the error
                if (encoder.equals("libx264")) {
                    throw e;
                }
            }
        }

        if (!conversionStarted && lastException != null) {
            throw lastException;
        }

        // ── Post-conversion: verify, swap, update DB ────────────────────────
        job.message = "Verifying output...";
        job.progressPercent = 95;

        if (!tempOutput.toFile().exists() || tempOutput.toFile().length() == 0) {
            throw new IOException("Conversion produced an empty or missing output file");
        }

        // Atomically rename temp to final
        Files.move(tempOutput, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Extract image-based subtitle streams as .sup files alongside the output
        if (!imageSubtitleStreams.isEmpty()) {
            job.message = "Extracting subtitles...";
            extractImageSubtitleStreams(ffmpegPath, inputFile, outputPath, imageSubtitleStreams);
        }

        job.message = "Updating database...";
        job.progressPercent = 98;

        // Delete old file with retry (Windows file locks)
        // Only delete if it's actually a different file — converting an already-MP4 file
        // (due to container misdetection) would make inputPath == outputPath, and deleting
        // the "old" file would wipe the freshly-converted output.
        // Use normalize() instead of toRealPath() because the input file may have been
        // overwritten by the ATOMIC_MOVE above (when inputPath == outputPath), so toRealPath()
        // would fail with IOException.
        if (!inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize())) {
            deleteOldFileWithRetry(inputPath);
        } else {
            LOG.info("Input and output paths are identical, skipping old file deletion: {}", inputPath);
        }

        // Update Video entity
        updateVideoRecord(video, outputPath, inputPath);

        job.status = ConversionJob.Status.COMPLETED;
        job.progressPercent = 100;
        job.message = "Conversion completed successfully!";
        job.endTime = System.currentTimeMillis();
    }

    // ── FFmpeg command building ───────────────────────────────────────────

    private List<String> buildFfmpegCommand(String ffmpegPath, File inputFile, Path tempOutput,
                                             Video video, String videoEncoder,
                                             boolean isHardwareAttempt, String hardwareDecoder,
                                             String preset, List<Integer> textSubtitleStreams) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // HW decoder setup (mirrors TranscodingService lines 563-608)
        if (isHardwareAttempt && hardwareDecoder != null) {
            LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hardwareDecoder, video.videoCodec);
            if (hardwareDecoder.contains("cuvid")) {
                command.add("-hwaccel"); command.add("cuda");
                if (videoEncoder.contains("nvenc")) {
                    command.add("-hwaccel_output_format"); command.add("cuda");
                }
                String index = discoveryService.getBestNvidiaDeviceIndex();
                if (index != null) {
                    command.add("-hwaccel_device"); command.add(index);
                }
            } else if (hardwareDecoder.contains("videotoolbox")) {
                command.add("-hwaccel"); command.add("videotoolbox");
            } else if (hardwareDecoder.contains("qsv")) {
                command.add("-hwaccel"); command.add("qsv");
                if (videoEncoder.contains("qsv")) {
                    command.add("-hwaccel_output_format"); command.add("qsv");
                }
                String device = discoveryService.getBestQsvDevicePath();
                if (device != null) {
                    command.add("-qsv_device"); command.add(device);
                }
            } else if (hardwareDecoder.contains("vaapi")) {
                command.add("-hwaccel"); command.add("vaapi");
                if (videoEncoder.contains("vaapi")) {
                    command.add("-hwaccel_output_format"); command.add("vaapi");
                }
                String device = discoveryService.getBestVaaPiDevicePath();
                if (device != null) {
                    command.add("-hwaccel_device"); command.add(device);
                }
            } else if (hardwareDecoder.contains("amf")) {
                command.add("-hwaccel"); command.add("amf");
                if (videoEncoder.contains("amf")) {
                    command.add("-hwaccel_output_format"); command.add("amf");
                }
            } else if (hardwareDecoder.contains("d3d11va")) {
                command.add("-hwaccel"); command.add("d3d11va");
                if (videoEncoder.contains("d3d11va")) {
                    command.add("-hwaccel_output_format"); command.add("d3d11");
                }
            } else if (hardwareDecoder.contains("dxva2")) {
                command.add("-hwaccel"); command.add("dxva2");
            }
        }

        command.add("-v"); command.add("error");
        command.add("-hide_banner");
        command.add("-stats");

        command.add("-i"); command.add(inputFile.getAbsolutePath());

        command.add("-map"); command.add("0:v");
        command.add("-c:v"); command.add(videoEncoder);

        if (!videoEncoder.equals("libx264")) {
            // HW encoder quality settings
            if (videoEncoder.contains("nvenc")) {
                command.add("-preset"); command.add(preset);
                command.add("-rc"); command.add("vbr");
                command.add("-cq"); command.add("23");
                command.add("-profile:v"); command.add("high");
            } else if (videoEncoder.contains("amf")) {
                command.add("-preset"); command.add(preset);
                command.add("-usage"); command.add("transcoding");
                command.add("-quality"); command.add("quality");
            } else if (videoEncoder.contains("qsv")) {
                command.add("-preset"); command.add(preset);
                command.add("-global_quality"); command.add("23");
            } else if (videoEncoder.contains("videotoolbox")) {
                command.add("-quality"); command.add("70");
            } else if (videoEncoder.contains("vaapi")) {
                command.add("-rc_mode"); command.add("CQP");
                command.add("-qp"); command.add("23");
            } else {
                command.add("-preset"); command.add(preset);
                command.add("-crf"); command.add("23");
            }
        } else {
            // Software libx264
            command.add("-preset"); command.add("veryfast");
            command.add("-crf"); command.add("23");
            command.add("-pix_fmt"); command.add("yuv420p");
        }

        // Scale filter — cap at 1080p, never upscale
        String scaleFilter = buildScaleFilter(hardwareDecoder, videoEncoder, 1080, video.resolution);
        if (scaleFilter != null) {
            command.add("-vf"); command.add(scaleFilter);
        }

        // Audio: copy if MP4-compatible, otherwise transcode to AAC
        command.add("-map"); command.add("0:a");
        if (isAudioCodecMp4Compatible(video.audioCodec)) {
            command.add("-c:a"); command.add("copy");
            if (video.audioCodec != null && video.audioCodec.equalsIgnoreCase("aac")) {
                command.add("-bsf:a"); command.add("aac_adtstoasc");
            }
        } else {
            LOG.info("Audio codec '{}' not MP4-compatible, transcoding to AAC", video.audioCodec);
            command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        }
        // Subtitles: map only text-based streams (skip PGS/VOBSUB which crash mov_text)
        if (textSubtitleStreams != null && !textSubtitleStreams.isEmpty()) {
            for (int subIdx : textSubtitleStreams) {
                command.add("-map"); command.add("0:s:" + subIdx);
            }
            command.add("-c:s"); command.add("mov_text");
        } else {
            command.add("-sn"); // no text subtitles — strip all
        }

        // Chapters and metadata passthrough
        command.add("-map_chapters"); command.add("0");
        command.add("-map_metadata"); command.add("0");

        // Web-optimized + sync
        command.add("-movflags"); command.add("+faststart");
        command.add("-avoid_negative_ts"); command.add("make_zero");

        command.add("-y"); // overwrite temp output
        command.add(tempOutput.toAbsolutePath().toString());

        LOG.info("Convert FFmpeg command: {}", String.join(" ", command));
        return command;
    }

    private boolean isAudioCodecMp4Compatible(String audioCodec) {
        if (audioCodec == null) {
            return true; // unknown — try copy, fail through if it doesn't work
        }
        return MP4_COMPATIBLE_AUDIO_CODECS.contains(audioCodec.toLowerCase(Locale.ROOT));
    }

    /**
     * Probe with ffprobe to find text-based AND image-based subtitle stream indices.
     * Text-based (SRT, ASS) can be embedded as mov_text; image-based (PGS, VOBSUB)
     * must be extracted as .sup files.
     */
    private SubtitleProbeResult probeSubtitleStreams(String ffprobePath, String inputPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "s",
                    "-show_entries", "stream=index,codec_name",
                    "-of", "csv=p=0",
                    inputPath
            );
            Process process = pb.start();
            List<Integer> textStreams = new ArrayList<>();
            List<Integer> imageStreams = new ArrayList<>();
            try (Scanner sc = new Scanner(process.getInputStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(",", 2);
                    if (parts.length < 2) continue;
                    try {
                        int index = Integer.parseInt(parts[0]);
                        String codec = parts[1].toLowerCase(Locale.ROOT);
                        if (TEXT_SUBTITLE_CODECS.contains(codec)) {
                            textStreams.add(index);
                        } else {
                            imageStreams.add(index);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOG.warn("FFprobe subtitle probe failed (exit {}), skipping subtitle mapping", exitCode);
                return new SubtitleProbeResult(Collections.emptyList(), Collections.emptyList());
            }
            LOG.debug("Found {} text and {} image subtitle stream(s) in {}",
                    textStreams.size(), imageStreams.size(), inputPath);
            return new SubtitleProbeResult(textStreams, imageStreams);
        } catch (Exception e) {
            LOG.warn("Failed to probe subtitle streams: {}", e.getMessage());
            return new SubtitleProbeResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * Extract image-based subtitle streams (PGS, VOBSUB) as .sup files
     * alongside the converted MP4. These can't be embedded in MP4, so they
     * are served externally by the subtitle service.
     */
    private void extractImageSubtitleStreams(String ffmpegPath, File inputFile, Path outputPath,
                                              List<Integer> imageStreams) {
        String baseName = outputPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path outputDir = outputPath.getParent();
        if (outputDir == null) {
            LOG.warn("Cannot determine output directory for subtitle extraction");
            return;
        }

        for (int i = 0; i < imageStreams.size(); i++) {
            int streamIdx = imageStreams.get(i);
            Path subOutput = outputDir.resolve(baseName + ".subtitle_" + i + ".sup");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-v", "error",
                        "-hide_banner",
                        "-i", inputFile.getAbsolutePath(),
                        "-map", "0:s:" + streamIdx,
                        "-c:s", "copy",
                        "-y",
                        subOutput.toAbsolutePath().toString()
                );
                Process process = pb.start();
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    LOG.warn("Timed out extracting subtitle stream {}", streamIdx);
                } else if (process.exitValue() != 0) {
                    LOG.warn("Failed to extract subtitle stream {} (exit {})", streamIdx, process.exitValue());
                } else {
                    LOG.info("Extracted subtitle stream {} as {}", streamIdx, subOutput.getFileName());
                }
            } catch (Exception e) {
                LOG.warn("Failed to extract subtitle stream {}: {}", streamIdx, e.getMessage());
            }
        }
    }

    // ── Scale filter (mirrors TranscodingService.buildScaleFilter) ────────

    private String buildScaleFilter(String hardwareDecoder, String videoEncoder, int targetHeight, String resolution) {
        if (targetHeight <= 0) return null;

        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}

        // Never upscale
        if (h > 0 && targetHeight >= h) return null;

        double aspect = (double) w / h;
        int targetH = targetHeight;
        int targetW = (int) Math.round(targetH * aspect);
        if (targetW % 2 != 0) targetW--;
        if (targetH % 2 != 0) targetH--;

        // Vendor-matched zero-copy pipelines
        boolean decoderIsCuda = hardwareDecoder != null && hardwareDecoder.contains("cuvid");
        boolean decoderIsQsv = hardwareDecoder != null && hardwareDecoder.contains("qsv");
        boolean decoderIsVaapi = hardwareDecoder != null && hardwareDecoder.contains("vaapi");
        boolean decoderIsAmf = hardwareDecoder != null && hardwareDecoder.contains("amf");
        boolean decoderIsVideoToolbox = hardwareDecoder != null && hardwareDecoder.contains("videotoolbox");

        boolean encoderIsNvenc = videoEncoder != null && videoEncoder.contains("nvenc");
        boolean encoderIsQsv = videoEncoder != null && videoEncoder.contains("qsv");
        boolean encoderIsVaapi = videoEncoder != null && videoEncoder.contains("vaapi");
        boolean encoderIsAmf = videoEncoder != null && videoEncoder.contains("amf");
        boolean encoderIsVideoToolbox = videoEncoder != null && videoEncoder.contains("videotoolbox");

        if (decoderIsCuda && encoderIsNvenc) {
            return "scale_cuda=" + targetW + ":" + targetH;
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH;
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH;
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH;
        } else if (decoderIsVideoToolbox && encoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Cross-vendor fallback or software
        return "scale=" + targetW + ":" + targetH;
    }

    // ── FFmpeg process execution with progress parsing ────────────────────

    private void runFfmpegProcess(ConversionJob job, List<String> command, Video video,
                                   File inputFile, Path outputPath, Path tempOutput) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        job.process = process;

        // Parse stderr for progress (time=HH:MM:SS.mm)
        long durationMs = video.duration != null && video.duration > 0 ? video.duration : 0;

        Thread progressReader = new Thread(() -> {
            Pattern timePattern = Pattern.compile("time=(\\d+):(\\d+):(\\d+)\\.(\\d+)");
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    if (line.contains("time=")) {
                        Matcher m = timePattern.matcher(line);
                        if (m.find()) {
                            long ptsMs = Long.parseLong(m.group(1)) * 3_600_000L
                                    + Long.parseLong(m.group(2)) * 60_000L
                                    + Long.parseLong(m.group(3)) * 1_000L
                                    + Long.parseLong(m.group(4)) * 10L;
                            if (durationMs > 0) {
                                int pct = (int) Math.min(94, (ptsMs * 100) / durationMs);
                                job.progressPercent = Math.max(job.progressPercent, pct);
                            }
                            job.message = "Converting... " + job.progressPercent + "%";
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
        progressReader.setDaemon(true);
        progressReader.start();

        int exitCode = process.waitFor();
        progressReader.join(2000);

        if (exitCode != 0) {
            // Read error output for diagnostics
            String errorOutput = "";
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream()).useDelimiter("\\A")) {
                if (sc.hasNext()) errorOutput = sc.next();
            } catch (Exception ignored) {}

            // Check if subtitle codec error is the only issue (unwanted subtitles)
            if (errorOutput.contains("Subtitle codec") && errorOutput.contains("is not supported")) {
                LOG.warn("Subtitle stream not compatible with mov_text, retrying without subtitles");
                // Retry without subtitle mapping
                List<String> retryCommand = new ArrayList<>(command);
                // Remove subtitle-related args
                int sIdx = retryCommand.indexOf("-c:s");
                if (sIdx >= 0) {
                    retryCommand.remove(sIdx); // -c:s
                    retryCommand.remove(sIdx); // mov_text
                }
                int mapSIdx = retryCommand.indexOf("-map");
                while (mapSIdx >= 0 && mapSIdx + 1 < retryCommand.size() && retryCommand.get(mapSIdx + 1).contains("0:s")) {
                    retryCommand.remove(mapSIdx); // -map
                    retryCommand.remove(mapSIdx); // 0:s?
                }

                ProcessBuilder pb2 = new ProcessBuilder(retryCommand);
                pb2.redirectErrorStream(false);
                Process p2 = pb2.start();
                job.process = p2;
                int exit2 = p2.waitFor();
                if (exit2 != 0) {
                    throw new IOException("FFmpeg conversion failed (exit code " + exit2 + ") after subtitle retry. Check logs.");
                }
            } else {
                String summary = errorOutput.length() > 200 ? errorOutput.substring(0, 200) + "..." : errorOutput;
                throw new IOException("FFmpeg conversion failed (exit code " + exitCode + "): " + summary.trim());
            }
        }
    }

    // ── Post-conversion helpers ───────────────────────────────────────────

    private void deleteOldFileWithRetry(Path path) {
        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Files.deleteIfExists(path);
                LOG.info("Deleted original file: {}", path);
                return;
            } catch (IOException e) {
                LOG.warn("Failed to delete original file (attempt {}/{}): {}", i + 1, maxAttempts, e.getMessage());
                if (i < maxAttempts - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
        LOG.warn("Could not delete original file after {} attempts: {}", maxAttempts, path);
    }

    @Transactional
    protected void updateVideoRecord(Video video, Path outputPath, Path oldInputPath) {
        Video managed = Video.findById(video.id);
        if (managed == null) return;

        // Capture old path BEFORE we overwrite it below (used for MediaFile lookup)
        String oldPath = video.path;

        String newPath = outputPath.toAbsolutePath().toString();
        String newFilename = outputPath.getFileName().toString();

        managed.path = newPath;
        managed.filename = newFilename;
        managed.size = outputPath.toFile().length();
        managed.fileSize = managed.size;
        managed.lastModified = outputPath.toFile().lastModified();

        // Force re-probe by clearing cached codec info
        managed.videoCodec = null;
        managed.audioCodec = null;
        managed.resolution = null;
        managed.displayResolution = null;

        videoService.probeVideoMetadata(managed);

        // Override container/format after probe: FFprobe reports MP4 as "mov" (format_name="mov,mp4,m4a,...")
        managed.container = "mp4";
        managed.format = "mp4";

        managed.persist();

        // Update the corresponding MediaFile entity so history recording still works
        if (oldPath != null && !oldPath.equals(newPath)) {
            Models.MediaFile mediaFile = Models.MediaFile.find("path", oldPath).firstResult();
            if (mediaFile != null) {
                mediaFile.path = newPath;
                // Re-probe metadata since the file content changed (re-encoded)
                try {
                    mediaAnalysisService.analyze(mediaFile);
                } catch (Exception e) {
                    LOG.warn("Could not re-analyze MediaFile {} after conversion: {}", mediaFile.id, e.getMessage());
                }
                mediaFile.persist();
                LOG.info("Updated MediaFile {}: path={}", mediaFile.id, newPath);
            } else {
                LOG.debug("No MediaFile found for old path: {}", oldPath);
            }
        }

        // Copy fields back to the detached object
        video.path = managed.path;
        video.filename = managed.filename;
        video.container = managed.container;
        video.format = managed.format;
        video.size = managed.size;
        video.fileSize = managed.fileSize;
        video.lastModified = managed.lastModified;
        video.videoCodec = managed.videoCodec;
        video.audioCodec = managed.audioCodec;
        video.resolution = managed.resolution;
        video.displayResolution = managed.displayResolution;

        LOG.info("Updated video record {}: path={}, container=mp4, size={}", video.id, newPath, managed.size);
    }

    // ── Configuration helpers ─────────────────────────────────────────────

    private boolean isHardwareAccelerationEnabled() {
        try {
            Models.Settings settings = settingsService.getOrCreateSettings();
            return settings.getHardwareAccelerationEnabled() != null ? settings.getHardwareAccelerationEnabled() : true;
        } catch (Exception e) {
            LOG.debug("Could not read hardware acceleration setting, defaulting to enabled: {}", e.getMessage());
            return true;
        }
    }

    // ── Job cleanup ───────────────────────────────────────────────────────

    private void cleanupOldJobs() {
        long now = System.currentTimeMillis();
        long timeout = 300_000; // 5 minutes
        jobs.entrySet().removeIf(entry -> {
            ConversionJob job = entry.getValue();
            if (job.status == ConversionJob.Status.COMPLETED || job.status == ConversionJob.Status.FAILED) {
                if (now - job.endTime > timeout) {
                    videoToJob.remove(job.videoId, job.jobId);
                    return true;
                }
            }
            return false;
        });
    }
}
