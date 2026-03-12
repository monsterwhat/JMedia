package Services;

import Models.Video;
import Models.SubtitleTrack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WhisperService {

    private static final Logger LOG = LoggerFactory.getLogger(WhisperService.class);
    
    @Inject
    SettingsService settingsService;
    
    @Inject
    SubtitleDownloadService subtitleDownloadService;

    @Inject
    Services.Platform.PlatformOperationsFactory platformOperationsFactory;

    public boolean isWhisperAvailable() {
        try {
            return platformOperationsFactory.getPlatformOperations().isWhisperInstalled();
        } catch (Exception e) {
            LOG.error("Error checking Whisper availability", e);
            return false;
        }
    }

    public CompletableFuture<String> generateSubtitle(Video video) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path videoPath = Paths.get(video.path);
                Path outputDir = videoPath.getParent();
                
                LOG.info("Starting Whisper generation for: " + video.filename);
                
                Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
                String whisperCmd = platformOps.getWhisperCommand();
                
                // Build the base command
                java.util.List<String> command = new java.util.ArrayList<>();
                
                // Check if we need to run via python module
                if ("whisper".equals(whisperCmd) && !isDirectCommandAvailable("whisper")) {
                    String pythonExec = platformOps.findPythonExecutable();
                    command.add(pythonExec);
                    command.add("-m");
                    command.add("whisper");
                } else {
                    command.add(whisperCmd);
                }
                
                // Add common arguments
                command.add(videoPath.toString());
                command.add("--model");
                command.add("medium");
                command.add("--output_format");
                command.add("srt");
                command.add("--output_dir");
                command.add(outputDir.toString());
                command.add("--language");
                command.add("English");
                
                ProcessBuilder pb = new ProcessBuilder(command);
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
                    
                    // Refresh tracks so the new .srt file is detected
                    subtitleDownloadService.refreshSubtitleTracks(video);
                    
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

    private boolean isDirectCommandAvailable(String command) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String checkCmd = os.contains("win") ? "where " + command : "which " + command;
            Process process = Runtime.getRuntime().exec(checkCmd);
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
