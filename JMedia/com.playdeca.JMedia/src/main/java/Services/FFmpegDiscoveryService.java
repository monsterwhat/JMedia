package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.File;

@ApplicationScoped
public class FFmpegDiscoveryService {

    private String ffmpegPath;

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
}
