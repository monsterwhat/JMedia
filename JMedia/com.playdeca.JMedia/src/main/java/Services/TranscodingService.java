package Services;

import Models.Settings;
import Models.Video;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class TranscodingService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscodingService.class);

    private static final List<String> MP4_COMPATIBLE_AUDIO_CODECS = List.of(
        "aac", "mp3", "ac3", "eac3"
    );

    @Inject
    VideoService videoService;

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoConversionService videoConversionService;

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<String, Long> processLastOutput = new ConcurrentHashMap<>();
    private final Map<Long, java.util.Set<String>> videoProcessKeys = new ConcurrentHashMap<>();

    private static final long TRANSCODE_IDLE_TTL_MS = 48 * 60 * 60 * 1000L;
    private static final long CACHE_FILE_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long TRANSCODE_START_TIMEOUT_MS = 90_000L;
    private static final long TRANSCODE_STALL_TTL_MS = 10 * 60 * 1000L; // no output for 10min => abandoned
    private static final long PARTIAL_CACHE_GRACE_MS = 60 * 60 * 1000L; // delete partial (no .done) after 1h idle
    private static final long TRANSCODE_FILE_REMOVED_GRACE_MS = 5_000L; // waiters allow this long for a writer to re-create the temp file

    private static final int AUTO_MAX_CONCURRENT_TRANSCODES = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() / 4, 4));
    private java.util.concurrent.Semaphore transcodePermits; // sized in init() from Settings (0 = auto)

    private final AtomicLong transcodeAttemptCount = new AtomicLong(0);
    private final AtomicLong transcodeFailureCount = new AtomicLong(0);
    private final AtomicLong transcodeOomCount = new AtomicLong(0);
    private final AtomicLong transcodeEofRetryCount = new AtomicLong(0);
    private final AtomicLong transcodeHwFallbackCount = new AtomicLong(0);
    private final AtomicLong transcodeRetryCount = new AtomicLong(0);

    private static class ActiveTranscode {
        final String key;
        volatile Process process; // non-final so placeholder can be updated after FFmpeg starts
        final Path tempFile;
        final AtomicInteger refCount = new AtomicInteger(1);
        final StringBuilder errorOutput = new StringBuilder();
        volatile long lastAccessed = System.currentTimeMillis();
        volatile boolean completed;
        volatile boolean failed;
        volatile boolean discontinuityDetected = false; // New flag for discontinuity
        ScheduledFuture<?> cleanupFuture;
        volatile long lastFileGrowth = System.currentTimeMillis();
        volatile long lastFileSize = 0;

        ActiveTranscode(String key, Process process, Path tempFile) {
            this.key = key;
            this.process = process;
            this.tempFile = tempFile;
        }
    }

    private final ConcurrentHashMap<String, ActiveTranscode> activeTranscodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> transcodeNeededCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "transcode-cleanup");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService cacheCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-cleanup");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        cacheCleanupExecutor.scheduleAtFixedRate(this::cleanupOldCacheFiles, 1, 1, TimeUnit.HOURS);
        cleanupExecutor.scheduleAtFixedRate(this::sweepStalledProcesses, 1, 1, TimeUnit.MINUTES);
        int poolSize = resolveMaxConcurrentTranscodes();
        transcodePermits = new java.util.concurrent.Semaphore(poolSize, true);
        LOG.info("Max concurrent transcodes: {} ({} available processors)", poolSize, Runtime.getRuntime().availableProcessors());
    }

    private int resolveMaxConcurrentTranscodes() {
        try {
            Settings settings = settingsService.getSettingsOrNull();
            Integer configured = settings != null ? settings.getMaxConcurrentTranscodes() : null;
            if (configured != null && configured > 0) {
                return Math.max(1, configured);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read maxConcurrentTranscodes setting, falling back to auto: {}", e.getMessage());
        }
        return AUTO_MAX_CONCURRENT_TRANSCODES;
    }

    private void cleanupOldCacheFiles() {
        try {
            Path dir = getTempDir();
            long cutoff = System.currentTimeMillis() - CACHE_FILE_TTL_MS;
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().startsWith("cache-") && p.toString().endsWith(".mp4"))
                     .filter(p -> {
                         try {
                             return java.nio.file.Files.getLastModifiedTime(p).toMillis() < cutoff;
                         } catch (IOException e) {
                             return false;
                         }
                     })
                     .forEach(p -> {
                         try {
                             java.nio.file.Files.delete(p);
                             java.nio.file.Files.deleteIfExists(completedMarkerPath(p));
                             LOG.info("Deleted stale cache file: {}", p);
                         } catch (IOException e) {
                             LOG.warn("Failed to delete stale cache file {}: {}", p, e.getMessage());
                         }
                     });
            }
            // Partial cache files (no .done marker) left by aborted or abandoned
            // transcodes are garbage even when recently modified. Active transcodes
            // keep writing so their files never reach the grace cutoff.
            long partialCutoff = System.currentTimeMillis() - PARTIAL_CACHE_GRACE_MS;
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().startsWith("cache-") && p.toString().endsWith(".mp4"))
                     .filter(p -> !java.nio.file.Files.exists(completedMarkerPath(p)))
                     .filter(p -> !isCacheBeingWritten(p))
                     .filter(p -> {
                         try {
                             return java.nio.file.Files.getLastModifiedTime(p).toMillis() < partialCutoff;
                         } catch (IOException e) {
                             return false;
                         }
                     })
                     .forEach(p -> {
                         try {
                             java.nio.file.Files.delete(p);
                             LOG.info("Deleted abandoned partial cache file: {}", p);
                         } catch (IOException e) {
                             LOG.warn("Failed to delete abandoned partial cache file {}: {}", p, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            LOG.warn("Cache cleanup failed: {}", e.getMessage());
        }
    }

    /** Kills FFmpeg processes that have produced no output for TRANSCODE_STALL_TTL_MS.
     *  Backstop for abandoned transcodes whose client vanished without a socket-write
     *  failure (e.g. FFmpeg blocked reading its input while the stream loop idles). */
    private void sweepStalledProcesses() {
        long cutoff = System.currentTimeMillis() - TRANSCODE_STALL_TTL_MS;
        activeProcesses.forEach((key, proc) -> {
            if (!proc.isAlive()) return;
            Long lastOutput = processLastOutput.get(key);
            if (lastOutput != null && lastOutput < cutoff) {
                LOG.warn("Killing stalled FFmpeg process {} (no output for over {} ms)", key, TRANSCODE_STALL_TTL_MS);
                proc.destroyForcibly();
            }
        });
    }

    private boolean isHardwareAccelerationEnabled() {
        try {
            Models.Settings settings = settingsService.getOrCreateSettings();
            return settings.getHardwareAccelerationEnabled() != null ? settings.getHardwareAccelerationEnabled() : true;
        } catch (Exception e) {
            LOG.debug("Could not read hardware acceleration setting, defaulting to enabled: {}", e.getMessage());
            return true;
        }
    }

    // Only codecs that EVERY browser (Chrome/Edge/Firefox/Safari incl. iOS)
    // decodes inside MP4 are bit-copied. AC3/DTS/TrueHD/Opus are not decodable
    // in <video> on at least one major platform (desktop Chrome/Firefox lack
    // them entirely; iOS Safari lacks all four) — copying them would yield
    // silent audio. They fall into the existing !canCopyAudio branch below and
    // are transcoded to AAC 192k, which is cheap compared to a video encode.
    private static final java.util.Set<String> COPYABLE_AUDIO_CODECS = java.util.Set.of(
        "aac", "mp3", "flac"
    );

    private boolean canCopyAudio(Video video) {
        if (video.audioCodec == null) {
            return false;
        }
        String codec = video.audioCodec.toLowerCase(Locale.ROOT);
        return COPYABLE_AUDIO_CODECS.contains(codec);
    }

    private String getScaleFilter(boolean isNvidia, int qualityHeight, String resolution) {
        if (qualityHeight <= 0) return null;
        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}
        // Never upscale — if source is already below the target, no scaling needed.
        if (h > 0 && qualityHeight >= h) return null;
        double aspect = (double) w / h;
        int targetH = qualityHeight;
        int targetW = (int) Math.round(targetH * aspect);
        if (targetW % 2 != 0) targetW--;
        if (targetH % 2 != 0) targetH--;
        return isNvidia ? "scale_cuda=" + targetW + ":" + targetH : "scale=" + targetW + ":" + targetH;
    }

    private static int parseSourceHeight(String resolution) {
        if (resolution == null || !resolution.contains("x")) return 0;
        try {
            String[] parts = resolution.split("x");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Builds the scale filter string based on decoder and encoder vendor matching.
     * Uses vendor-matched zero-copy pipelines where possible, falls back to CPU.
     */
    private String buildScaleFilter(String hardwareDecoder, String videoEncoder, int qualityHeight, String resolution) {
        if (qualityHeight <= 0) return null;
        
        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}
        // Never upscale — if source is already below the target, no scaling needed.
        if (h > 0 && qualityHeight >= h) return null;
        double aspect = (double) w / h;
        int targetH = qualityHeight;
        int targetW = (int) Math.round(targetH * aspect);
        if (targetW % 2 != 0) targetW--;
        if (targetH % 2 != 0) targetH--;

        boolean decoderIsCuda = hardwareDecoder != null && hardwareDecoder.contains("cuvid");
        boolean decoderIsQsv = hardwareDecoder != null && hardwareDecoder.contains("qsv");
        boolean decoderIsVaapi = hardwareDecoder != null && hardwareDecoder.contains("vaapi");
        boolean decoderIsAmf = hardwareDecoder != null && hardwareDecoder.contains("amf");
        boolean decoderIsVideoToolbox = hardwareDecoder != null && hardwareDecoder.contains("videotoolbox");
        boolean decoderIsD3d11va = hardwareDecoder != null && hardwareDecoder.contains("d3d11va");
        boolean decoderIsDxva2 = hardwareDecoder != null && hardwareDecoder.contains("dxva2");

        boolean encoderIsNvenc = videoEncoder != null && videoEncoder.contains("nvenc");
        boolean encoderIsQsv = videoEncoder != null && videoEncoder.contains("qsv");
        boolean encoderIsVaapi = videoEncoder != null && videoEncoder.contains("vaapi");
        boolean encoderIsAmf = videoEncoder != null && videoEncoder.contains("amf");
        boolean encoderIsVideoToolbox = videoEncoder != null && videoEncoder.contains("videotoolbox");

        // Vendor-matched zero-copy pipelines
        if (decoderIsCuda && encoderIsNvenc) {
            return "scale_cuda=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH;
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH;
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH;
        } else if (decoderIsVideoToolbox && encoderIsVideoToolbox) {
            // VideoToolbox doesn't have a dedicated scale filter, use software scale
            return "scale=" + targetW + ":" + targetH;
        }

        // Cross-vendor fallback: download from GPU to CPU, then software scale
        if (decoderIsCuda) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsQsv) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsVaapi) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsAmf) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsD3d11va) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsDxva2) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Software decode path
        return "scale=" + targetW + ":" + targetH;
    }

    public boolean isIOSClient(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod");
    }

    public boolean isMacOSSafari(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return ua.contains("macintosh") && ua.contains("safari") &&
               !ua.contains("chrome") && !ua.contains("firefox") && !ua.contains("edg");
    }

    public boolean needsHEVCTag(String userAgent) {
        return isIOSClient(userAgent) || isMacOSSafari(userAgent);
    }

    public boolean isTranscodeNeededForWeb(Video video, String userAgent) {
        if (video.videoCodec == null) {
            return true;
        }
        String codec = video.videoCodec.toLowerCase(Locale.ROOT);
        // Cache key versioned ("v3") so decisions made by the previous logic
        // (v2: VP9/AV1 treated as playable on iOS) don't survive the fix
        // within a running session.
        String cacheKey = "v3|" + video.id + "|" + video.videoCodec + "|" + video.audioCodec + "|" + (userAgent != null ? userAgent.hashCode() : "null");
        
        Boolean cached = transcodeNeededCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        boolean result;
        // Audio codec is intentionally NOT part of this decision. Browsers handle
        // non-native audio (EAC3/AC3/DTS/TrueHD) via the stream path, which copies
        // the video and transcodes only the audio to AAC — a full video re-encode
        // is never required for an audio-codec mismatch. FLAC/Opus/Vorbis/PCM are
        // natively decodable by all modern browsers.
        if (codec.contains("h264") || codec.contains("avc")) {
            result = false;
        } else if (codec.contains("hevc") || codec.contains("h265")) {
            if (userAgent != null) {
                String ua = userAgent.toLowerCase(Locale.ROOT);

                if (ua.contains("windows")) {
                    // Both Chromium (Chrome 107+) and Firefox (133+) on Windows
                    // decode HEVC via the OS Microsoft HEVC Video Extension.
                    result = false;
                } else if (ua.contains("macintosh") && (ua.contains("safari") || ua.contains("chrome"))) {
                    result = false;  // macOS native VideoToolbox
                } else if (isIOSClient(userAgent)) {
                    result = false;  // iOS native VideoToolbox
                } else {
                    result = true;   // Linux/Android: HEVC support is inconsistent
                }
            } else {
                result = true;
            }
        } else if (codec.contains("vp9") || codec.contains("av1") || codec.contains("av01")) {
            // VP9/AV1 decode natively on desktop browsers, but iOS Safari has no
            // VP9/AV1 decoder — an iPhone needs a transcoded H.264 stream.
            result = isIOSClient(userAgent);
        } else {
            result = true;
        }
        
        transcodeNeededCache.put(cacheKey, result);
        return result;
    }

    public void streamRemuxedMKV(Video video, File videoFile, double startSeconds, String userAgent, OutputStream output, int audioTrackIndex, int qualityHeight) throws IOException {
        streamRemuxedMKV(video, videoFile, startSeconds, userAgent, output, audioTrackIndex, qualityHeight, null);
    }

    public void streamRemuxedMKV(Video video, File videoFile, double startSeconds, String userAgent, OutputStream output, int audioTrackIndex, int qualityHeight, java.nio.file.Path cacheFile) throws IOException {
        if (!videoFile.exists() || !videoFile.canRead()) {
            throw new IOException("Video file not found or not readable: " + videoFile.getName());
        }
        LOG.info("Starting transcoding for {} (size: {} bytes)", videoFile.getName(), videoFile.length());

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found");
        }

        if (video.videoCodec == null || video.audioCodec == null) {
            videoService.probeVideoMetadata(video);
        }

        boolean isIOS = isIOSClient(userAgent);
        boolean isMacSafari = isMacOSSafari(userAgent);
        boolean needsAppleHvc1Tag = needsHEVCTag(userAgent);
        LOG.info("Client request - iOS: {}, macOS Safari: {}, User-Agent: {}", isIOS, isMacSafari, userAgent);
        
        boolean codecNeedsTranscode = isTranscodeNeededForWeb(video, userAgent);
        int sourceHeight = parseSourceHeight(video.resolution);
        // Transcode only for codec conversion or a real downscale (quality < source).
        // A native codec at source resolution is served as a pure remux (-c copy).
        boolean needsVideoTranscode = codecNeedsTranscode
                || (qualityHeight > 0 && sourceHeight > 0 && qualityHeight < sourceHeight);

        if (needsVideoTranscode) {
            if (codecNeedsTranscode) {
                LOG.info("Transcoding forced for codec: {} to ensure web compatibility", video.videoCodec);
            } else {
                LOG.info("Transcoding forced: quality {} below source height {}", qualityHeight, sourceHeight);
            }
        }

        boolean canCopyAudio = canCopyAudio(video);

        // Permanent audio fix: when the video stream is served as a pure remux but the
        // audio must be re-encoded on EVERY request, schedule a background job that
        // remuxes the file once so future streams can -c:a copy (non-blocking here).
        if (!needsVideoTranscode && !canCopyAudio) {
            try {
                videoConversionService.startAudioRemuxIfNeeded(video.id);
            } catch (Exception e) {
                LOG.warn("Failed to schedule audio remux for {}: {}", videoFile.getName(), e.getMessage());
            }
        }

        LOG.info("[STREAM] videoId={} remux path direct={} startSeconds={}", video.id, !needsVideoTranscode && startSeconds == 0, startSeconds);

        streamViaFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, output, audioTrackIndex, qualityHeight, cacheFile);
    }

    private static final int AVERROR_EOF = -40;
    private static final int EPIPE = -32;
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 2000;

    private void streamViaFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                                  boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag, OutputStream output, int audioTrackIndex, int qualityHeight) throws IOException {
        streamViaFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, output, audioTrackIndex, qualityHeight, null);
    }

    private void streamViaFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                                  boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag, OutputStream output, int audioTrackIndex, int qualityHeight, java.nio.file.Path cacheFile) throws IOException {
        StringBuilder errorOutput = new StringBuilder();
        int exitCode =0;
        
        boolean useHardware = isHardwareAccelerationEnabled();
        
        for (int attempt =0; attempt <= MAX_RETRIES; attempt++) {
            transcodeAttemptCount.incrementAndGet();
            if (attempt > 0) {
                transcodeRetryCount.incrementAndGet();
            }
            errorOutput.setLength(0);
            
            exitCode = runFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, useHardware, output, errorOutput, audioTrackIndex, qualityHeight, cacheFile);
            
            if (exitCode == 0 || exitCode == EPIPE) {
                return;
            }
            String errors = errorOutput.toString();

            if (exitCode == 137) {
                transcodeOomCount.incrementAndGet();
                LOG.error("FFmpeg was killed (exit code 137 = SIGKILL) for {}. This is likely an Out Of Memory condition. Consider reducing concurrent transcodes or adding swap.", videoFile.getName());
                if (useHardware) {
                    LOG.warn("OOM occurred with hardware acceleration for {}, falling back to software with tighter memory limits", videoFile.getName());
                    transcodeHwFallbackCount.incrementAndGet();
                    useHardware = false;
                    continue;
                }
                break;
            }

            if (exitCode == 1 && (errors.contains("Broken pipe") || errors.contains("broken pipe"))) {
                LOG.debug("Client disconnected from {} (broken pipe)", videoFile.getName());
                return;
            }
            if (useHardware && (errors.contains("No capable devices found") || 
                                errors.contains("nvenc") || errors.contains("amf") || errors.contains("qsv") || 
                                errors.contains("vaapi") || errors.contains("videotoolbox") || errors.contains("driver") || 
                                errors.contains("Hardware acceleration failed") || errors.contains("cuvid") || 
                                errors.contains("cuda") || errors.contains("GPU") || errors.contains("signal") ||
                                errors.contains("d3d11va") || errors.contains("dxva2") || errors.contains("mf"))) {
                LOG.warn("Hardware acceleration or encoder failed, falling back to software for {}: {}", videoFile.getName(), errors.split("\n")[0]);
                
                // Invalidate the failed encoder
                if (errors.contains("h264_nvenc")) discoveryService.recordEncoderFailure("h264_nvenc");
                else if (errors.contains("hevc_nvenc")) discoveryService.recordEncoderFailure("hevc_nvenc");
                else if (errors.contains("h264_qsv")) discoveryService.recordEncoderFailure("h264_qsv");
                else if (errors.contains("hevc_qsv")) discoveryService.recordEncoderFailure("hevc_qsv");
                else if (errors.contains("h264_amf")) discoveryService.recordEncoderFailure("h264_amf");
                else if (errors.contains("hevc_amf")) discoveryService.recordEncoderFailure("hevc_amf");
                else if (errors.contains("h264_vaapi")) discoveryService.recordEncoderFailure("h264_vaapi");
                else if (errors.contains("hevc_vaapi")) discoveryService.recordEncoderFailure("hevc_vaapi");
                else if (errors.contains("h264_d3d11va")) discoveryService.recordEncoderFailure("h264_d3d11va");
                else if (errors.contains("hevc_d3d11va")) discoveryService.recordEncoderFailure("hevc_d3d11va");
                else if (errors.contains("h264_dxva2")) discoveryService.recordEncoderFailure("h264_dxva2");
                else if (errors.contains("hevc_dxva2")) discoveryService.recordEncoderFailure("hevc_dxva2");
                else if (errors.contains("h264_mf")) discoveryService.recordEncoderFailure("h264_mf");
                else if (errors.contains("hevc_mf")) discoveryService.recordEncoderFailure("hevc_mf");
                
                transcodeHwFallbackCount.incrementAndGet();
                useHardware = false;
                continue;
            }
            
            if (useHardware && exitCode !=0 && exitCode != EPIPE) {
                LOG.warn("FFmpeg exited with code {} while using hardware acceleration for {}, falling back to software", exitCode, videoFile.getName());
                transcodeHwFallbackCount.incrementAndGet();
                useHardware = false;
                continue;
            }
            
            if (attempt < MAX_RETRIES && exitCode == AVERROR_EOF) {
                transcodeEofRetryCount.incrementAndGet();
                LOG.warn("FFmpeg exited with EOF (code {}), retrying in {}ms for {}", exitCode, RETRY_DELAY_MS, videoFile.getName());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                break;
            }
        }
        
        if (exitCode != EPIPE) {
            transcodeFailureCount.incrementAndGet();
            String errorMsg = errorOutput.length() >0 ? errorOutput.toString() : "Unknown error";
            LOG.error("FFmpeg failed with exit code {} for {}. Error output: {}", exitCode, videoFile.getName(), errorMsg);
            throw new IOException("FFmpeg transcoding failed with code " + exitCode + " for " + videoFile.getName());
        }
    }

    private int runFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                          boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag,
                          boolean useHardware, OutputStream output, StringBuilder errorOutput, int audioTrackIndex, int qualityHeight) throws IOException {
        return runFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, useHardware, output, errorOutput, audioTrackIndex, qualityHeight, null);
    }

    private int runFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                          boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag,
                          boolean useHardware, OutputStream output, StringBuilder errorOutput, int audioTrackIndex, int qualityHeight, java.nio.file.Path cacheFile) throws IOException {
        // Permit is held until the process fully exits: the finally below wraps the
        // entire launch + stream (including pb.start()), so a failed launch can never
        // leak one of the pool permits and black out all streaming.
        if (!transcodePermits.tryAcquire()) {
            LOG.warn("Transcode permit pool exhausted (available: {}), request for {} queued",
                     transcodePermits.availablePermits(), videoFile.getName());
            try {
                transcodePermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for transcode permit", e);
            }
            LOG.info("Acquired transcode permit for {} after waiting (available: {})",
                     videoFile.getName(), transcodePermits.availablePermits());
        } else {
            LOG.debug("Acquired transcode permit for {} (available: {})",
                      videoFile.getName(), transcodePermits.availablePermits());
        }
        try {
            return runFFmpegWithPermit(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode,
                                       canCopyAudio, needsAppleHvc1Tag, useHardware, output, errorOutput,
                                       audioTrackIndex, qualityHeight, cacheFile);
        } finally {
            transcodePermits.release();
            LOG.debug("Released transcode permit for {} (available: {})",
                      videoFile.getName(), transcodePermits.availablePermits());
        }
    }

    private int runFFmpegWithPermit(Video video, File videoFile, String ffmpegPath, double startSeconds,
                                    boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag,
                                    boolean useHardware, OutputStream output, StringBuilder errorOutput,
                                    int audioTrackIndex, int qualityHeight, java.nio.file.Path cacheFile) throws IOException {
        String hardwareEncoder = useHardware ? discoveryService.detectHardwareEncoder() : "libx264";
        String hardwareDecoder = useHardware ? discoveryService.getHardwareDecoder(video.videoCodec) : null;
        
        boolean isHardwareEncoder = useHardware && (hardwareEncoder.startsWith("h264") || hardwareEncoder.startsWith("hevc"));
        
        String videoEncoder;
        String preset = "ultrafast";
        if (needsVideoTranscode) {
            if (isHardwareEncoder) {
                videoEncoder = hardwareEncoder;
                if (hardwareEncoder.contains("nvenc")) {
                    preset = "fast";
                } else if (hardwareEncoder.contains("amf")) {
                    preset = "speed";
                } else if (hardwareEncoder.contains("qsv") || hardwareEncoder.contains("videotoolbox")) {
                    preset = "fast";
                } else {
                    preset = "medium";
                }
            } else {
                videoEncoder = "libx264";
            }
        } else {
            videoEncoder = "copy";
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        
        // Configure hardware decoder with zero-copy output format when encoder matches vendor
        if (useHardware && hardwareDecoder != null && needsVideoTranscode) {
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
                if (videoEncoder != null && videoEncoder.contains("d3d11va")) {
                    command.add("-hwaccel_output_format"); command.add("d3d11");
                }
            } else if (hardwareDecoder.contains("dxva2")) {
                command.add("-hwaccel"); command.add("dxva2");
            }
        }

        command.add("-v"); command.add("error");
        command.add("-hide_banner");

        // Seek before -i for both copy and transcode modes so both A/V streams
        // are demuxed from the same keyframe position. With copy mode, seeking
        // after -i produces a PTS gap (video starts at the keyframe before the
        // seek point while audio starts exactly at the seek point), causing the
        // browser to freeze the first video frame while audio continues playing.
        if (startSeconds > 0) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", startSeconds));
        }
        command.add("-i"); command.add(videoFile.getAbsolutePath());

        command.add("-map"); command.add("0:v:0");
        if (audioTrackIndex >= 0) {
            // audioTrackIndex is the absolute stream index from FFprobe
            // Use -map 0:N to map the exact stream by its index
            command.add("-map"); command.add("0:" + audioTrackIndex);
            LOG.info("Mapping specific audio track by index: 0:{}", audioTrackIndex);
        } else {
            command.add("-map"); command.add("0:a:0?");
        }

        if (needsVideoTranscode) {
            LOG.info("Transcoding video for {} (source codec: {}, encoder: {})", videoFile.getName(), video.videoCodec, videoEncoder);
            command.add("-c:v"); command.add(videoEncoder);
            if (!videoEncoder.equals("copy")) {
                if (videoEncoder.contains("amf")) {
                    command.add("-preset"); command.add("speed");
                    command.add("-usage"); command.add("transcoding");
                    command.add("-quality"); command.add("quality");
                } else {
                    command.add("-preset"); command.add(preset);
                }
                
                if (videoEncoder.contains("nvenc")) {
                    command.add("-rc"); command.add("vbr");
                    command.add("-cq"); command.add("23");
                    if (needsAppleHvc1Tag && videoEncoder.contains("h264")) {
                        command.add("-profile:v"); command.add("high");
                    }
                } else if (videoEncoder.contains("amf")) {
                    // AMF uses -quality and -usage, not -rc/-cq
                } else if (videoEncoder.contains("qsv")) {
                    command.add("-global_quality"); command.add("23");
                } else if (videoEncoder.contains("videotoolbox")) {
                    command.add("-quality"); command.add("70");
                } else if (videoEncoder.contains("vaapi")) {
                    command.add("-rc_mode"); command.add("CQP");
                    command.add("-qp"); command.add("23");
                } else {
                    command.add("-crf"); command.add("23");
                }
                
                if (videoEncoder.equals("libx264")) {
                    command.add("-pix_fmt"); command.add("yuv420p");
                    command.add("-tune"); command.add("zerolatency");
                }
                if (qualityHeight > 0) {
                    String scaleFilter = buildScaleFilter(hardwareDecoder, videoEncoder, qualityHeight, video.resolution);
                    if (scaleFilter != null) {
                        command.add("-vf"); command.add(scaleFilter);
                    }
                }
                if (needsAppleHvc1Tag && videoEncoder.equals("libx264")) {
                    command.add("-profile:v"); command.add("high");
                }
            }
        } else {
            LOG.info("Remuxing video for {} (source codec: {})", videoFile.getName(), video.videoCodec);
            boolean isCopy = videoEncoder != null && videoEncoder.equals("copy");
            command.add("-c:v"); command.add(isCopy ? "copy" : "libx264");
            if (isCopy && needsAppleHvc1Tag && video.videoCodec != null && video.videoCodec.toLowerCase(Locale.ROOT).contains("hevc")) {
                command.add("-tag:v"); command.add("hvc1");
            }
        }

        // When a specific audio track is selected, transcode to AAC for web compatibility
        // (the selected track may have a different codec than the default track)
        if (needsVideoTranscode || audioTrackIndex >= 0 || !canCopyAudio) {
            LOG.info("Transcoding audio for {} (selected track, ensuring AAC)", videoFile.getName());
            command.addAll(List.of(
                "-c:a", "aac",
                "-b:a", "192k",
                "-ac", "2"
            ));
        } else {
            LOG.info("Copying audio for {} (source codec: {})", videoFile.getName(), video.audioCodec);
            command.add("-c:a"); command.add("copy");
            if (video.audioCodec != null && video.audioCodec.equalsIgnoreCase("aac")) {
                command.add("-bsf:a"); command.add("aac_adtstoasc");
            }
        }

        if (startSeconds >0) {
            command.add("-async"); command.add("1");
            command.add("-vsync"); command.add("vfr");
        }

        // Low-latency streaming: flush output immediately, skip input buffering, discard corrupt data
        command.add("-fflags"); command.add("+discardcorrupt+nobuffer+flush_packets");
        command.add("-flags"); command.add("low_delay");
        command.add("-max_delay"); command.add("0");
        command.add("-muxdelay"); command.add("0");

        String movflags = "frag_keyframe+empty_moov+default_base_moof";
        
        command.add("-sn");
        command.add("-f"); command.add("mp4");
        command.add("-movflags"); command.add(movflags);
        command.add("-g"); command.add("48");
        command.add("-avoid_negative_ts"); command.add("make_zero");
        
        command.add("-ignore_unknown");
        command.add("pipe:1");

        command.add("-max_muxing_queue_size"); command.add("1024");

        String ffmpegAudioMode = (needsVideoTranscode || audioTrackIndex >= 0 || !canCopyAudio) ? "aac" : "copy";
        LOG.info("[FFMPEG] videoId={} hw={} decoder={} encoder={} audio={} scale={}",
            video.id, useHardware, hardwareDecoder, videoEncoder, ffmpegAudioMode,
            qualityHeight > 0 ? String.valueOf(qualityHeight) : "none");

        LOG.info("FFmpeg command for {} (AppleHvc1Tag={}): {}", videoFile.getName(), needsAppleHvc1Tag, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        
        String processKey = (cacheFile != null ? cacheFile.getFileName().toString() : "live-" + video.id) + "-" + System.nanoTime();
        activeProcesses.put(processKey, process);
        processLastOutput.put(processKey, System.currentTimeMillis());
        videoProcessKeys.computeIfAbsent(video.id, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(processKey);

        Thread errorLogger = new Thread(() -> {
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    errorOutput.append(line).append("\n");
                    if (needsAppleHvc1Tag) {
                        LOG.info("FFmpeg stderr: {}", line);
                    } else {
                        LOG.debug("FFmpeg: {}", line);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error reading FFmpeg stderr for {}: {}", videoFile.getName(), e.getMessage());
            }
        });
        errorLogger.setDaemon(true);
        errorLogger.setUncaughtExceptionHandler((t, e) -> LOG.error("Uncaught exception in errorLogger thread for {}: {}", videoFile.getName(), e.getMessage()));
        errorLogger.start();

        boolean clientDisconnected = false;
        try (InputStream is = process.getInputStream()) {
            OutputStream fileOut = cacheFile != null ? Files.newOutputStream(cacheFile) : null;
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                try {
                    output.write(buffer,0, read);
                    if (fileOut != null) {
                        fileOut.write(buffer, 0, read);
                    }
                    processLastOutput.put(processKey, System.currentTimeMillis());
                } catch (IOException e) {
                    LOG.debug("Client disconnected for {}, killing ffmpeg", videoFile.getName());
                    clientDisconnected = true;
                    if (fileOut != null) {
                        try { fileOut.close(); } catch (IOException ignored) {}
                    }
                    process.destroyForcibly();
                    break;
                }
            }
            if (fileOut != null) {
                try { fileOut.close(); } catch (IOException ignored) {}
            }
        } finally {
            activeProcesses.remove(processKey);
            processLastOutput.remove(processKey);
            java.util.Set<String> keys = videoProcessKeys.get(video.id);
            if (keys != null) {
                keys.remove(processKey);
                if (keys.isEmpty()) videoProcessKeys.remove(video.id);
            }
            try {
                errorLogger.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                int code = process.waitFor();
                if (clientDisconnected) {
                    LOG.debug("Client disconnected, killed ffmpeg for {}", videoFile.getName());
                } else if (code == EPIPE) {
                    LOG.debug("FFmpeg pipe closed (client disconnected) for {}", videoFile.getName());
                } else {
                    String errors = errorOutput.toString();
                    if (!errors.isEmpty()) {
                        LOG.info("FFmpeg exited with code {} for {}. Error output: {}", code, videoFile.getName(), errors);
                    } else {
                        LOG.info("FFmpeg exited with code {} for {}", code, videoFile.getName());
                    }
                }
                // .done marker exists <=> exit code 0 => complete; anything else is a partial file.
                if (cacheFile != null) {
                    if (code == 0) {
                        writeCompletedMarker(cacheFile);
                    } else {
                        deleteCacheFile(cacheFile);
                    }
                }
                return clientDisconnected ? EPIPE : code;
            } catch (InterruptedException e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                if (cacheFile != null) {
                    deleteCacheFile(cacheFile);
                }
                return -1;
            }
        }
    }
    
    // === Temp-file transcode management for MKV streaming ===

    public static boolean isAppleClient(String userAgent) {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("ipad") || ua.contains("iphone") || ua.contains("ipod")
            || (ua.contains("safari") && ua.contains("applewebkit") && !ua.contains("chrome") && !ua.contains("android"));
    }

    private String buildTranscodeKey(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        return videoId + "|" + String.format(Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight;
    }

    private Path getTempDir() throws IOException {
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isEmpty()) {
                Path dir = Paths.get(libraryPath, "mp4");
                Files.createDirectories(dir);
                return dir;
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve video library path, falling back to temp dir: {}", e.getMessage());
        }
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jmedia-mp4");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /** Path to the completion marker file for a transcode temp file.
     *  Existence of this file means FFmpeg exited successfully (exit code 0).
     *  Absence means the file is from a crashed/partial transcode.
     */
    private static Path completedMarkerPath(Path tempFile) {
        return tempFile.resolveSibling(tempFile.getFileName().toString() + ".done");
    }

    private void writeCompletedMarker(Path cacheFile) {
        try {
            java.nio.file.Files.write(completedMarkerPath(cacheFile), new byte[0]);
        } catch (IOException e) {
            LOG.warn("Failed to write completion marker for {}: {}", cacheFile, e.getMessage());
        }
    }

    public boolean isCacheFileComplete(Path cacheFile) {
        return cacheFile != null && java.nio.file.Files.exists(completedMarkerPath(cacheFile));
    }

    public boolean isCacheBeingWritten(Path cacheFile) {
        if (cacheFile == null) return false;
        String prefix = cacheFile.getFileName().toString() + "-";
        return activeProcesses.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    public boolean deleteCacheFile(Path cacheFile) {
        if (cacheFile == null) return false;
        try {
            boolean deleted = java.nio.file.Files.deleteIfExists(cacheFile);
            java.nio.file.Files.deleteIfExists(completedMarkerPath(cacheFile));
            return deleted;
        } catch (IOException e) {
            LOG.warn("Failed to delete cache file {}: {}", cacheFile, e.getMessage());
            return false;
        }
    }

    /**
     * Blocks until the file exists and has at least neededBytes, or until a timeout or the process fails.
     * If transcodeKey params are provided, also checks whether the transcode has completed or failed
     * to fail fast instead of waiting the full timeout.
     */
    public void waitForFile(Path file, long neededBytes, Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) throws IOException {
        String key = videoId != null ? buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight) : null;
        long deadline = System.currentTimeMillis() + TRANSCODE_START_TIMEOUT_MS;
        boolean everExisted = false;
        long vanishedSince = 0;

        while (System.currentTimeMillis() < deadline) {
            if (key != null) {
                ActiveTranscode at = activeTranscodes.get(key);
                if (at != null && at.failed) {
                    throw new IOException("Transcode failed while waiting for transcode data");
                }
            }

            if (Files.exists(file)) {
                everExisted = true;
                vanishedSince = 0;
                long size = Files.size(file);
                if (size >= neededBytes) {
                    return;
                }
                if (key != null) {
                    ActiveTranscode at = activeTranscodes.get(key);
                    if (at != null && at.completed) {
                        throw new IOException("Transcode completed but file has only " + size + " bytes (needed " + neededBytes + ")");
                    }
                }
            } else if (everExisted) {
                // Writer deleted the temp file (client abort or error). Allow a short grace for a
                // software-fallback retry to re-create it, then fail fast instead of a full timeout.
                if (vanishedSince == 0) {
                    vanishedSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - vanishedSince > TRANSCODE_FILE_REMOVED_GRACE_MS) {
                    throw new IOException("Transcode temp file was removed while waiting for data");
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for transcode data", e);
            }
        }

        throw new IOException("Timeout waiting for transcode file to reach " + neededBytes + " bytes (waited " + TRANSCODE_START_TIMEOUT_MS + "ms)");
    }

    /**
     * Checks whether a transcode has finished (completed or failed).
     * Returns true if no transcode exists for this key (treated as finished).
     */
    public boolean isTranscodeFinished(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight, boolean isAppleClient) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        ActiveTranscode at = activeTranscodes.get(key);
        if (at == null) return true;
        return at.completed || at.failed;
    }

    /**
     * Checks whether a transcode has failed.
     * Returns false if no transcode exists or if it completed successfully.
     */
    public boolean isTranscodeFailed(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight, boolean isAppleClient) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        ActiveTranscode at = activeTranscodes.get(key);
        return at != null && at.failed;
    }

    /**
     * Checks whether a transcode has a discontinuity (restarted encoder).
     * Returns true if the transcode was restarted and the discontinuity flag is set.
     */
    public boolean hasTranscodeDiscontinuity(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight, boolean isAppleClient) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        ActiveTranscode at = activeTranscodes.get(key);
        if (at == null) return false;
        boolean result = at.discontinuityDetected;
        // Clear the flag after reading so subsequent requests don't signal discontinuity
        at.discontinuityDetected = false;
        return result;
    }

    /**
     * Blocks until the transcode process completes (or fails), or a timeout expires.
     * Uses the same timeout as {@link #waitForFile}.
     */
    public void waitForTranscodeCompletion(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight, boolean isAppleClient) throws IOException {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        long deadline = System.currentTimeMillis() + TRANSCODE_START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ActiveTranscode at = activeTranscodes.get(key);
            if (at == null) {
                return;
            }
            if (at.completed || at.failed) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for transcode completion", e);
            }
        }
        throw new IOException("Timeout waiting for transcode to complete (waited " + TRANSCODE_START_TIMEOUT_MS + "ms)");
    }

    public void releaseTranscode(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight, boolean isAppleClient) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        releaseTranscode(key);
    }

    /**
     * Kills any in-flight FFmpeg process for the same video other than the one
     * serving the requested session. During drag-seeks the client abandons the
     * previous ?start= stream, but its FFmpeg process can keep running until it
     * exits, holding a pool permit and exhausting the pool for the new request.
     * Latest-seek-wins: destroy the stale process so its permit is released and
     * the new transcode never queues.
     */
    public void releaseStaleTranscodesForVideo(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        java.util.Set<String> keys = videoProcessKeys.get(videoId);
        if (keys == null || keys.isEmpty()) return;
        String keepProcessPrefix = getCacheFilePath(videoId, startSeconds, audioTrackIndex, qualityHeight).getFileName().toString();
        for (String processKey : keys) {
            if (processKey.startsWith(keepProcessPrefix)) {
                continue;
            }
            Process proc = activeProcesses.get(processKey);
            if (proc != null && proc.isAlive()) {
                LOG.info("Killing superseded transcode {} for video {} (new request start={}s)", processKey, videoId, startSeconds);
                proc.destroyForcibly();
            }
        }
    }

    private void releaseTranscode(String key) {
        ActiveTranscode at = activeTranscodes.get(key);
        if (at == null) return;

        int remaining = at.refCount.decrementAndGet();
        at.lastAccessed = System.currentTimeMillis();

        if (remaining <= 0) {
            at.cleanupFuture = cleanupExecutor.schedule(() -> {
                ActiveTranscode toClean = activeTranscodes.get(key);
                if (toClean == null) return;
                if (toClean.refCount.get() > 0) return;
                // Add grace period: if failed/restarted within last 2 minutes, reschedule cleanup
                if (toClean.failed && (System.currentTimeMillis() - toClean.lastAccessed < 120000L)) {
                    LOG.debug("Transcode {} was recently failed/restarted, rescheduling cleanup.", key);
                    toClean.cleanupFuture = cleanupExecutor.schedule(() -> cleanupTranscode(toClean), TRANSCODE_IDLE_TTL_MS, TimeUnit.MILLISECONDS);
                } else {
                    cleanupTranscode(toClean);
                }
            }, TRANSCODE_IDLE_TTL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanupTranscode(ActiveTranscode at) {
        LOG.info("Cleaning up transcode for key {} (temp file: {})", at.key, at.tempFile);
        activeTranscodes.remove(at.key);
        if (at.process != null && at.process.isAlive()) {
            at.process.destroyForcibly();
        }
        Path markerPath = completedMarkerPath(at.tempFile);
        try {
            if (Files.exists(at.tempFile)) {
                Files.delete(at.tempFile);
            }
            if (Files.exists(markerPath)) {
                Files.delete(markerPath);
            }
        } catch (IOException e) {
            LOG.warn("Failed to delete temp file or marker for {}: {}", at.tempFile, e.getMessage());
        }
    }

    // === End temp-file transcode management ===

    public java.nio.file.Path getCacheFilePath(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        String key = videoId + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight;
        try {
            return getTempDir().resolve("cache-" + Math.abs(key.hashCode()) + ".mp4");
        } catch (IOException e) {
            return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "jmedia-mp4", "cache-" + Math.abs(key.hashCode()) + ".mp4");
        }
    }

    /**
     * Returns true if any FFmpeg process is currently running
     * (live streaming OR temp-file transcoding).
     * Used by AnalysisWorker to avoid CPU contention with audio analysis.
     */
    public boolean isAnyTranscodingActive() {
        return activeProcesses.values().stream().anyMatch(Process::isAlive);
    }

    /**
     * Returns the count of currently running FFmpeg processes.
     */
    public int getActiveTranscodeCount() {
        return (int) activeProcesses.values().stream().filter(Process::isAlive).count();
    }

    @PreDestroy
    public void stopAllTranscoding() {
        cacheCleanupExecutor.shutdownNow();
        activeProcesses.values().forEach(p -> {
            if (p.isAlive()) p.destroyForcibly();
        });
        activeProcesses.clear();
        processLastOutput.clear();

        activeTranscodes.values().forEach(this::cleanupTranscode);
        activeTranscodes.clear();
        cleanupExecutor.shutdownNow();
    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new java.util.HashMap<>();
        stats.put("attemptCount", transcodeAttemptCount.get());
        stats.put("failureCount", transcodeFailureCount.get());
        stats.put("oomCount", transcodeOomCount.get());
        stats.put("eofRetryCount", transcodeEofRetryCount.get());
        stats.put("hwFallbackCount", transcodeHwFallbackCount.get());
        stats.put("retryCount", transcodeRetryCount.get());
        stats.put("activeTranscodes", (long) activeTranscodes.size());
        stats.put("activeProcesses", (long) activeProcesses.values().stream().filter(Process::isAlive).count());
        return stats;
    }
}
