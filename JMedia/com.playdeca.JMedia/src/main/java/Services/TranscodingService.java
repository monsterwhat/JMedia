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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        // Modern iPads in desktop mode report as Macintosh but include Safari.
        // We also check for mobile Safari indicators.
        return ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") || 
               (ua.contains("macintosh") && ua.contains("safari") && !ua.contains("chrome") && !ua.contains("firefox"));
    }

    public boolean isTranscodeNeededForWeb(Video video) {
        if (video.videoCodec == null) {
            return true;
        }
        String codec = video.videoCodec.toLowerCase(Locale.ROOT);
        // H.264/AVC is the most compatible baseline for the web
        return !codec.contains("h264") && !codec.contains("avc");
    }

    public void streamRemuxedMKV(Video video, File videoFile, double startSeconds, String userAgent, OutputStream output) throws IOException {
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
        LOG.info("Client request - iOS: {}, User-Agent: {}", isIOS, userAgent);
        
        // Use the generalized web compatibility check
        boolean needsVideoTranscode = isTranscodeNeededForWeb(video);

        if (needsVideoTranscode) {
            LOG.info("Transcoding forced for codec: {} to ensure web compatibility", video.videoCodec);
        }

        boolean canCopyAudio = canCopyAudio(video);
        boolean needsAudioTranscode = !canCopyAudio;

        streamViaFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, isIOS, output);
    }

    private void streamViaMkvmerge(String mkvmergePath, File videoFile, OutputStream output) throws IOException {
        LOG.info("Using mkvmerge for instant remux: {}", videoFile.getName());
        
        List<String> command = new ArrayList<>();
        command.add(mkvmergePath);
        command.add("-o");
        command.add("-");
        command.add("--no-attachments");
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
                    output.write(buffer, 0, read);
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
    private static final int EPIPE = -32; // Broken pipe (client disconnected)
    private static final int MAX_RETRIES = 1;
    private static final long RETRY_DELAY_MS = 500;

    private void streamViaFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds, 
                                  boolean needsVideoTranscode, boolean canCopyAudio, boolean isIOS, OutputStream output) throws IOException {
        StringBuilder errorOutput = new StringBuilder();
        int exitCode = 0;
        
        // Re-enable hardware acceleration for iOS but use more compatible settings
        boolean useHardware = true;
        
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            errorOutput.setLength(0);
            
            exitCode = runFFmpeg(video, videoFile, ffmpegPath, startSeconds, needsVideoTranscode, canCopyAudio, isIOS, useHardware, output, errorOutput);
            
            if (exitCode == 0 || exitCode == EPIPE) {
                // Success or client disconnected (normal during skipping)
                return;
            }

            String errors = errorOutput.toString();
            if (useHardware && (errors.contains("nvenc") || errors.contains("amf") || errors.contains("qsv") || errors.contains("vaapi") || errors.contains("driver") || errors.contains("Hardware acceleration failed") || errors.contains("cuvid") || errors.contains("cuda") || errors.contains("GPU") || errors.contains("signal"))) {
                LOG.warn("Hardware acceleration or encoder failed, falling back to software for {}: {}", videoFile.getName(), errors.split("\n")[0]);
                useHardware = false;
                continue;
            }
            
            // Also fallback for any non-zero exit code when using hardware (except client disconnect)
            if (useHardware && exitCode != 0 && exitCode != EPIPE) {
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
            String errorMsg = errorOutput.length() > 0 ? errorOutput.toString() : "Unknown error";
            LOG.error("FFmpeg failed with exit code {} for {}. Error output: {}", exitCode, videoFile.getName(), errorMsg);
            throw new IOException("FFmpeg transcoding failed with code " + exitCode + " for " + videoFile.getName());
        }
    }

    private int runFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds, 
                          boolean needsVideoTranscode, boolean canCopyAudio, boolean isIOS, 
                          boolean useHardware, OutputStream output, StringBuilder errorOutput) throws IOException {
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
                } else if (hardwareEncoder.contains("amf") || hardwareEncoder.contains("qsv")) {
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
        
        // Add hardware acceleration flags BEFORE the input file if a decoder is found
        if (useHardware && hardwareDecoder != null) {
            LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hardwareDecoder, video.videoCodec);
            if (hardwareDecoder.contains("cuvid")) {
                command.add("-hwaccel");
                command.add("cuda");
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

        if (startSeconds > 0) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", startSeconds));
        }

        command.add("-i"); command.add(videoFile.getAbsolutePath());

        command.add("-map"); command.add("0:v:0");
        command.add("-map"); command.add("0:a:0?");

        if (needsVideoTranscode) {
            LOG.info("Transcoding video for {} (source codec: {}, encoder: {})", videoFile.getName(), video.videoCodec, videoEncoder);
            command.add("-c:v"); command.add(videoEncoder);
            if (!videoEncoder.equals("copy")) {
                command.add("-preset"); command.add(preset);
                
                // Encoder-specific rate control
                if (videoEncoder.contains("nvenc") || videoEncoder.contains("amf")) {
                    command.add("-rc"); command.add("vbr");
                    command.add("-cq"); command.add("23");
                    if (isIOS && videoEncoder.contains("h264") && videoEncoder.contains("nvenc")) {
                        command.add("-profile:v"); command.add("high");
                    }
                } else if (videoEncoder.contains("qsv")) {
                    command.add("-global_quality"); command.add("23");
                } else {
                    command.add("-crf"); command.add("23");
                }
                
                command.add("-pix_fmt"); command.add("yuv420p");
                if (isIOS && videoEncoder.equals("libx264")) {
                    command.add("-profile:v"); command.add("high");
                }
            }
        } else {
            LOG.info("Remuxing video for {} (source codec: {})", videoFile.getName(), video.videoCodec);
            boolean isCopy = videoEncoder != null && videoEncoder.equals("copy");
            command.add("-c:v"); command.add(isCopy ? "copy" : "libx264");
            if (isCopy && isIOS && video.videoCodec != null && video.videoCodec.toLowerCase(Locale.ROOT).contains("hevc")) {
                command.add("-tag:v"); command.add("hvc1");
            }
        }

        if (canCopyAudio) {
            LOG.info("Copying audio for {} (source codec: {})", videoFile.getName(), video.audioCodec);
            command.add("-c:a"); command.add("copy");
            if (video.audioCodec != null && video.audioCodec.equalsIgnoreCase("aac")) {
                command.add("-bsf:a"); command.add("aac_adtstoasc");
            }
        } else {
            LOG.info("Transcoding audio for {} (source codec: {})", videoFile.getName(), video.audioCodec);
            command.addAll(List.of(
                "-c:a", "aac",
                "-b:a", "192k",
                "-ac", "2"
            ));
        }

        // Use consistent movflags for 2026 - fragmented MP4 with CMAF-style options
        // For Safari compatibility, we use frag_keyframe and default_base_moof.
        // empty_moov is essential for streaming over a pipe.
        String movflags = "frag_keyframe+empty_moov+default_base_moof";
        
        command.addAll(List.of(
            "-sn", // No subtitles in the video stream (handled by VTT API)
            "-f", "mp4",
            "-movflags", movflags,
            "-g", "48", // Force 2s keyframe interval for snappy, frame-accurate seeking
            "-avoid_negative_ts", "make_zero",
            "-ignore_unknown",
            "pipe:1"
        ));

        // Log the FFmpeg command for debugging
        LOG.info("FFmpeg command for {} (iOS={}): {}", videoFile.getName(), isIOS, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        
        String processKey = video.id + "-" + System.currentTimeMillis();
        activeProcesses.put(processKey, process);

        Thread errorLogger = new Thread(() -> {
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    errorOutput.append(line).append("\n");
                    if (isIOS) {
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
                    output.write(buffer, 0, read);
                } catch (IOException e) {
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
                if (code == EPIPE) {
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
    
    @PreDestroy
    public void stopAllTranscoding() {
        activeProcesses.values().forEach(p -> {
            if (p.isAlive()) p.destroyForcibly();
        });
        activeProcesses.clear();
    }
}
