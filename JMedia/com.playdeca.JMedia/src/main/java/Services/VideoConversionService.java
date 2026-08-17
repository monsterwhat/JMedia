package Services;

import Models.Video.AudioTrack;
import Models.Video.Video;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class VideoConversionService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoConversionService.class);

    /* Only codecs every browser can decode natively are safe to stream-copy into
     * MP4. AC3/EAC3/DTS/etc. fit the container but Chrome/Firefox have no decoder
     * for them — copying them produces an MP4 that plays with no audio. Anything
     * not in this list gets transcoded to AAC. */
    private static final List<String> MP4_COMPATIBLE_AUDIO_CODECS = List.of(
        "aac", "mp3"
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

    @Inject
    FFprobeAudioService ffprobeAudioService;

    @Inject
    TranscodingService transcodingService;

    // ── Audio remux (permanent audio fix) ─────────────────────────────────

    /* Codecs safe to bit-copy into MP4 and play natively in every browser
     * (matches TranscodingService's streaming copy list). Anything else —
     * AC3/EAC3/DTS/TrueHD/Opus — is re-encoded to AAC 192k so the fixed file
     * streams with -c:a copy forever after. */
    private static final List<String> AUDIO_REMUX_COPY_CODECS = List.of(
        "aac", "mp3", "flac"
    );

    /* Cooldown after a FAILED audio remux so repeated stream requests can't
     * trigger a retry storm against a file that can't be fixed. */
    private static final long AUDIO_REMUX_FAILED_COOLDOWN_MS = 30 * 60 * 1000L;

    /* If an ffmpeg process produces no stderr output (progress lines) for this
     * long, it is wedged — deadlocked codec init, hung I/O, frozen pipe — so we
     * kill it instead of waiting forever. ffmpeg emits progress roughly every
     * second with -stats, so a healthy process never trips this. */
    private static final long FFMPEG_STALL_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long FFMPEG_STALL_POLL_MS = 30_000L;

    /* D17: maximum age (ms) for a QUEUED job before it is considered a zombie
     * and evicted by cleanupOldJobs.  60 minutes covers even the longest
     * transcodes; a genuinely in-flight job will be RUNNING, not QUEUED. */
    private static final long QUEUED_JOB_TTL_MS = 60 * 60 * 1000L;

    /* D20: maximum age (ms) for a pending finalize entry before it is evicted
     * by cleanupOldJobs to prevent source+mp4+map-entry leaking forever. */
    private static final long PENDING_FINALIZE_TTL_MS = 2 * 60 * 60 * 1000L;

    /* D21: maximum age (ms) for a finished/inactive batch before it is evicted
     * by cleanupOldJobs to prevent unbounded map growth. */
    private static final long BATCH_TTL_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<Long, Long> audioRemuxLastFailure = new ConcurrentHashMap<>();

    /** A completed conversion whose destructive finalize (old-file deletion +
     *  DB path swap) was deferred because the video was actively streaming.
     *  Completed by finalizePendingIfIdle once the video is idle. */
    private record PendingFinalize(Path inputPath, Path outputPath, long createdAt) {}

    private final ConcurrentHashMap<Long, PendingFinalize> pendingFinalizes = new ConcurrentHashMap<>();

    // D18: per-videoId lock for the delete+update critical section in finalize
    private final ConcurrentHashMap<Long, Object> finalizeLocks = new ConcurrentHashMap<>();

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
        // De-duplicate the input list so a single video can't be counted twice
        // in the same batch (remaining() would then never reach 0).
        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(videoIds));
        BatchInfo batch = new BatchInfo(batchId, uniqueIds);
        batches.put(batchId, batch);
        // Enqueue all video IDs
        for (Long id : uniqueIds) {
            // Skip if already queued or running
            String existingJobId = videoToJob.get(id);
            if (existingJobId != null) {
                ConversionJob existing = jobs.get(existingJobId);
                if (existing != null && (existing.status == ConversionJob.Status.QUEUED || existing.status == ConversionJob.Status.RUNNING)) {
                    continue;
                }
            }
            // Avoid duplicate queue entries from overlapping batch requests
            if (!pendingQueue.contains(id)) {
                pendingQueue.add(id);
            }
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
        ConversionStart start = createOrGetConversionJob(videoId);
        ConversionJob job = start.job();
        if (job == null) return null;

        if (start.created()) {
            // This queue item owns the job — run it now (blocking) so the single
            // conversion executor processes batch items one after another.
            Video video = videoService.findById(videoId);
            if (video != null) {
                try {
                    runConversion(job, video);
                } catch (Exception e) {
                    LOG.error("Conversion failed for video {}: {}", videoId, e.getMessage(), e);
                    job.status = ConversionJob.Status.FAILED;
                    job.errorMessage = e.getMessage();
                    job.endTime = System.currentTimeMillis();
                }
            }
        }

        // Count every outcome — including reused QUEUED/RUNNING/COMPLETED jobs —
        // so batch progress never stalls on a video that is already converted or
        // already running.
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
                    batches.remove(batch.batchId);
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
        // killRunningProcessesForShutdown() must come first: shutdownNow() only
        // interrupts the Java thread, but runConversion blocks in
        // process.waitFor(), which ignores interrupts — the ffmpeg child would
        // keep transcoding (and keep writing the same .tmp.mp4) after the app
        // is stopped. destroyForcibly() terminates the native process itself.
        for (ConversionJob job : jobs.values()) {
            Process p = job.process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        conversionExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** A conversion job and whether this call created it (vs. reusing an existing one). */
    private record ConversionStart(ConversionJob job, boolean created) {}

    /**
     * Atomically resolve the job for a video. Synchronized so two concurrent
     * callers can never both create a job for the same video — the old
     * check-then-act (videoToJob.get followed by put) allowed duplicates when
     * e.g. two playback-fragment renders for the same video landed at once.
     *
     * <p>Also reuses a COMPLETED job while the MP4 it produced is still the
     * current file — or while its finalize is still pending (the video is
     * being watched and the destructive path swap was deferred) — so
     * re-rendering the fragment for an already-converted video (including
     * when the post-conversion metadata re-probe fails) can't start an
     * endless re-conversion loop.
     */
    private synchronized ConversionStart createOrGetConversionJob(Long videoId) {
        String existingJobId = videoToJob.get(videoId);
        if (existingJobId != null) {
            ConversionJob existing = jobs.get(existingJobId);
            if (existing != null && (existing.status == ConversionJob.Status.QUEUED
                    || existing.status == ConversionJob.Status.RUNNING)) {
                return new ConversionStart(existing, false);
            }
        }

        Video video = videoService.findById(videoId);
        if (video == null) return new ConversionStart(null, false);

        if (existingJobId != null) {
            ConversionJob existing = jobs.get(existingJobId);
            if (existing != null && existing.status == ConversionJob.Status.COMPLETED
                    && (isMp4File(video) || pendingFinalizes.containsKey(videoId))) {
                return new ConversionStart(existing, false);
            }
        }

        String jobId = "conv-" + videoId + "-" + System.currentTimeMillis();
        ConversionJob job = new ConversionJob(jobId, videoId);
        jobs.put(jobId, job);
        videoToJob.put(videoId, jobId);
        return new ConversionStart(job, true);
    }

    /** True when the video's current file is already an MP4 (e.g. produced by a completed conversion). */
    private boolean isMp4File(Video video) {
        if (video.container != null && video.container.toLowerCase(Locale.ROOT).contains("mp4")) return true;
        return video.path != null && video.path.toLowerCase(Locale.ROOT).endsWith(".mp4");
    }

    public ConversionJob startConversion(Long videoId) {
        ConversionStart start = createOrGetConversionJob(videoId);
        ConversionJob job = start.job();
        // Reused an existing QUEUED/RUNNING/COMPLETED job — nothing new to run.
        if (job == null || !start.created()) {
            return job;
        }

        conversionExecutor.submit(() -> {
            try {
                Video video = videoService.findById(videoId);
                if (video == null) throw new IllegalStateException("Video " + videoId + " no longer exists");
                runConversion(job, video);
            } catch (Exception e) {
                LOG.error("Conversion failed for video {}: {}", videoId, e.getMessage(), e);
                job.status = ConversionJob.Status.FAILED;
                job.errorMessage = e.getMessage();
                job.endTime = System.currentTimeMillis();
            }
            // Report the outcome to any batch that contains this video so batch
            // progress can't stall on a fragment-triggered job.
            updateBatchForVideo(videoId, job.status);
        });

        return job;
    }

    public ConversionJob getJobStatus(String jobId) {
        return jobs.get(jobId);
    }

    // ── Auto audio remux (permanent audio fix) ────────────────────────────

    /**
     * Non-blocking entry fired by the streaming path when a video is served as
     * a pure video remux but its audio had to be transcoded on the fly.
     * Schedules a background job that permanently fixes the file in place
     * (video bit-copied, incompatible audio tracks re-encoded to AAC) so future
     * streams of the same file become -c:a copy. Idempotent: reuses any
     * existing QUEUED/RUNNING/COMPLETED job, with a cooldown after FAILED.
     */
    public ConversionJob startAudioRemuxIfNeeded(Long videoId) {
        Video video = videoService.findById(videoId);
        if (video == null || !isAudioRemuxCandidate(video)) {
            return null;
        }

        // D17 fix: check cooldown BEFORE createOrGetConversionJob so a
        // too-recent failure never registers a QUEUED job that blocks forever.
        Long lastFailure = audioRemuxLastFailure.get(videoId);
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < AUDIO_REMUX_FAILED_COOLDOWN_MS) {
            LOG.debug("Skipping audio remux for video {} (failed {}ms ago, cooldown active)",
                    videoId, System.currentTimeMillis() - lastFailure);
            return null;
        }

        ConversionStart start = createOrGetConversionJob(videoId);
        ConversionJob job = start.job();
        if (job == null || !start.created()) {
            return job; // already queued/running/completed — nothing new to run
        }

        conversionExecutor.submit(() -> {
            try {
                runAudioRemux(job, video);
            } catch (Exception e) {
                LOG.error("Audio remux failed for video {}: {}", videoId, e.getMessage(), e);
                job.status = ConversionJob.Status.FAILED;
                job.errorMessage = e.getMessage();
                job.endTime = System.currentTimeMillis();
                audioRemuxLastFailure.put(videoId, System.currentTimeMillis());
                updateBatchForVideo(videoId, job.status);
            }
        });
        return job;
    }

    /**
     * True when the file is worth permanently fixing: its video stream is
     * already web-compatible (can be bit-copied into MP4) but the default
     * audio track is not in the copy list. The actual per-track decisions are
     * made by runAudioRemux after a fresh ffprobe.
     */
    private boolean isAudioRemuxCandidate(Video video) {
        if (video.videoCodec == null) return false;
        String vc = video.videoCodec.toLowerCase(Locale.ROOT);
        boolean copyableVideo = vc.contains("h264") || vc.contains("avc")
                || vc.contains("hevc") || vc.contains("h265");
        if (!copyableVideo) return false;

        if (video.audioCodec == null) return false; // unknown — leave it alone
        String ac = video.audioCodec.toLowerCase(Locale.ROOT);
        return !AUDIO_REMUX_COPY_CODECS.contains(ac);
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
        Path inputPath = resolveInputPath(video);
        File inputFile = inputPath.toFile();

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

        // Build output path — include videoId to prevent basename collisions
        // (D19: different sources like movie.mkv and movie.avi both producing
        // movie.mp4 would silently overwrite each other).
        String baseName = video.filename != null
                ? video.filename.replaceFirst("\\.[^.]+$", "")
                : inputFile.getName().replaceFirst("\\.[^.]+$", "");
        Path outputPath = inputPath.getParent().resolve(baseName + "-" + video.id + ".mp4");
        Path tempOutput = inputPath.getParent().resolve("." + baseName + "-" + video.id + ".tmp.mp4");

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
        finalizeConversion(job, video, inputPath, tempOutput, outputPath, ffmpegPath, imageSubtitleStreams);
    }

    // ── Shared conversion helpers ─────────────────────────────────────────

    private Path resolveInputPath(Video video) throws IOException {
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
        return inputPath;
    }

    private void finalizeConversion(ConversionJob job, Video video, Path inputPath, Path tempOutput,
                                    Path outputPath, String ffmpegPath, List<Integer> imageSubtitleStreams) throws Exception {
        job.message = "Verifying output...";
        job.progressPercent = 95;

        if (!tempOutput.toFile().exists() || tempOutput.toFile().length() == 0) {
            throw new IOException("Conversion produced an empty or missing output file");
        }

        // Windows file locks can make the atomic replace fail transiently
        moveTempToFinal(tempOutput, outputPath);

        // Extract image-based subtitle streams as .sup files alongside the output
        if (!imageSubtitleStreams.isEmpty()) {
            job.message = "Extracting subtitles...";
            extractImageSubtitleStreams(ffmpegPath, inputPath.toFile(), outputPath, imageSubtitleStreams);
        }

        job.message = "Updating database...";
        job.progressPercent = 98;

        // Defer the destructive finalize (old-file deletion + DB path swap)
        // while the video is actively streaming: the live transcode has the
        // source file open, and swapping the DB path mid-playback makes
        // subsequent player requests resolve to a different serving mode
        // (remux → direct-faststart), which manifests as playback restarting
        // from 0. finalizePendingIfIdle completes the swap once the video is
        // idle (on the next stream request).
        if (transcodingService.hasActiveTranscodesForVideo(video.id)) {
            pendingFinalizes.put(video.id, new PendingFinalize(inputPath, outputPath, System.currentTimeMillis()));
            job.status = ConversionJob.Status.COMPLETED;
            job.progressPercent = 100;
            job.message = "Converted; finalize deferred until video is no longer streaming";
            job.endTime = System.currentTimeMillis();
            LOG.info("Deferred finalize for video {} (actively streaming); old-file deletion + DB swap pending", video.id);
            return;
        }

        // Delete old file with retry (Windows file locks)
        // Only delete if it's actually a different file — converting an already-MP4 file
        // (due to container misdetection) would make inputPath == outputPath, and deleting
        // the "old" file would wipe the freshly-converted output.
        // Use normalize() instead of toRealPath() because the input file may have been
        // overwritten by the ATOMIC_MOVE above (when inputPath == outputPath), so toRealPath()
        // would fail with IOException.
        // D18 fix: wrap delete+update in a per-videoId lock so a concurrent
        // stream-open cannot observe the file mid-transition.
        Object finalizeLock = finalizeLocks.computeIfAbsent(video.id, k -> new Object());
        synchronized (finalizeLock) {
            if (!inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize())) {
                deleteOldFileWithRetry(inputPath);
            } else {
                LOG.info("Input and output paths are identical, skipping old file deletion: {}", inputPath);
            }

            // Update Video entity
            updateVideoRecord(video, outputPath, inputPath);
        }

        job.status = ConversionJob.Status.COMPLETED;
        job.progressPercent = 100;
        job.message = "Conversion completed successfully!";
        job.endTime = System.currentTimeMillis();
    }

    /**
     * Completes a deferred conversion finalize (old-file deletion + DB path
     * swap) once the video is no longer actively streaming. Called from the
     * streaming path BEFORE the video record is resolved, so a completed
     * conversion is picked up without ever switching serving mode mid-playback.
     *
     * @return true if a pending finalize existed and was completed
     */
    @Transactional
    public boolean finalizePendingIfIdle(Long videoId) {
        PendingFinalize pending = pendingFinalizes.get(videoId);
        if (pending == null) return false;
        if (transcodingService.hasActiveTranscodesForVideo(videoId)) {
            LOG.debug("Finalize still deferred for video {} (actively streaming)", videoId);
            return false;
        }
        if (!pendingFinalizes.remove(videoId, pending)) return false;
        try {
            Video video = videoService.findById(videoId);
            if (video == null) return false;
            Path current = video.path != null
                    ? java.nio.file.Paths.get(video.path).toAbsolutePath().normalize()
                    : null;
            if (current != null && current.equals(pending.outputPath().toAbsolutePath().normalize())) {
                return false; // already swapped
            }
            // D18 fix: wrap delete+update in a per-videoId lock so a concurrent
            // stream-open cannot observe the file mid-transition.
            Object finalizeLock = finalizeLocks.computeIfAbsent(videoId, k -> new Object());
            synchronized (finalizeLock) {
                if (!pending.inputPath().toAbsolutePath().normalize()
                        .equals(pending.outputPath().toAbsolutePath().normalize())) {
                    deleteOldFileWithRetry(pending.inputPath());
                }
                updateVideoRecord(video, pending.outputPath(), pending.inputPath());
            }
            LOG.info("Completed deferred finalize for video {}: path={}", videoId, pending.outputPath());
            return true;
        } catch (Exception e) {
            LOG.warn("Deferred finalize failed for video {}: {}", videoId, e.getMessage());
            pendingFinalizes.put(videoId, pending); // retry on the next touch
            return false;
        }
    }

    private void moveTempToFinal(Path tempOutput, Path outputPath) throws IOException {
        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Files.move(tempOutput, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                LOG.warn("Failed to move temp output to final (attempt {}/{}): {}", i + 1, maxAttempts, e.getMessage());
                if (i < maxAttempts - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    throw e;
                }
            }
        }
    }

    // ── Audio remux (permanent audio fix) ─────────────────────────────────

    private void runAudioRemux(ConversionJob job, Video video) throws Exception {
        job.status = ConversionJob.Status.RUNNING;
        job.message = "Starting audio remux...";
        job.progressPercent = 0;

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found. Please install FFmpeg and restart.");
        }

        Path inputPath = resolveInputPath(video);
        File inputFile = inputPath.toFile();

        // Build output path — same .tmp.mp4 → .mp4 swap as the full conversion.
        // Include videoId to prevent basename collisions (D19).
        String baseName = video.filename != null
                ? video.filename.replaceFirst("\\.[^.]+$", "")
                : inputFile.getName().replaceFirst("\\.[^.]+$", "");
        Path outputPath = inputPath.getParent().resolve(baseName + "-" + video.id + ".mp4");
        Path tempOutput = inputPath.getParent().resolve("." + baseName + "-" + video.id + ".tmp.mp4");

        // Fresh per-track probe: copy-vs-transcode decided per track, not from the
        // default track's codec alone (a mixed English-AAC + Spanish-AC3 file must
        // fix only the AC3 track, not transcode everything).
        List<AudioTrack> audioTracks = ffprobeAudioService.extractAudioTracks(video, inputFile.getAbsolutePath());

        // Probe subtitle streams — text go into MP4, image get extracted as .sup
        String ffprobePath = discoveryService.findFFprobeExecutable();
        SubtitleProbeResult subtitleProbe = (ffprobePath != null)
                ? probeSubtitleStreams(ffprobePath, inputFile.getAbsolutePath())
                : new SubtitleProbeResult(new ArrayList<>(), new ArrayList<>());
        List<Integer> textSubtitleStreams = subtitleProbe.textStreams();
        List<Integer> imageSubtitleStreams = subtitleProbe.imageStreams();

        List<String> command = buildAudioRemuxCommand(ffmpegPath, inputFile, tempOutput, video, audioTracks, textSubtitleStreams);
        runFfmpegProcess(job, command, video, inputFile, outputPath, tempOutput);

        finalizeConversion(job, video, inputPath, tempOutput, outputPath, ffmpegPath, imageSubtitleStreams);
    }

    private List<String> buildAudioRemuxCommand(String ffmpegPath, File inputFile, Path tempOutput,
                                                Video video, List<AudioTrack> audioTracks,
                                                List<Integer> textSubtitleStreams) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-v"); command.add("error");
        command.add("-hide_banner");
        command.add("-stats");
        command.add("-i"); command.add(inputFile.getAbsolutePath());

        // Video: bit-copy (web-compatibility is guaranteed by isAudioRemuxCandidate)
        command.add("-map"); command.add("0:v:0");
        command.add("-c:v"); command.add("copy");
        String videoCodec = video.videoCodec.toLowerCase(Locale.ROOT);
        if (videoCodec.contains("hevc") || videoCodec.contains("h265")) {
            command.add("-tag:v"); command.add("hvc1"); // Apple-compatible HEVC tag
        }

        command.add("-map"); command.add("0:a");
        if (audioTracks.isEmpty()) {
            // No probe data — default track is known incompatible, transcode everything
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("192k");
        } else {
            for (int i = 0; i < audioTracks.size(); i++) {
                String codec = audioTracks.get(i).codec != null
                        ? audioTracks.get(i).codec.toLowerCase(Locale.ROOT) : "";
                if (AUDIO_REMUX_COPY_CODECS.contains(codec)) {
                    command.add("-c:a:" + i); command.add("copy");
                    if (codec.equals("aac")) {
                        command.add("-bsf:a:" + i); command.add("aac_adtstoasc");
                    }
                } else {
                    command.add("-c:a:" + i); command.add("aac");
                    command.add("-b:a:" + i); command.add("192k");
                }
            }
            for (int i = 0; i < audioTracks.size(); i++) {
                if (audioTracks.get(i).isDefault) {
                    command.add("-disposition:a:" + i); command.add("default");
                    break;
                }
            }
        }

        // Subtitles: map only text-based streams (skip PGS/VOBSUB which crash mov_text)
        if (textSubtitleStreams != null && !textSubtitleStreams.isEmpty()) {
            for (int subIdx : textSubtitleStreams) {
                command.add("-map"); command.add("0:s:" + subIdx);
            }
            command.add("-c:s"); command.add("mov_text");
        } else {
            command.add("-sn");
        }

        command.add("-map_chapters"); command.add("0");
        command.add("-map_metadata"); command.add("0");
        command.add("-movflags"); command.add("+faststart");
        command.add("-avoid_negative_ts"); command.add("make_zero");
        command.add("-y");
        command.add(tempOutput.toAbsolutePath().toString());

        LOG.info("Audio remux FFmpeg command: {}", String.join(" ", command));
        return command;
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
                GpuDetectionService.GpuInfo amfGpu = discoveryService.getBestAmfGpu();
                if (amfGpu != null && amfGpu.deviceIndex() >= 0) {
                    command.add("-hwaccel_device"); command.add(String.valueOf(amfGpu.deviceIndex()));
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

        // True when decode frames stay on the device (-hwaccel_output_format matched
        // the encoder vendor): no auto-inserted SOFTWARE filter may touch them, so a
        // bare -pix_fmt would crash with "Impossible to convert ... src: cuda".
        boolean hwFramesOnDevice = isHardwareAttempt && hardwareDecoder != null
                && ((hardwareDecoder.contains("cuvid") && videoEncoder.contains("nvenc"))
                    || (hardwareDecoder.contains("qsv") && videoEncoder.contains("qsv"))
                    || (hardwareDecoder.contains("vaapi") && videoEncoder.contains("vaapi"))
                    || (hardwareDecoder.contains("amf") && videoEncoder.contains("amf"))
                    || (hardwareDecoder.contains("d3d11va") && videoEncoder != null && videoEncoder.contains("d3d11va")));

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
            if (videoEncoder.contains("h264")) {
                boolean addedGpuFmtFilter = false;
                if (hwFramesOnDevice && buildScaleFilter(hardwareDecoder, videoEncoder, 1080, video.resolution) == null) {
                    String fmtFilter = buildFormatFilter(hardwareDecoder, videoEncoder, video.resolution);
                    if (fmtFilter != null) {
                        command.add("-vf"); command.add(fmtFilter);
                        addedGpuFmtFilter = true;
                    } else {
                        command.add("-vf"); command.add("hwdownload");
                        addedGpuFmtFilter = true;
                    }
                }
                if (!addedGpuFmtFilter) {
                    command.add("-pix_fmt"); command.add("nv12");
                }
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
            int subtitleTypeIndex = 0;
            try (Scanner sc = new Scanner(process.getInputStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(",", 2);
                    if (parts.length < 2) continue;
                    try {
                        String codec = parts[1].toLowerCase(Locale.ROOT);
                        if (TEXT_SUBTITLE_CODECS.contains(codec)) {
                            textStreams.add(subtitleTypeIndex);
                        } else {
                            imageStreams.add(subtitleTypeIndex);
                        }
                    } catch (NumberFormatException ignored) {}
                    subtitleTypeIndex++;
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
            return "scale_cuda=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsVideoToolbox && encoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Cross-vendor fallback or software
        return "scale=" + targetW + ":" + targetH;
    }

    private String buildFormatFilter(String hardwareDecoder, String videoEncoder, String resolution) {
        if (hardwareDecoder == null || videoEncoder == null) return null;

        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}

        boolean decoderIsCuda = hardwareDecoder.contains("cuvid");
        boolean encoderIsNvenc = videoEncoder.contains("nvenc");
        boolean decoderIsQsv = hardwareDecoder.contains("qsv");
        boolean encoderIsQsv = videoEncoder.contains("qsv");
        boolean decoderIsVaapi = hardwareDecoder.contains("vaapi");
        boolean encoderIsVaapi = videoEncoder.contains("vaapi");
        boolean decoderIsAmf = hardwareDecoder.contains("amf");
        boolean encoderIsAmf = videoEncoder.contains("amf");

        if (decoderIsCuda && encoderIsNvenc)
            return "scale_cuda=" + w + ":" + h + ":format=nv12";
        if (decoderIsQsv && encoderIsQsv)
            return "scale_qsv=" + w + ":" + h + ":format=nv12";
        if (decoderIsVaapi && encoderIsVaapi)
            return "scale_vaapi=" + w + ":" + h + ":format=nv12";
        if (decoderIsAmf && encoderIsAmf)
            return "scale_amf=" + w + ":" + h + ":format=nv12";

        return null;
    }

    // ── FFmpeg process execution with progress parsing ────────────────────

    /** Waits for an ffmpeg process to exit while monitoring its stderr output.
     *  If no output arrives for FFMPEG_STALL_TIMEOUT_MS the process is wedged:
     *  it is force-killed and an IOException is thrown. Returns the exit code
     *  when the process completes on its own. */
    private int waitForFfmpegExit(Process process, AtomicLong lastProgressNs) throws IOException, InterruptedException {
        while (!process.waitFor(FFMPEG_STALL_POLL_MS, TimeUnit.MILLISECONDS)) {
            long idleMs = (System.nanoTime() - lastProgressNs.get()) / 1_000_000L;
            if (idleMs > FFMPEG_STALL_TIMEOUT_MS) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("FFmpeg stalled (no progress output for "
                        + (FFMPEG_STALL_TIMEOUT_MS / 60_000L) + " minutes) and was terminated");
            }
        }
        return process.exitValue();
    }

    private void runFfmpegProcess(ConversionJob job, List<String> command, Video video,
                                   File inputFile, Path outputPath, Path tempOutput) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        job.process = process;

        // Parse stderr for progress (time=HH:MM:SS.mm)
        long durationMs = video.duration != null && video.duration > 0 ? video.duration : 0;
        final StringBuilder stderrCapture = new StringBuilder();

        // Stall detection: with -stats ffmpeg emits a progress line roughly every
        // second, so any stderr output proves the process is alive and working.
        // If nothing arrives for FFMPEG_STALL_TIMEOUT_MS the process is wedged
        // (deadlocked codec init, hung I/O, frozen pipe) and gets killed.
        final AtomicLong lastProgressNs = new AtomicLong(System.nanoTime());

        Thread progressReader = new Thread(() -> {
            Pattern timePattern = Pattern.compile("time=(\\d+):(\\d+):(\\d+)\\.(\\d+)");
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    stderrCapture.append(line).append("\n");
                    lastProgressNs.set(System.nanoTime());
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

        int exitCode = waitForFfmpegExit(process, lastProgressNs);
        progressReader.join(2000);

        if (exitCode != 0) {
            // Read error output for diagnostics
            String errorOutput = stderrCapture.toString();

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
                // Drain stderr on the retry pass too: it both feeds the stall
                // guard and prevents a full stderr pipe from deadlocking the process.
                final AtomicLong p2ProgressNs = new AtomicLong(System.nanoTime());
                Thread p2Drain = new Thread(() -> {
                    try (java.util.Scanner sc = new java.util.Scanner(p2.getErrorStream())) {
                        while (sc.hasNextLine()) {
                            sc.nextLine();
                            p2ProgressNs.set(System.nanoTime());
                        }
                    } catch (Exception ignored) {}
                });
                p2Drain.setDaemon(true);
                p2Drain.start();
                int exit2 = waitForFfmpegExit(p2, p2ProgressNs);
                p2Drain.join(2000);
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
            Models.Video.MediaFile mediaFile = Models.Video.MediaFile.find("path", oldPath).firstResult();
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
            Models.Settings.Settings settings = settingsService.getOrCreateSettings();
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
            // D17 fix: evict zombie QUEUED jobs older than QUEUED_JOB_TTL_MS.
            // A genuinely in-flight job will be RUNNING, not QUEUED.
            if (job.status == ConversionJob.Status.QUEUED
                    && now - job.startTime > QUEUED_JOB_TTL_MS) {
                LOG.warn("Evicting zombie QUEUED job {} for video {} (queued {}ms ago, TTL exceeded)",
                        job.jobId, job.videoId, now - job.startTime);
                videoToJob.remove(job.videoId, job.jobId);
                return true;
            }
            return false;
        });
        audioRemuxLastFailure.entrySet().removeIf(entry -> now - entry.getValue() > AUDIO_REMUX_FAILED_COOLDOWN_MS);

        // D20 fix: evict stale pendingFinalizes to prevent source+mp4+map-entry
        // leaking forever when a converted video is never re-streamed.
        pendingFinalizes.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt() > PENDING_FINALIZE_TTL_MS) {
                LOG.warn("Evicting stale pendingFinalize for video {} (age {}ms, TTL exceeded)",
                        entry.getKey(), now - entry.getValue().createdAt());
                return true;
            }
            return false;
        });

        // D21 safety net: remove any lingering inactive batches that were not
        // caught by updateBatchForVideo (e.g. race during batch finalization).
        batches.entrySet().removeIf(entry -> !entry.getValue().active);
    }
}
