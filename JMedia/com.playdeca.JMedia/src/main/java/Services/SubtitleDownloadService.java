package Services;

import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SubtitleDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleDownloadService.class);
    
    @Inject
    SettingsService settingsService;

    public CompletableFuture<String> downloadSubtitle(Video video, String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // In a real implementation, this would fetch from OpenSubtitles or similar.
                // For now, we'll try to use yt-dlp if the video was downloaded from a supported source
                // or just log that we can't download yet.
                
                LOG.info("Attempting to download subtitle for: " + video.title + " in " + language);
                
                // Placeholder: Check if yt-dlp can fetch subs
                // ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--write-subs", "--sub-lang", language, "--skip-download", video.url);
                
                Thread.sleep(1000); // Simulate network call
                
                return "Subtitle download feature not yet fully implemented. Please use Whisper generation.";
                
            } catch (Exception e) {
                LOG.error("Error downloading subtitle", e);
                throw new RuntimeException("Download failed: " + e.getMessage());
            }
        });
    }
}
