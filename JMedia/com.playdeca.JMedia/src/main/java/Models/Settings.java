package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
public class Settings extends PanacheEntity {

    private String libraryPath;
    private String videoLibraryPath;

    private Boolean firstTimeSetup = true;
    
    private Long activeProfileId;
    
    private Boolean runAsService = false;
      
    private String outputFormat = "mp3";
    private Integer downloadThreads = 4;
    private Integer searchThreads = 4;
     
    @OneToMany(cascade = CascadeType.ALL, fetch=FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "settings_id")
    private List<SettingsLog> logs = new ArrayList<>();
    
    private String currentVersion = "0.9.0";
    private String lastUpdateCheck;
    private Boolean autoUpdateEnabled = true;
    
    // Thumbnail processing settings
    private Integer thumbnailApiDelayMs = 1000;  // Delay between API calls in milliseconds
    private Integer thumbnailMaxRetries = 3;      // Maximum retry attempts for failed thumbnails
    private Integer thumbnailProcessingThreads = 2; // Number of thumbnail processing threads
    private Boolean thumbnailPreferApi = true;   // Prefer API thumbnails over local extraction
    private Boolean thumbnailRegenerateOnReload = true; // Regenerate all thumbnails during metadata reload
    
    // Cookies file path for yt-dlp on Linux
    private String cookiesFilePath;
    
    // Import source configuration
    private DownloadSource primarySource = DownloadSource.YOUTUBE;
    private DownloadSource secondarySource = DownloadSource.SPOTDL;
    private Boolean youtubeEnabled = true;
    private Boolean spotdlEnabled = true;
    
    // Retry and rate limiting configuration
    private Integer maxRetryAttempts = 3;
    private Long retryWaitTimeMs = 90000L; // 90 seconds
    private RetrySwitchStrategy switchStrategy = RetrySwitchStrategy.AFTER_FAILURES;
    private Integer switchThreshold = 3;
    
    // Smart rate limiting configuration
    private Boolean enableSmartRateLimitHandling = true;
    private Boolean fallbackOnLongWait = true;
    private Long maxAcceptableWaitTimeMs = 3600000L; // 1 hour
    
    // BPM tolerance for smart shuffle
    private Integer bpmTolerance = 10;
    private String bpmToleranceOverrides = "{}"; // JSON map: {"electronic": 5, "jazz": 15}
    
    public enum DownloadSource {
        NONE("None"),
        YOUTUBE("YouTube (yt-dlp)"), 
        SPOTDL("Spotify (SpotDL)");
        
        private final String displayName;
        DownloadSource(String displayName) { 
            this.displayName = displayName; 
        }
        public String getDisplayName() { 
            return displayName; 
        }
    }
    
    public enum RetrySwitchStrategy {
        IMMEDIATELY("Switch immediately on first failure"),
        AFTER_FAILURES("Switch after X consecutive failures"), 
        ONLY_ON_RATE_LIMIT("Switch only on rate limit"),
        SMART_ADAPTIVE("Intelligent switching based on error patterns");
        
        private final String description;
        RetrySwitchStrategy(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
    }

    // Manual getter methods for Lombok issues
    public String getVideoLibraryPath() {
        return videoLibraryPath;
    }
    
    public void setVideoLibraryPath(String videoLibraryPath) {
        this.videoLibraryPath = videoLibraryPath;
    }
    
    public String getCookiesFilePath() {
        return cookiesFilePath;
    }
    
    public DownloadSource getPrimarySource() {
        return primarySource;
    }
    
    public DownloadSource getSecondarySource() {
        return secondarySource;
    }
    
    public Boolean getYoutubeEnabled() {
        return youtubeEnabled;
    }
    
    public Boolean getSpotdlEnabled() {
        return spotdlEnabled;
    }
    
    public Integer getMaxRetryAttempts() {
        return maxRetryAttempts;
    }
    
    public Long getRetryWaitTimeMs() {
        return retryWaitTimeMs;
    }
    
    public RetrySwitchStrategy getSwitchStrategy() {
        return switchStrategy;
    }
    
    public Integer getSwitchThreshold() {
        return switchThreshold;
    }
    
    public Boolean getEnableSmartRateLimitHandling() {
        return enableSmartRateLimitHandling;
    }
    
    public Boolean getFallbackOnLongWait() {
        return fallbackOnLongWait;
    }
    
    public Long getMaxAcceptableWaitTimeMs() {
        return maxAcceptableWaitTimeMs;
    }
    
    public void setCookiesFilePath(String cookiesFilePath) {
        this.cookiesFilePath = cookiesFilePath;
    }
    
    public void setPrimarySource(DownloadSource primarySource) {
        this.primarySource = primarySource;
    }
    
    public void setSecondarySource(DownloadSource secondarySource) {
        this.secondarySource = secondarySource;
    }
    
    public void setYoutubeEnabled(Boolean youtubeEnabled) {
        this.youtubeEnabled = youtubeEnabled;
    }
    
    public void setSpotdlEnabled(Boolean spotdlEnabled) {
        this.spotdlEnabled = spotdlEnabled;
    }
    
    public void setMaxRetryAttempts(Integer maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }
    
    public void setRetryWaitTimeMs(Long retryWaitTimeMs) {
        this.retryWaitTimeMs = retryWaitTimeMs;
    }
    
    public void setSwitchStrategy(RetrySwitchStrategy switchStrategy) {
        this.switchStrategy = switchStrategy;
    }
    
    public void setSwitchThreshold(Integer switchThreshold) {
        this.switchThreshold = switchThreshold;
    }
    
    public void setEnableSmartRateLimitHandling(Boolean enableSmartRateLimitHandling) {
        this.enableSmartRateLimitHandling = enableSmartRateLimitHandling;
    }
    
    public void setFallbackOnLongWait(Boolean fallbackOnLongWait) {
        this.fallbackOnLongWait = fallbackOnLongWait;
    }
    
    public void setMaxAcceptableWaitTimeMs(Long maxAcceptableWaitTimeMs) {
        this.maxAcceptableWaitTimeMs = maxAcceptableWaitTimeMs;
    }
    
    public Integer getBpmTolerance() {
        return bpmTolerance;
    }
    
    public void setBpmTolerance(Integer bpmTolerance) {
        this.bpmTolerance = bpmTolerance;
    }
    
    public String getBpmToleranceOverrides() {
        return bpmToleranceOverrides;
    }
    
    public void setBpmToleranceOverrides(String bpmToleranceOverrides) {
        this.bpmToleranceOverrides = bpmToleranceOverrides;
    }
    
    public Integer getBpmToleranceForGenre(String genre) {
        if (genre == null || genre.isBlank()) {
            return bpmTolerance != null ? bpmTolerance : 10;
        }
        
        String genreLower = genre.toLowerCase();
        
        if (bpmToleranceOverrides != null && !bpmToleranceOverrides.isEmpty() && !bpmToleranceOverrides.equals("{}")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Integer> overrides = mapper.readValue(bpmToleranceOverrides, 
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Integer>>() {});
                
                // Check for exact match first
                if (overrides.containsKey(genreLower)) {
                    return overrides.get(genreLower);
                }
                
                // Check for partial match (e.g., "techno" matches "electronic")
                for (java.util.Map.Entry<String, Integer> entry : overrides.entrySet()) {
                    if (genreLower.contains(entry.getKey()) || entry.getKey().contains(genreLower)) {
                        return entry.getValue();
                    }
                }
            } catch (Exception e) {
                // If JSON parsing fails, fall back to default
            }
        }
        
        return bpmTolerance != null ? bpmTolerance : 10;
    }
     
}
