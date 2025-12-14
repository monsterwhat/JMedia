package Services;

import Models.GitHubRelease;
import Models.Settings;
import Utils.VersionComparator;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty; 
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@ApplicationScoped
public class UpdateService {
    
    @Inject
    SettingsService settingsService;
    
    @ConfigProperty(name = "github.api.url", defaultValue = "https://api.github.com")
    String githubApiUrl;
    
    @ConfigProperty(name = "github.owner", defaultValue = "monsterwhat")
    String githubOwner;
    
    @ConfigProperty(name = "github.repo", defaultValue = "JMusic")
    String githubRepo;
    
    private static final Pattern SEMANTIC_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private final Client client = ClientBuilder.newClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public UpdateInfo checkForUpdates() {
        try {
            Settings settings = settingsService.getOrCreateSettings();
            String currentVersion = settings.getCurrentVersion();
            
            // Fetch latest releases from GitHub
            List<GitHubRelease> releases = fetchReleases();
            if (releases == null || releases.isEmpty()) {
                return new UpdateInfo(false, "Unable to fetch release information", null, null);
            }
            
            // Find the latest semantic version release
            GitHubRelease latestRelease = findLatestSemanticRelease(releases);
            if (latestRelease == null) {
                return new UpdateInfo(false, "No stable releases found", null, null);
            }
            
            String latestVersion = latestRelease.getTagName();
            
            // Check if update is available
            if (VersionComparator.isNewerVersion(currentVersion, latestVersion)) {
                return new UpdateInfo(true, "Update available", latestRelease, currentVersion);
            } else {
                return new UpdateInfo(false, "You are using the latest version", null, currentVersion);
            }
            
        } catch (Exception e) {
            return new UpdateInfo(false, "Error checking for updates: " + e.getMessage(), null, null);
        }
    }
    
    public void checkForUpdatesAsync() {
        CompletableFuture.supplyAsync(this::checkForUpdates)
            .thenAccept(updateInfo -> {
                if (updateInfo.isUpdateAvailable()) {
                    // Update last check time
                    Settings settings = settingsService.getOrCreateSettings();
                    settings.setLastUpdateCheck(Instant.now().toString());
                    settingsService.save(settings);
                    
                    // Send update notification via WebSocket
                    notifyUpdateAvailable(updateInfo);
                }
            });
    }
    
    @Scheduled(cron = "0 0 0 * * ?") // Every day at midnight
    public void scheduledUpdateCheck() {
        Settings settings = settingsService.getOrCreateSettings();
        if (settings.getAutoUpdateEnabled()) {
            checkForUpdatesAsync();
        }
    }
    
    private List<GitHubRelease> fetchReleases() {
        try {
            String url = String.format("%s/repos/%s/%s/releases", githubApiUrl, githubOwner, githubRepo);
            
            List<GitHubRelease> releases = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(new GenericType<List<GitHubRelease>>() {});
            
            return releases;
        } catch (Exception e) {
            System.err.println("Error fetching releases: " + e.getMessage());
            return null;
        }
    }
    
    private GitHubRelease findLatestSemanticRelease(List<GitHubRelease> releases) {
        return releases.stream()
            .filter(release -> !release.isDraft() && !release.isPrerelease())
            .filter(release -> SEMANTIC_VERSION_PATTERN.matcher(release.getTagName()).matches())
            .max((r1, r2) -> VersionComparator.isNewerVersion(r1.getTagName(), r2.getTagName()) ? 1 : -1)
            .orElse(null);
    }
    
    private void notifyUpdateAvailable(UpdateInfo updateInfo) {
        // This will be implemented to send WebSocket notification
        // For now, we'll just log it
        System.out.println("Update available: " + updateInfo.getLatestRelease().getTagName());
    }
    
    public static class UpdateInfo {
        private final boolean updateAvailable;
        private final String message;
        private final GitHubRelease latestRelease;
        private final String currentVersion;
        
        public UpdateInfo(boolean updateAvailable, String message, GitHubRelease latestRelease, String currentVersion) {
            this.updateAvailable = updateAvailable;
            this.message = message;
            this.latestRelease = latestRelease;
            this.currentVersion = currentVersion;
        }
        
        public boolean isUpdateAvailable() { return updateAvailable; }
        public String getMessage() { return message; }
        public GitHubRelease getLatestRelease() { return latestRelease; }
        public String getCurrentVersion() { return currentVersion; }
        
        public String getFormattedReleaseDate() {
            if (latestRelease == null || latestRelease.getPublishedAt() == null) {
                return "Unknown";
            }
            
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                latestRelease.getPublishedAt(), ZoneId.systemDefault());
            return dateTime.format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        }
        
        public String getDownloadSize() {
            if (latestRelease == null || latestRelease.getAssets() == null) {
                return "Unknown";
            }
            
            // Find the .exe asset for Windows users
            Optional<GitHubRelease.Asset> exeAsset = latestRelease.getAssets().stream()
                .filter(asset -> asset.getName().endsWith(".exe"))
                .findFirst();
            
            if (exeAsset.isPresent()) {
                return formatFileSize(exeAsset.get().getSize());
            }
            
            // Fallback to .jar if no .exe found
            Optional<GitHubRelease.Asset> jarAsset = latestRelease.getAssets().stream()
                .filter(asset -> asset.getName().endsWith(".jar"))
                .findFirst();
            
            if (jarAsset.isPresent()) {
                return formatFileSize(jarAsset.get().getSize());
            }
            
            return "Unknown";
        }
        
        public String getDownloadUrl() {
            if (latestRelease == null || latestRelease.getAssets() == null) {
                return null;
            }
            
            // Prefer .exe for Windows users
            Optional<GitHubRelease.Asset> exeAsset = latestRelease.getAssets().stream()
                .filter(asset -> asset.getName().endsWith(".exe"))
                .findFirst();
            
            if (exeAsset.isPresent()) {
                return exeAsset.get().getBrowserDownloadUrl();
            }
            
            // Fallback to .jar
            Optional<GitHubRelease.Asset> jarAsset = latestRelease.getAssets().stream()
                .filter(asset -> asset.getName().endsWith(".jar"))
                .findFirst();
            
            return jarAsset.map(GitHubRelease.Asset::getBrowserDownloadUrl).orElse(null);
        }
        
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}