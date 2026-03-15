package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class FFmpegDiscoveryService {

    private String ffmpegPath;
    private String ffprobePath;
    private String mkvmergePath;
    private String hardwareEncoder;

    public synchronized String findFFmpegExecutable() {
        if (ffmpegPath != null) {
            return ffmpegPath;
        }

        String[] paths = {
            "ffmpeg", 
            "ffmpeg.exe", 
            "C:\\ffmpeg\\bin\\ffmpeg.exe", 
            "C:\\Program Files\\FFmpeg\\bin\\ffmpeg.exe",
            "/usr/bin/ffmpeg", 
            "/usr/local/bin/ffmpeg"
        };

        for (String p : paths) {
            try {
                if (new ProcessBuilder(p, "-version").start().waitFor() == 0) {
                    ffmpegPath = p;
                    return p;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public synchronized String findFFprobeExecutable() {
        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) return null;
        
        if (ffmpeg.endsWith(".exe")) {
            String probe = ffmpeg.replace("ffmpeg.exe", "ffprobe.exe");
            if (new File(probe).exists()) return probe;
        } else {
            String probe = ffmpeg.replace("ffmpeg", "ffprobe");
            if (new File(probe).exists()) return probe;
        }
        
        // Fallback search
        String[] paths = {
            "ffprobe", 
            "ffprobe.exe", 
            "C:\\ffmpeg\\bin\\ffprobe.exe", 
            "C:\\Program Files\\FFmpeg\\bin\\ffprobe.exe",
            "/usr/bin/ffprobe", 
            "/usr/local/bin/ffprobe"
        };
        for (String p : paths) {
            try {
                if (new ProcessBuilder(p, "-version").start().waitFor() == 0) return p;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public synchronized String findMkvmerge() {
        if (mkvmergePath != null) {
            return mkvmergePath;
        }

        String[] paths = {
            "mkvmerge",
            "mkvmerge.exe",
            "C:\\mkvtoolnix\\mkvmerge.exe",
            "C:\\Program Files\\MKVToolNix\\mkvmerge.exe",
            "/usr/bin/mkvmerge",
            "/usr/local/bin/mkvmerge"
        };

        for (String p : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(p, "--version");
                Process process = pb.start();
                if (process.waitFor() == 0) {
                    mkvmergePath = p;
                    return p;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public synchronized String detectHardwareEncoder() {
        if (hardwareEncoder != null) {
            return hardwareEncoder;
        }

        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) {
            hardwareEncoder = "libx264";
            return hardwareEncoder;
        }

        List<String> priorityEncoders = List.of(
            "h264_nvenc",
            "hevc_nvenc",
            "h264_qsv",
            "hevc_qsv",
            "h264_vaapi",
            "hevc_vaapi"
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-encoders");
            Process process = pb.start();
            
            String output = new String(process.getInputStream().readAllBytes());
            String errorOutput = new String(process.getErrorStream().readAllBytes());
            process.waitFor();
            
            String allOutput = output + errorOutput;
            
            for (String encoder : priorityEncoders) {
                if (allOutput.contains(encoder)) {
                    hardwareEncoder = encoder;
                    return hardwareEncoder;
                }
            }
        } catch (IOException | InterruptedException e) {
            // Fall through to default
        }

        hardwareEncoder = "libx264";
        return hardwareEncoder;
    }
}
