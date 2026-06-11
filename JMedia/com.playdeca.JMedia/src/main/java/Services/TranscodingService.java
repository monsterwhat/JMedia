package Services;

import Models.Video;
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

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    private static final long TRANSCODE_IDLE_TTL_MS = 48 * 60 * 60 * 1000L;
    private static final long TRANSCODE_START_TIMEOUT_MS = 30_000L;

    private static class ActiveTranscode {
        final String key;
        final Process process;
        final Path tempFile;
        final AtomicInteger refCount = new AtomicInteger(1);
        final StringBuilder errorOutput = new StringBuilder();
        volatile long lastAccessed = System.currentTimeMillis();
        volatile boolean completed;
        volatile boolean failed;
        ScheduledFuture<?> cleanupFuture;

        ActiveTranscode(String key, Process process, Path tempFile) {
            this.key = key;
            this.process = process;
            this.tempFile = tempFile;
        }
    }

    private final ConcurrentHashMap<String, ActiveTranscode> activeTranscodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "transcode-cleanup");
        t.setDaemon(true);
        return t;
    });

    private boolean canCopyAudio(Video video) {
        if (video.audioCodec == null) {
            return false;
        }
        String codec = video.audioCodec.toLowerCase(Locale.ROOT);
        return codec.equals("aac") || codec.equals("mp3");
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
        
        if (codec.contains("h264") || codec.contains("avc")) {
            return false;
        }
        
        if (codec.contains("hevc") || codec.contains("h265")) {
            if (userAgent != null) {
                String ua = userAgent.toLowerCase(Locale.ROOT);
                if (ua.contains("firefox") && ua.contains("windows nt 10")) {
                    LOG.info("HEVC detected - Firefox on Windows can likely play natively, using mkvmerge");
                    return false;
                }
                if (ua.contains("chrome") && ua.contains("windows")) {
                    LOG.info("HEVC detected - Chrome/Edge on Windows can likely play natively, using mkvmerge");
                    return false;
                }
                if (ua.contains("macintosh") && (ua.contains("safari") || ua.contains("chrome"))) {
                    LOG.info("HEVC detected - macOS browser can likely play natively");
                    return false;
                }
                if (isIOSClient(userAgent)) {
                    LOG.info("HEVC detected - iOS device supports HEVC natively");
                    return false;
                }
            }
            LOG.info("HEVC detected - will transcode to H.264 for web compatibility");
            return true;
        }
        
        return true;
    }

    public void streamRemuxedMKV(Video video, File videoFile, double startSeconds, String userAgent, OutputStream output, int audioTrackIndex, int qualityHeight) throws IOException {
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
        boolean skipMkvmerge = isIOS || isMacSafari;
        LOG.info("Client request - iOS: {}, macOS Safari: {}, User-Agent: {}", isIOS, isMacSafari, userAgent);
        
        boolean needsVideoTranscode = isTranscodeNeededForWeb(video, userAgent) || qualityHeight > 0;

        if (needsVideoTranscode) {
            LOG.info("Transcoding forced for codec: {} to ensure web compatibility", video.videoCodec);
        }

        boolean canCopyAudio = canCopyAudio(video);

        if (!needsVideoTranscode && startSeconds ==0 && !skipMkvmerge) {
            LOG.info("Using mkvmerge for instant remux of {}", videoFile.getName());
            String mkvmergePath = discoveryService.findMkvmerge();
            if (mkvmergePath != null) {
                streamViaMkvmerge(mkvmergePath, videoFile, output, audioTrackIndex);
                return;
            } else {
                LOG.warn("mkvmerge not found, falling back to FFmpeg for remux");
            }
        } else if (!needsVideoTranscode && startSeconds >0) {
            LOG.info("Seeking to {}s requested - using FFmpeg instead of mkvmerge for accurate seeking", startSeconds);
        }

        streamViaFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, output, audioTrackIndex, qualityHeight);
    }

    private void streamViaMkvmerge(String mkvmergePath, File videoFile, OutputStream output, int audioTrackIndex) throws IOException {
        LOG.info("Using mkvmerge for instant remux: {}", videoFile.getName());
        
        List<String> command = new ArrayList<>();
        command.add(mkvmergePath);
        command.add("-o");
        command.add("-");
        command.add("--no-attachments");
        if (audioTrackIndex >= 0) {
            command.add("--audio-tracks");
            command.add(String.valueOf(audioTrackIndex));
            LOG.info("mkvmerge: selecting audio track {}", audioTrackIndex);
        }
        command.add(videoFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        String processKey = videoFile.getName() + "-mkvmerge-" + System.currentTimeMillis();
        activeProcesses.put(processKey, process);

        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                try {
                    output.write(buffer,0, read);
                } catch (IOException e) {
                    break;
                }
            }
        } finally {
            activeProcesses.remove(processKey);
            try {
                int exitCode = process.waitFor();
                LOG.info("mkvmerge exited with code {} for {}", exitCode, videoFile.getName());
            } catch (InterruptedException e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private static final int AVERROR_EOF = -40;
    private static final int EPIPE = -32;
    private static final int MAX_RETRIES = 1;
    private static final long RETRY_DELAY_MS = 500;

    private void streamViaFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                                  boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag, OutputStream output, int audioTrackIndex, int qualityHeight) throws IOException {
        StringBuilder errorOutput = new StringBuilder();
        int exitCode =0;
        
        boolean useHardware = true;
        
        for (int attempt =0; attempt <= MAX_RETRIES; attempt++) {
            errorOutput.setLength(0);
            
            exitCode = runFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, needsAppleHvc1Tag, useHardware, output, errorOutput, audioTrackIndex, qualityHeight);
            
            if (exitCode == 0 || exitCode == EPIPE) {
                return;
            }

            String errors = errorOutput.toString();
            if (useHardware && (errors.contains("nvenc") || errors.contains("amf") || errors.contains("qsv") || errors.contains("vaapi") || errors.contains("videotoolbox") || errors.contains("driver") || errors.contains("Hardware acceleration failed") || errors.contains("cuvid") || errors.contains("cuda") || errors.contains("GPU") || errors.contains("signal"))) {
                LOG.warn("Hardware acceleration or encoder failed, falling back to software for {}: {}", videoFile.getName(), errors.split("\n")[0]);
                useHardware = false;
                continue;
            }
            
            if (useHardware && exitCode !=0 && exitCode != EPIPE) {
                LOG.warn("FFmpeg exited with code {} while using hardware acceleration for {}, falling back to software", exitCode, videoFile.getName());
                useHardware = false;
                continue;
            }
            
            if (attempt < MAX_RETRIES && exitCode == AVERROR_EOF) {
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
            String errorMsg = errorOutput.length() >0 ? errorOutput.toString() : "Unknown error";
            LOG.error("FFmpeg failed with exit code {} for {}. Error output: {}", exitCode, videoFile.getName(), errorMsg);
            throw new IOException("FFmpeg transcoding failed with code " + exitCode + " for " + videoFile.getName());
        }
    }

    private int runFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds,
                          boolean needsVideoTranscode, boolean canCopyAudio, boolean needsAppleHvc1Tag,
                          boolean useHardware, OutputStream output, StringBuilder errorOutput, int audioTrackIndex, int qualityHeight) throws IOException {
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
                } else if (hardwareEncoder.contains("amf") || hardwareEncoder.contains("qsv") || hardwareEncoder.contains("videotoolbox")) {
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
        
        if (useHardware && hardwareDecoder != null) {
            LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hardwareDecoder, video.videoCodec);
            if (hardwareDecoder.contains("cuvid")) {
                command.add("-hwaccel");
                command.add("cuda");
            } else if (hardwareDecoder.contains("videotoolbox")) {
                command.add("-hwaccel");
                command.add("videotoolbox");
            } else if (hardwareDecoder.contains("qsv")) {
                command.add("-hwaccel");
                command.add("qsv");
            } else if (hardwareDecoder.contains("vaapi")) {
                command.add("-hwaccel");
                command.add("vaapi");
            }
        }

        command.add("-v"); command.add("error");
        command.add("-hide_banner");

        // Fast keyframe-based seeking — place -ss before -i
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
                command.add("-preset"); command.add(preset);
                
                if (videoEncoder.contains("nvenc") || videoEncoder.contains("amf")) {
                    command.add("-rc"); command.add("vbr");
                    command.add("-cq"); command.add("23");
                    if (needsAppleHvc1Tag && videoEncoder.contains("h264") && videoEncoder.contains("nvenc")) {
                        command.add("-profile:v"); command.add("high");
                    }
                } else if (videoEncoder.contains("qsv")) {
                    command.add("-global_quality"); command.add("23");
                } else if (videoEncoder.contains("videotoolbox")) {
                    command.add("-quality"); command.add("70");
                } else {
                    command.add("-crf"); command.add("23");
                }
                
                command.add("-pix_fmt"); command.add("yuv420p");
                if (qualityHeight > 0) {
                    command.add("-vf"); command.add("scale=-2:" + qualityHeight + ":force_original_aspect_ratio=decrease");
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
            command.add("-fflags"); command.add("+discardcorrupt");
        }

        String movflags = "frag_keyframe+empty_moov+default_base_moof";
        
        command.add("-sn");
        command.add("-f"); command.add("mp4");
        command.add("-movflags"); command.add(movflags);
        command.add("-g"); command.add("48");
        command.add("-avoid_negative_ts"); command.add("make_zero");
        
        command.add("-ignore_unknown");
        command.add("pipe:1");

        LOG.info("FFmpeg command for {} (AppleHvc1Tag={}): {}", videoFile.getName(), needsAppleHvc1Tag, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        
        String processKey = video.id + "-" + System.currentTimeMillis();
        activeProcesses.put(processKey, process);

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

        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                try {
                    output.write(buffer,0, read);
                } catch (IOException e) {
                    LOG.debug("Client disconnected for {}, killing ffmpeg", videoFile.getName());
                    process.destroyForcibly();
                    break;
                }
            }
        } finally {
            activeProcesses.remove(processKey);
            try {
                errorLogger.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                int code = process.waitFor();
                if (code == EPIPE || !process.isAlive()) {
                    LOG.debug("FFmpeg pipe closed (client disconnected) for {}", videoFile.getName());
                } else {
                    String errors = errorOutput.toString();
                    if (!errors.isEmpty()) {
                        LOG.info("FFmpeg exited with code {} for {}. Error output: {}", code, videoFile.getName(), errors);
                    } else {
                        LOG.info("FFmpeg exited with code {} for {}", code, videoFile.getName());
                    }
                }
                return code;
            } catch (InterruptedException e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                return -1;
            }
        }
    }
    
    // === Temp-file transcode management for MKV streaming ===

    private String buildTranscodeKey(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        return videoId + "|" + String.format(Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight;
    }

    private Path getTempDir() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jmedia-transcodes");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * Returns a temp file path where ffmpeg is writing the transcode.
     * Starts a new ffmpeg process if one isn't already running for this key.
     */
    public Path getOrCreateTranscode(Video video, File videoFile, double startSeconds, String userAgent,
                                      int audioTrackIndex, int qualityHeight) throws IOException {
        String key = buildTranscodeKey(video.id, startSeconds, audioTrackIndex, qualityHeight);

        ActiveTranscode existing = activeTranscodes.get(key);
        if (existing != null) {
            if (existing.failed) {
                LOG.warn("Previous transcode for key {} failed, starting new one", key);
                cleanupTranscode(existing);
            } else {
                existing.refCount.incrementAndGet();
                existing.lastAccessed = System.currentTimeMillis();
                if (existing.cleanupFuture != null) {
                    existing.cleanupFuture.cancel(false);
                    existing.cleanupFuture = null;
                }
                LOG.info("Reusing transcode for key {} (refCount={})", key, existing.refCount.get());
                return existing.tempFile;
            }
        }

        Path tempDir = getTempDir();
        Path tempFile = tempDir.resolve(key.replace("|", "_") + ".mp4");

        // Reuse existing file from a previous session (48h TTL) instead of re-transcoding
        if (Files.exists(tempFile) && Files.size(tempFile) > 1024) {
            LOG.info("Reusing existing transcode file for key {} (size={})", key, Files.size(tempFile));
            ActiveTranscode at = new ActiveTranscode(key, null, tempFile);
            at.completed = true;
            at.refCount.set(1);
            activeTranscodes.put(key, at);
            return tempFile;
        }

        LOG.info("Starting new transcode for key {} (video={})", key, videoFile.getName());

        if (Files.exists(tempFile)) {
            Files.delete(tempFile);
        }

        if (video.videoCodec == null || video.audioCodec == null) {
            videoService.probeVideoMetadata(video);
        }

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found");
        }

        boolean needsVideoTranscode = isTranscodeNeededForWeb(video, userAgent) || qualityHeight > 0;
        boolean needsAppleHvc1Tag = needsHEVCTag(userAgent);
        boolean canCopyAudio = canCopyAudio(video);

        String hardwareEncoder = discoveryService.detectHardwareEncoder();
        String hardwareDecoder = discoveryService.getHardwareDecoder(video.videoCodec);
        boolean useHardware = hardwareEncoder != null && (hardwareEncoder.startsWith("h264") || hardwareEncoder.startsWith("hevc"));

        String videoEncoder;
        String preset = "ultrafast";
        if (needsVideoTranscode) {
            if (useHardware) {
                videoEncoder = hardwareEncoder;
                if (hardwareEncoder.contains("nvenc")) preset = "fast";
                else if (hardwareEncoder.contains("amf") || hardwareEncoder.contains("qsv") || hardwareEncoder.contains("videotoolbox")) preset = "fast";
                else preset = "medium";
            } else {
                videoEncoder = "libx264";
            }
        } else {
            videoEncoder = "copy";
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        if (useHardware && hardwareDecoder != null) {
            LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hardwareDecoder, video.videoCodec);
            if (hardwareDecoder.contains("cuvid")) { command.add("-hwaccel"); command.add("cuda"); }
            else if (hardwareDecoder.contains("videotoolbox")) { command.add("-hwaccel"); command.add("videotoolbox"); }
            else if (hardwareDecoder.contains("qsv")) { command.add("-hwaccel"); command.add("qsv"); }
            else if (hardwareDecoder.contains("vaapi")) { command.add("-hwaccel"); command.add("vaapi"); }
        }

        command.add("-v"); command.add("error");
        command.add("-hide_banner");

        if (startSeconds > 0) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", startSeconds));
        }
        command.add("-i"); command.add(videoFile.getAbsolutePath());

        command.add("-map"); command.add("0:v:0");
        if (audioTrackIndex >= 0) {
            command.add("-map"); command.add("0:" + audioTrackIndex);
        } else {
            command.add("-map"); command.add("0:a:0?");
        }

        if (needsVideoTranscode) {
            LOG.info("Transcoding video for {} (codec: {}, encoder: {})", videoFile.getName(), video.videoCodec, videoEncoder);
            command.add("-c:v"); command.add(videoEncoder);
            if (!videoEncoder.equals("copy")) {
                command.add("-preset"); command.add(preset);
                if (videoEncoder.contains("nvenc") || videoEncoder.contains("amf")) {
                    command.add("-rc"); command.add("vbr");
                    command.add("-cq"); command.add("23");
                } else if (videoEncoder.contains("qsv")) {
                    command.add("-global_quality"); command.add("23");
                } else if (videoEncoder.contains("videotoolbox")) {
                    command.add("-quality"); command.add("70");
                } else {
                    command.add("-crf"); command.add("23");
                }
                command.add("-pix_fmt"); command.add("yuv420p");
                if (qualityHeight > 0) {
                    command.add("-vf"); command.add("scale=-2:" + qualityHeight + ":force_original_aspect_ratio=decrease");
                }
                if (needsAppleHvc1Tag && videoEncoder.equals("libx264")) {
                    command.add("-profile:v"); command.add("high");
                }
            }
        } else {
            LOG.info("Remuxing video for {} (codec: {})", videoFile.getName(), video.videoCodec);
            command.add("-c:v"); command.add("copy");
            if (needsAppleHvc1Tag && video.videoCodec != null && video.videoCodec.toLowerCase(Locale.ROOT).contains("hevc")) {
                command.add("-tag:v"); command.add("hvc1");
            }
        }

        if (needsVideoTranscode || audioTrackIndex >= 0 || !canCopyAudio) {
            command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        } else {
            command.add("-c:a"); command.add("copy");
            if (video.audioCodec != null && video.audioCodec.equalsIgnoreCase("aac")) {
                command.add("-bsf:a"); command.add("aac_adtstoasc");
            }
        }

        if (startSeconds > 0) {
            command.add("-async"); command.add("1");
            command.add("-vsync"); command.add("vfr");
            command.add("-fflags"); command.add("+discardcorrupt");
        }

        command.add("-sn");
        command.add("-f"); command.add("mp4");
        command.add("-movflags"); command.add("faststart");
        command.add("-avoid_negative_ts"); command.add("make_zero");
        command.add("-ignore_unknown");
        command.add(tempFile.toAbsolutePath().toString());

        LOG.info("FFmpeg transcode command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        String processKey = key + "-" + System.currentTimeMillis();
        activeProcesses.put(processKey, process);

        ActiveTranscode at = new ActiveTranscode(key, process, tempFile);
        activeTranscodes.put(key, at);

        Thread errorLogger = new Thread(() -> {
            try (Scanner sc = new Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    at.errorOutput.append(line).append("\n");
                    LOG.debug("FFmpeg transcode: {}", line);
                }
            } catch (Exception e) {
                LOG.warn("Error reading ffmpeg stderr for {}: {}", key, e.getMessage());
            }
        }, "ffmpeg-stderr-" + key);
        errorLogger.setDaemon(true);
        errorLogger.start();

        Thread monitor = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                at.completed = true;
                activeProcesses.remove(processKey);
                if (exitCode != 0 && exitCode != EPIPE) {
                    at.failed = true;
                    LOG.error("FFmpeg transcode {} failed with code {}. Error: {}", key, exitCode, at.errorOutput);
                } else {
                    LOG.info("FFmpeg transcode {} finished with code {}", key, exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ffmpeg-monitor-" + key);
        monitor.setDaemon(true);
        monitor.start();

        return tempFile;
    }

    /**
     * Blocks until the file exists and has at least neededBytes, or until a timeout or the process fails.
     */
    public void waitForFile(Path file, long neededBytes) throws IOException {
        long deadline = System.currentTimeMillis() + TRANSCODE_START_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                long size = Files.size(file);
                if (size >= neededBytes) {
                    return;
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
    public boolean isTranscodeFinished(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        ActiveTranscode at = activeTranscodes.get(key);
        if (at == null) return true;
        return at.completed || at.failed;
    }

    /**
     * Blocks until the transcode process completes (or fails), or a timeout expires.
     * Uses the same timeout as {@link #waitForFile}.
     */
    public void waitForTranscodeCompletion(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) throws IOException {
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

    public void releaseTranscode(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        String key = buildTranscodeKey(videoId, startSeconds, audioTrackIndex, qualityHeight);
        releaseTranscode(key);
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
                cleanupTranscode(toClean);
            }, TRANSCODE_IDLE_TTL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanupTranscode(ActiveTranscode at) {
        LOG.info("Cleaning up transcode for key {} (temp file: {})", at.key, at.tempFile);
        activeTranscodes.remove(at.key);
        if (at.process != null && at.process.isAlive()) {
            at.process.destroyForcibly();
        }
        try {
            if (Files.exists(at.tempFile)) {
                Files.delete(at.tempFile);
            }
        } catch (IOException e) {
            LOG.warn("Failed to delete temp file {}: {}", at.tempFile, e.getMessage());
        }
    }

    // === End temp-file transcode management ===

    @PreDestroy
    public void stopAllTranscoding() {
        activeProcesses.values().forEach(p -> {
            if (p.isAlive()) p.destroyForcibly();
        });
        activeProcesses.clear();

        activeTranscodes.values().forEach(this::cleanupTranscode);
        activeTranscodes.clear();
        cleanupExecutor.shutdownNow();
    }
}
