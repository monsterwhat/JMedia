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

    public record ConversionOptions(
        String targetCodec,      // "h264", "hevc", "av1" — default "h264"
        String targetContainer,  // "mp4", "mkv" — default "mp4"
        String targetAudioCodec, // "aac", "ac3", "eac3", "copy" — default "aac"
        Integer crf,             // 18-28 — default 23
        String preset,           // FFmpeg preset — default depends on encoder
        Integer maxHeight,       // 0 = no limit, or 720/1080/1440/2160 — default 0
        boolean keepOriginal     // keep the source as "<name>.<ext>.original" — default true
    ) {
        /**
         * Safe defaults: no resolution cap and the original is kept. The old
         * defaults capped at 1080p and deleted the source after a lossy
         * re-encode, so a 4K file was permanently downgraded.
         */
        public static ConversionOptions defaults() {
            return new ConversionOptions("h264", "mp4", "aac", 23, null, 0, true);
        }

        /** Keeps the original unless told otherwise. */
        public ConversionOptions(String targetCodec, String targetContainer, String targetAudioCodec,
                                 Integer crf, String preset, Integer maxHeight) {
            this(targetCodec, targetContainer, targetAudioCodec, crf, preset, maxHeight, true);
        }
    }

    /** Suffix for a kept original. Not a video extension, so the scanner never imports it. */
    static final String ORIGINAL_SUFFIX = ".original";

    /**
     * Where a kept original goes: {@code Movie.mkv -> Movie.mkv.original},
     * then {@code Movie.mkv.original.1}, ... if that name is taken. Same folder,
     * so the move is a cheap rename on the same volume.
     */
    static Path backupPathFor(Path input) {
        Path candidate = input.resolveSibling(input.getFileName() + ORIGINAL_SUFFIX);
        int n = 1;
        while (Files.exists(candidate)) {
            candidate = input.resolveSibling(input.getFileName() + ORIGINAL_SUFFIX + "." + n++);
        }
        return candidate;
    }

    private static final Logger LOG = LoggerFactory.getLogger(VideoConversionService.class);

    /* Only codecs every browser can decode natively are safe to stream-copy into
     * MP4. AC3/EAC3 are decoded by Chrome/Edge on Windows, Firefox on Windows,
     * and Safari — but NOT by Chrome on macOS or Firefox on Linux.  For the
     * permanent-conversion path we include them (no UA context here); the live
     * streaming path (TranscodingService.canCopyAudioForClient) gates them. */
    private static final List<String> MP4_COMPATIBLE_AUDIO_CODECS = List.of(
        "aac", "mp3", "opus", "vorbis", "ac3", "eac3"
    );

    private static final List<String> TEXT_SUBTITLE_CODECS = List.of(
        "subrip", "ass", "ssa", "mov_text", "text"
    );

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    GpuScheduler gpuScheduler;

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
    /**
     * A converted file waiting for its source to stop streaming. The new file
     * stays at {@code tempOutput} (hidden from the scanner) until the swap:
     * replacing or renaming a file that is open for streaming fails on Windows.
     */
    private record PendingFinalize(Path inputPath, Path tempOutput, Path outputPath,
                                   boolean keepOriginal, long createdAt) {}

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
        public ConversionOptions options = ConversionOptions.defaults();

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
    private final ConcurrentHashMap<Long, ConversionOptions> pendingBatchOptions = new ConcurrentHashMap<>();
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

    public String startBatchConversion(List<Long> videoIds) {
        return startBatchConversion(videoIds, null);
    }

    /**
     * Queues all eligible (non-MP4) videos for sequential conversion.
     * Returns a batch ID that can be polled for overall progress.
     */
    public String startBatchConversion(List<Long> videoIds, ConversionOptions options) {
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
                if (options != null) {
                    pendingBatchOptions.put(id, options);
                }
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
        ConversionOptions batchOptions = pendingBatchOptions.remove(nextId);
        queueProcessing = true;
        conversionExecutor.submit(() -> {
            try {
                ConversionJob job = doStartConversion(nextId, batchOptions);
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

    private ConversionJob doStartConversion(Long videoId, ConversionOptions options) {
        ConversionStart start = createOrGetConversionJob(videoId);
        ConversionJob job = start.job();
        if (job == null) return null;

        if (start.created()) {
            if (options != null) {
                job.options = options;
            }
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

    /**
     * Start a conversion with custom options (codec, container, CRF, etc.).
     * Falls back to defaults when {@code options} is null.
     */
    public ConversionJob startConversion(Long videoId, ConversionOptions options) {
        ConversionStart start = createOrGetConversionJob(videoId);
        ConversionJob job = start.job();
        if (job == null || !start.created()) {
            return job;
        }
        job.options = options != null ? options : ConversionOptions.defaults();

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
        String outputExt = job.options.targetContainer();
        Path outputPath = inputPath.getParent().resolve(baseName + "." + outputExt);
        // D19: another file already owns that name (movie.avi converting next to
        // a movie.mp4 from movie.mkv) - add the video id instead of replacing it.
        if (Files.exists(outputPath)
                && !outputPath.toAbsolutePath().normalize().equals(inputPath.toAbsolutePath().normalize())) {
            outputPath = inputPath.getParent().resolve(baseName + "-" + video.id + "." + outputExt);
        }
        Path tempOutput = inputPath.getParent().resolve("." + baseName + ".tmp." + outputExt);

        // Determine hardware acceleration
        boolean useHardware = isHardwareAccelerationEnabled();
        String hardwareDecoder = useHardware ? discoveryService.getHardwareDecoder(video.videoCodec) : null;
        List<String> hwEncoders = discoveryService.getAvailableHardwareEncoders();

        // Build encoder attempt list (same pattern as TranscodingService: HW encoders first, libx264 last)
        List<String> attemptEncoders = new ArrayList<>();
        String targetCodec = job.options.targetCodec();
        if (useHardware && hwEncoders != null) {
            for (String enc : hwEncoders) {
                if (targetCodec.startsWith("hevc") ? enc.startsWith("hevc")
                        : targetCodec.startsWith("av1") ? enc.startsWith("av1")
                        : enc.startsWith("h264")) {
                    attemptEncoders.add(enc);
                }
            }
        }
        attemptEncoders.add(targetCodec.startsWith("hevc") ? "libx265"
                : targetCodec.startsWith("av1") ? "libsvtav1"
                : "libx264");

        // Probe subtitle streams — text go into MP4, image get extracted as .sup
        String ffprobePath = discoveryService.findFFprobeExecutable();
        SubtitleProbeResult subtitleProbe = (ffprobePath != null)
                ? probeSubtitleStreams(ffprobePath, inputFile.getAbsolutePath())
                : new SubtitleProbeResult(new ArrayList<>(), new ArrayList<>());
        List<Integer> textSubtitleStreams = subtitleProbe.textStreams();
        List<Integer> imageSubtitleStreams = subtitleProbe.imageStreams();

        // Bit depth and HDR decide the output pixel format; refuse HDR -> H.264
        // before spending hours on an encode that would look washed out.
        SourceVideoInfo sourceVideo = probeSourceVideo(ffprobePath, inputFile.getAbsolutePath());
        String refusal = hdrRefusal(targetCodec, sourceVideo);
        if (refusal != null) {
            throw new IOException(refusal);
        }
        // Per-track audio decisions need every track's codec and channel count.
        List<AudioTrack> audioTracks = ffprobeAudioService.extractAudioTracks(video, inputFile.getAbsolutePath());

        Exception lastException = null;
        boolean conversionStarted = false;

        String softwareEncoder = targetCodec.startsWith("hevc") ? "libx265"
                : targetCodec.startsWith("av1") ? "libsvtav1"
                : "libx264";

        for (String encoder : attemptEncoders) {
            if (conversionStarted) break; // successful, skip remaining

            boolean isHardwareAttempt = !encoder.equals(softwareEncoder);
            String preset;
            if (job.options.preset() != null) {
                preset = job.options.preset();
            } else if (isHardwareAttempt) {
                if (encoder.contains("nvenc")) preset = "fast";
                else if (encoder.contains("amf")) preset = "speed";
                else if (encoder.contains("qsv") || encoder.contains("videotoolbox")) preset = "fast";
                else preset = "medium";
            } else {
                preset = "veryfast";
            }

            try {
                GpuScheduler.GpuLease conversionLease = null;
                if (isHardwareAttempt) {
                    conversionLease = gpuScheduler.acquire(video.videoCodec, encoder);
                }
                try {
                    List<String> command = buildFfmpegCommand(ffmpegPath, inputFile, tempOutput, video,
                            encoder, isHardwareAttempt, hardwareDecoder, preset, textSubtitleStreams, job.options, conversionLease,
                            sourceVideo, audioTracks);
                    runFfmpegProcess(job, command, video, inputFile, outputPath, tempOutput);
                    conversionStarted = true;
                } finally {
                    if (conversionLease != null) {
                        gpuScheduler.release(conversionLease);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Encoder '{}' failed for video {}: {}", encoder, video.id, e.getMessage());
                if (isHardwareAttempt) {
                    discoveryService.recordEncoderFailure(encoder);
                }
                // Clean up partial temp file
                try { Files.deleteIfExists(tempOutput); } catch (IOException ignored) {}
                // If this was the last attempt (software fallback), propagate the error
                if (encoder.equals(softwareEncoder)) {
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

        // Extract image-based subtitle streams as .sup files next to the output.
        // Done before the swap, while the source is still at its own path.
        if (!imageSubtitleStreams.isEmpty()) {
            job.message = "Extracting subtitles...";
            extractImageSubtitleStreams(ffmpegPath, inputPath.toFile(), outputPath, imageSubtitleStreams);
        }

        boolean keepOriginal = job.options == null || job.options.keepOriginal();

        job.message = "Updating database...";
        job.progressPercent = 98;

        // Defer the swap (moving the new file in, keeping or deleting the old
        // one, DB path update) while the video is actively streaming: the live
        // transcode has the source file open, and swapping the DB path
        // mid-playback makes subsequent player requests resolve to a different
        // serving mode (remux -> direct-faststart), which manifests as playback
        // restarting from 0. The new file waits at tempOutput (hidden from the
        // scanner); finalizePendingIfIdle completes the swap on the next stream
        // request once the video is idle.
        if (transcodingService.hasActiveTranscodesForVideo(video.id)) {
            pendingFinalizes.put(video.id, new PendingFinalize(inputPath, tempOutput, outputPath,
                    keepOriginal, System.currentTimeMillis()));
            job.status = ConversionJob.Status.COMPLETED;
            job.progressPercent = 100;
            job.message = "Converted; finalize deferred until video is no longer streaming";
            job.endTime = System.currentTimeMillis();
            LOG.info("Deferred finalize for video {} (actively streaming); file swap + DB update pending", video.id);
            return;
        }

        swapInConvertedFile(video, inputPath, tempOutput, outputPath, keepOriginal);

        job.status = ConversionJob.Status.COMPLETED;
        job.progressPercent = 100;
        job.message = keepOriginal
                ? "Conversion completed successfully! Original kept as " + inputPath.getFileName() + ORIGINAL_SUFFIX
                : "Conversion completed successfully!";
        job.endTime = System.currentTimeMillis();
    }

    /**
     * Puts the converted file in place and points the video record at it.
     *
     * <p>keepOriginal: the source is renamed to {@code <name>.<ext>.original}
     * first (also when input and output share a path, e.g. MP4 to MP4, which
     * previously overwrote the source). If moving the new file in then fails,
     * the original is moved back. Otherwise the old behaviour: replace, then
     * delete a source at a different path.
     *
     * <p>D18: runs under a per-video lock so a concurrent stream-open cannot
     * observe the file mid-transition.
     */
    private void swapInConvertedFile(Video video, Path inputPath, Path tempOutput, Path outputPath,
                                     boolean keepOriginal) throws IOException {
        boolean samePath = inputPath.toAbsolutePath().normalize()
                .equals(outputPath.toAbsolutePath().normalize());
        Object finalizeLock = finalizeLocks.computeIfAbsent(video.id, k -> new Object());
        synchronized (finalizeLock) {
            if (keepOriginal) {
                Path backup = null;
                if (Files.exists(inputPath)) {
                    backup = backupPathFor(inputPath);
                    moveWithRetry(inputPath, backup);
                    LOG.info("Kept original for video {} as {}", video.id, backup);
                }
                try {
                    moveTempToFinal(tempOutput, outputPath);
                } catch (IOException e) {
                    if (backup != null) {
                        try {
                            moveWithRetry(backup, inputPath);
                        } catch (IOException restoreError) {
                            LOG.error("Could not restore original {} from {}: {}", inputPath, backup,
                                    restoreError.getMessage());
                        }
                    }
                    throw e;
                }
            } else {
                moveTempToFinal(tempOutput, outputPath);
                if (!samePath) {
                    deleteOldFileWithRetry(inputPath);
                } else {
                    LOG.info("Input and output paths are identical, nothing to delete: {}", inputPath);
                }
            }
            updateVideoRecord(video, outputPath, inputPath);
        }
    }

    /**
     * Completes a deferred conversion finalize (file swap + DB path update)
     * once the video is no longer actively streaming. Called from the
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
            if (!Files.exists(pending.tempOutput())) {
                LOG.warn("Deferred finalize for video {}: converted file {} is gone, nothing to swap",
                        videoId, pending.tempOutput());
                return false;
            }
            swapInConvertedFile(video, pending.inputPath(), pending.tempOutput(), pending.outputPath(),
                    pending.keepOriginal());
            LOG.info("Completed deferred finalize for video {}: path={}", videoId, pending.outputPath());
            return true;
        } catch (Exception e) {
            LOG.warn("Deferred finalize failed for video {}: {}", videoId, e.getMessage());
            pendingFinalizes.put(videoId, pending); // retry on the next touch
            return false;
        }
    }

    /** Rename with a few retries (Windows file locks). */
    private void moveWithRetry(Path from, Path to) throws IOException {
        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                LOG.warn("Failed to move {} to {} (attempt {}/{}): {}", from, to, i + 1, maxAttempts, e.getMessage());
                if (i == maxAttempts - 1) throw e;
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
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
                                             String preset, List<Integer> textSubtitleStreams,
                                             ConversionOptions options, GpuScheduler.GpuLease gpuLease,
                                             SourceVideoInfo source, List<AudioTrack> audioTracks) {
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
                String index = gpuLease != null && gpuLease.gpu().deviceIndex() >= 0
                    ? String.valueOf(gpuLease.gpu().deviceIndex())
                    : discoveryService.getBestNvidiaDeviceIndex();
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
                String device = gpuLease != null && gpuLease.gpu().devicePath() != null
                    ? gpuLease.gpu().devicePath()
                    : discoveryService.getBestQsvDevicePath();
                if (device != null) {
                    command.add("-qsv_device"); command.add(device);
                }
            } else if (hardwareDecoder.contains("vaapi")) {
                command.add("-hwaccel"); command.add("vaapi");
                if (videoEncoder.contains("vaapi")) {
                    command.add("-hwaccel_output_format"); command.add("vaapi");
                }
                String device = gpuLease != null && gpuLease.gpu().devicePath() != null
                    ? gpuLease.gpu().devicePath()
                    : discoveryService.getBestVaaPiDevicePath();
                if (device != null) {
                    command.add("-hwaccel_device"); command.add(device);
                }
            } else if (hardwareDecoder.contains("amf")) {
                command.add("-hwaccel"); command.add("amf");
                if (videoEncoder.contains("amf")) {
                    command.add("-hwaccel_output_format"); command.add("amf");
                }
                GpuDetectionService.GpuInfo amfGpu = gpuLease != null
                    ? gpuLease.gpu()
                    : discoveryService.getBestAmfGpu();
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

        boolean isMkv = "mkv".equalsIgnoreCase(options.targetContainer());
        int crf = options.crf();
        int maxHeight = options.maxHeight();

        command.add("-v"); command.add("error");
        command.add("-hide_banner");
        command.add("-stats");

        command.add("-i"); command.add(inputFile.getAbsolutePath());

        // 0:V = real video streams only. 0:v also matched cover art and other
        // attached pictures, which were then encoded as an extra video track.
        command.add("-map"); command.add("0:V");
        command.add("-c:v"); command.add(videoEncoder);

        String softwareEncoder = options.targetCodec().startsWith("hevc") ? "libx265"
                : options.targetCodec().startsWith("av1") ? "libsvtav1"
                : "libx264";

        // 10-bit sources stay 10-bit for HEVC/AV1 (H.264 output is always 8-bit;
        // HDR to H.264 is refused before this point, see hdrRefusal).
        boolean tenBit = keepsHighBitDepth(options.targetCodec(), source);
        String gpuFormat = tenBit ? "p010" : "nv12";

        if (!videoEncoder.equals(softwareEncoder)) {
            // HW encoder quality settings
            if (videoEncoder.contains("nvenc")) {
                command.add("-preset"); command.add(preset);
                command.add("-rc"); command.add("vbr");
                command.add("-cq"); command.add(String.valueOf(crf));
                if (!videoEncoder.contains("av1")) {
                    String profile = videoEncoder.contains("hevc") ? (tenBit ? "main10" : "main") : "high";
                    command.add("-profile:v"); command.add(profile);
                }
            } else if (videoEncoder.contains("amf")) {
                // -preset and -quality are the same AMF option (the later one
                // wins), so only -quality is passed.
                command.add("-usage"); command.add("transcoding");
                command.add("-quality"); command.add("quality");
            } else if (videoEncoder.contains("qsv")) {
                command.add("-preset"); command.add(preset);
                command.add("-global_quality"); command.add(String.valueOf(crf));
            } else if (videoEncoder.contains("videotoolbox")) {
                command.add("-quality"); command.add("70");
            } else if (videoEncoder.contains("vaapi")) {
                command.add("-rc_mode"); command.add("CQP");
                command.add("-qp"); command.add(String.valueOf(crf));
            } else {
                command.add("-preset"); command.add(preset);
                command.add("-crf"); command.add(String.valueOf(crf));
            }
            if (tenBit && videoEncoder.contains("hevc") && !videoEncoder.contains("nvenc")) {
                command.add("-profile:v"); command.add("main10");
            }
            if (videoEncoder.contains("h264") || videoEncoder.contains("hevc")) {
                boolean addedGpuFmtFilter = false;
                if (hwFramesOnDevice && buildScaleFilter(hardwareDecoder, videoEncoder, maxHeight, video.resolution, gpuFormat) == null) {
                    String fmtFilter = buildFormatFilter(hardwareDecoder, videoEncoder, video.resolution, gpuFormat);
                    if (fmtFilter != null) {
                        command.add("-vf"); command.add(fmtFilter);
                        addedGpuFmtFilter = true;
                    } else {
                        command.add("-vf"); command.add("hwdownload");
                        addedGpuFmtFilter = true;
                    }
                }
                // Frames on the GPU are converted by the vendor scale filter below;
                // a software -pix_fmt on them fails ("Impossible to convert ... cuda").
                if (HlsService.shouldWriteSoftwarePixFmt(addedGpuFmtFilter, hwFramesOnDevice)) {
                    command.add("-pix_fmt"); command.add(tenBit ? "p010le" : "nv12");
                }
            }
        } else {
            command.add("-preset"); command.add(preset);
            command.add("-crf"); command.add(String.valueOf(crf));
            command.add("-pix_fmt"); command.add(tenBit ? "yuv420p10le" : "yuv420p");
        }

        // Colour tags (primaries, transfer, matrix) need no flags: FFmpeg carries
        // them from the decoded frames to the encoder, HDR10/HLG included.

        // Scale filter — cap at configured max height, never upscale
        if (maxHeight > 0) {
            String scaleFilter = buildScaleFilter(hardwareDecoder, videoEncoder, maxHeight, video.resolution, gpuFormat);
            if (scaleFilter != null) {
                command.add("-vf"); command.add(scaleFilter);
            }
        }

        // Audio: decided per track (0:a? so files without audio still convert).
        command.add("-map"); command.add("0:a?");
        command.addAll(audioArgs(audioTracks, options.targetAudioCodec(), isMkv));

        // Subtitles
        if (isMkv) {
            // MKV supports all subtitle formats — map everything
            command.add("-map"); command.add("0:s?");
            command.add("-c:s"); command.add("copy");
        } else if (textSubtitleStreams != null && !textSubtitleStreams.isEmpty()) {
            for (int subIdx : textSubtitleStreams) {
                command.add("-map"); command.add("0:s:" + subIdx);
            }
            command.add("-c:s"); command.add("mov_text");
        } else {
            command.add("-sn");
        }

        // Chapters and metadata passthrough
        command.add("-map_chapters"); command.add("0");
        command.add("-map_metadata"); command.add("0");

        // Web-optimized only for MP4
        if (!isMkv) {
            command.add("-movflags"); command.add("+faststart");
        }
        command.add("-avoid_negative_ts"); command.add("make_zero");

        command.add("-y"); // overwrite temp output
        command.add(tempOutput.toAbsolutePath().toString());

        LOG.info("Convert FFmpeg command: {}", String.join(" ", command));
        return command;
    }

    private static boolean isAudioCodecMp4Compatible(String audioCodec) {
        if (audioCodec == null) {
            return true; // unknown — try copy, fail through if it doesn't work
        }
        return MP4_COMPATIBLE_AUDIO_CODECS.contains(audioCodec.toLowerCase(Locale.ROOT));
    }

    // ── Source video facts (bit depth, HDR) ───────────────────────────────

    /** Pixel format and colour description of the source's main video stream. */
    record SourceVideoInfo(String pixFmt, String colorTransfer, String colorPrimaries, String colorSpace) {
        static final SourceVideoInfo UNKNOWN = new SourceVideoInfo(null, null, null, null);

        /** 10/12/16-bit formats: yuv420p10le, p010le, yuv422p12le, ... */
        boolean isHighBitDepth() {
            return pixFmt != null && pixFmt.toLowerCase(Locale.ROOT).matches(".*(10|12|14|16)(le|be)$");
        }

        /** HDR10/HDR10+/Dolby Vision base layer (PQ) or HLG. */
        boolean isHdr() {
            if (colorTransfer == null) return false;
            String t = colorTransfer.toLowerCase(Locale.ROOT);
            return t.equals("smpte2084") || t.equals("arib-std-b67");
        }
    }

    /** H.264 output is always 8-bit; HEVC and AV1 keep a 10-bit (or deeper) source at 10-bit. */
    static boolean keepsHighBitDepth(String targetCodec, SourceVideoInfo source) {
        return source != null && source.isHighBitDepth()
                && targetCodec != null && !targetCodec.startsWith("h264");
    }

    /**
     * Why this conversion must not run, or null. There is no tone mapping, so
     * HDR to 8-bit H.264 would permanently produce a washed-out picture.
     */
    static String hdrRefusal(String targetCodec, SourceVideoInfo source) {
        if (source != null && source.isHdr() && (targetCodec == null || targetCodec.startsWith("h264"))) {
            return "This video is HDR (" + source.colorTransfer() + "). Converting it to H.264 would make it "
                    + "look washed out, because HDR cannot be kept in 8-bit H.264. Choose HEVC or AV1 to keep HDR.";
        }
        return null;
    }

    private SourceVideoInfo probeSourceVideo(String ffprobePath, String inputPath) {
        if (ffprobePath == null) return SourceVideoInfo.UNKNOWN;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "V:0",
                    "-show_entries", "stream=pix_fmt,color_transfer,color_primaries,color_space",
                    "-of", "default=noprint_wrappers=1",
                    inputPath
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            java.util.Map<String, String> values = new java.util.HashMap<>();
            try (Scanner sc = new Scanner(process.getInputStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    int eq = line.indexOf('=');
                    if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("FFprobe video probe timed out for: {}", inputPath);
                return SourceVideoInfo.UNKNOWN;
            }
            return new SourceVideoInfo(values.get("pix_fmt"), values.get("color_transfer"),
                    values.get("color_primaries"), values.get("color_space"));
        } catch (Exception e) {
            LOG.warn("Failed to probe video stream of {}: {}", inputPath, e.getMessage());
            return SourceVideoInfo.UNKNOWN;
        }
    }

    // ── Audio (per track) ─────────────────────────────────────────────────

    /**
     * Per-track audio options for {@code -map 0:a?}. A track is copied when it
     * can go into the target container as it is; otherwise it is encoded with
     * the chosen codec at its own channel count (the old command downmixed every
     * track to stereo, and judged MP4 compatibility on the first track only).
     * AC-3/E-AC-3 top out at 5.1, so 7.1 goes to 6 channels for those.
     */
    static List<String> audioArgs(List<AudioTrack> tracks, String targetAudioCodec, boolean isMkv) {
        String target = targetAudioCodec == null ? "aac" : targetAudioCodec.toLowerCase(Locale.ROOT);
        String encoder = "copy".equals(target) ? "aac" : target;
        List<String> args = new ArrayList<>();
        if (tracks == null || tracks.isEmpty()) {
            // No probe data: copy what MP4 can hold is unknown, so encode, keeping channels.
            if ("copy".equals(target) && isMkv) {
                args.addAll(List.of("-c:a", "copy"));
            } else {
                args.addAll(List.of("-c:a", encoder, "-b:a", "384k"));
            }
            return args;
        }
        for (int i = 0; i < tracks.size(); i++) {
            AudioTrack track = tracks.get(i);
            String codec = track.codec != null ? track.codec.toLowerCase(Locale.ROOT) : null;
            boolean copy = "copy".equals(target)
                    ? (isMkv || isAudioCodecMp4Compatible(codec))
                    : (isMkv ? target.equals(codec) : (codec != null && isAudioCodecMp4Compatible(codec)));
            if (copy) {
                args.add("-c:a:" + i); args.add("copy");
                if ("aac".equals(codec) && !isMkv) {
                    args.add("-bsf:a:" + i); args.add("aac_adtstoasc");
                }
                continue;
            }
            int channels = track.channels != null && track.channels > 0 ? track.channels : 2;
            boolean dolby = encoder.equals("ac3") || encoder.equals("eac3");
            if (dolby && channels > 6) {
                args.add("-ac:a:" + i); args.add("6");
                channels = 6;
            }
            String bitrate = channels <= 2 ? "192k" : channels <= 6 ? (dolby ? "448k" : "384k") : "512k";
            args.add("-c:a:" + i); args.add(encoder);
            args.add("-b:a:" + i); args.add(bitrate);
        }
        return args;
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
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("FFprobe subtitle probe timed out for: {}", inputPath);
                return new SubtitleProbeResult(Collections.emptyList(), Collections.emptyList());
            }
            int exitCode = process.exitValue();
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

    private String buildScaleFilter(String hardwareDecoder, String videoEncoder, int targetHeight, String resolution,
                                    String gpuFormat) {
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
            return "scale_cuda=" + targetW + ":" + targetH + ":format=" + gpuFormat;
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH + ":format=" + gpuFormat;
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH + ":format=" + gpuFormat;
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH + ":format=" + gpuFormat;
        } else if (decoderIsVideoToolbox && encoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Cross-vendor fallback or software
        return "scale=" + targetW + ":" + targetH;
    }

    private String buildFormatFilter(String hardwareDecoder, String videoEncoder, String resolution,
                                     String gpuFormat) {
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
            return "scale_cuda=" + w + ":" + h + ":format=" + gpuFormat;
        if (decoderIsQsv && encoderIsQsv)
            return "scale_qsv=" + w + ":" + h + ":format=" + gpuFormat;
        if (decoderIsVaapi && encoderIsVaapi)
            return "scale_vaapi=" + w + ":" + h + ":format=" + gpuFormat;
        if (decoderIsAmf && encoderIsAmf)
            return "scale_amf=" + w + ":" + h + ":format=" + gpuFormat;

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
