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
        return ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod");
    }

    public boolean isIOSTranscodeNeeded(Video video) {
        if (video.videoCodec == null) {
            return true;
        }
        String codec = video.videoCodec.toLowerCase(Locale.ROOT);
        return !codec.contains("h264") && !codec.contains("avc");
    }

    public void streamRemuxedMKV(Video video, File videoFile, double startSeconds, String userAgent, OutputStream output) throws IOException {
        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found");
        }

        if (video.videoCodec == null || video.audioCodec == null) {
            videoService.probeVideoMetadata(video);
        }

        boolean isIOS = isIOSClient(userAgent);
        LOG.info("iOS client: {}, User-Agent: {}", isIOS, userAgent);
        
        boolean needsVideoTranscode = video.videoCodec == null || 
                                     (!video.videoCodec.toLowerCase().contains("h264") && 
                                      !video.videoCodec.toLowerCase().contains("avc") &&
                                      !video.videoCodec.toLowerCase().contains("hevc") &&
                                      !video.videoCodec.toLowerCase().contains("h265") &&
                                      !video.videoCodec.toLowerCase().contains("vp9") &&
                                      !video.videoCodec.toLowerCase().contains("av1"));

        if (isIOS && isIOSTranscodeNeeded(video)) {
            LOG.info("iOS client detected - forcing transcoding for codec: {}", video.videoCodec);
            needsVideoTranscode = true;
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

    private void streamViaFFmpeg(Video video, File videoFile, String ffmpegPath, double startSeconds, 
                                  boolean needsVideoTranscode, boolean canCopyAudio, boolean isIOS, OutputStream output) throws IOException {
        String hardwareEncoder = discoveryService.detectHardwareEncoder();
        
        boolean isHardwareEncoder = hardwareEncoder.startsWith("h264") || hardwareEncoder.startsWith("hevc");
        
        String videoEncoder;
        String preset = "ultrafast";
        if (needsVideoTranscode) {
            if (isHardwareEncoder) {
                videoEncoder = hardwareEncoder;
                if (hardwareEncoder.contains("nvenc")) {
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
                command.add("-crf"); command.add("23");
                command.add("-pix_fmt"); command.add("yuv420p");
                if (isIOS && (videoEncoder.equals("libx264") || videoEncoder.equals("h264_nvenc"))) {
                    command.add("-profile:v"); command.add("main");
                }
            }
        } else {
            LOG.info("Remuxing video for {} (source codec: {})", videoFile.getName(), video.videoCodec);
            command.add("-c:v"); command.add("copy");
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

        if (isIOS) {
            command.addAll(List.of(
                "-f", "mp4",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                "-avoid_negative_ts", "make_zero",
                "pipe:1"
            ));
        } else {
            command.addAll(List.of(
                "-avoid_negative_ts", "make_zero",
                "-f", "mp4",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                "pipe:1"
            ));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        
        String processKey = video.id + "-" + System.currentTimeMillis();
        activeProcesses.put(processKey, process);

        Thread errorLogger = new Thread(() -> {
            try (java.util.Scanner sc = new java.util.Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    LOG.debug("FFmpeg: {}", sc.nextLine());
                }
            } catch (Exception e) {}
        });
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
                int exitCode = process.waitFor();
                LOG.info("FFmpeg exited with code {} for {}", exitCode, videoFile.getName());
            } catch (InterruptedException e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
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
