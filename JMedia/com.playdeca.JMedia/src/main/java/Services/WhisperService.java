package Services;

import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WhisperService {

    private static final Logger LOG = LoggerFactory.getLogger(WhisperService.class);
    
    @Inject
    SettingsService settingsService;

    public boolean isWhisperAvailable() {
        try {
            Process process = new ProcessBuilder("whisper", "--help").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public CompletableFuture<String> generateSubtitle(Video video) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path videoPath = Paths.get(video.path);
                Path outputDir = videoPath.getParent();
                
                LOG.info("Starting Whisper generation for: " + video.filename);
                
                // Command: whisper "video.mp4" --model medium --output_format srt --output_dir "/path/to/video"
                ProcessBuilder pb = new ProcessBuilder(
                    "whisper",
                    videoPath.toString(),
                    "--model", "medium",
                    "--output_format", "srt",
                    "--output_dir", outputDir.toString(),
                    "--language", "English" // Default to English for now
                );
                
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Capture output
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOG.debug("Whisper: " + line);
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    LOG.info("Whisper generation completed for: " + video.filename);
                    return "Success";
                } else {
                    LOG.error("Whisper failed with exit code: " + exitCode);
                    throw new RuntimeException("Whisper process failed");
                }
                
            } catch (Exception e) {
                LOG.error("Error generating subtitle with Whisper", e);
                throw new RuntimeException("Error generating subtitle: " + e.getMessage());
            }
        });
    }
}
