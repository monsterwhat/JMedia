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
    private String tmdbApiKey; // TMDb API key for video metadata and artwork
    
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
    
    // Metadata enrichment during reload
    private Boolean enableMetadataEnrichment = true; // Enrich missing metadata from external APIs during reload
    private Boolean enableBpmExtraction = true; // Extract BPM using FFmpeg during reload
    
    // YouTube (yt-dlp) advanced options
    private Boolean youtubeForceIpv4 = false;
    private Boolean youtubeForceIpv6 = false;
    private String youtubeUserAgent;
    private String youtubeExtractorArgs;
    private String youtubeImpersonate;
    private YtDlpUpdateChannel youtubeUpdateChannel = YtDlpUpdateChannel.STABLE;
    private String youtubePlayerClient = ""; // android, tv, web_safari, web
    
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
    
    public enum YtDlpUpdateChannel {
        STABLE("stable"),
        NIGHTLY("nightly"),
        MASTER("master");
        
        private final String channelName;
        YtDlpUpdateChannel(String channelName) {
            this.channelName = channelName;
        }
        public String getChannelName() {
            return channelName;
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
    
    public Boolean getEnableMetadataEnrichment() {
        return enableMetadataEnrichment != null ? enableMetadataEnrichment : true;
    }
    
    public void setEnableMetadataEnrichment(Boolean enableMetadataEnrichment) {
        this.enableMetadataEnrichment = enableMetadataEnrichment;
    }
    
    public Boolean getEnableBpmExtraction() {
        return enableBpmExtraction != null ? enableBpmExtraction : true;
    }
    
    public void setEnableBpmExtraction(Boolean enableBpmExtraction) {
        this.enableBpmExtraction = enableBpmExtraction;
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
    
    // YouTube advanced options getters and setters
    public Boolean getYoutubeForceIpv4() {
        return youtubeForceIpv4 != null ? youtubeForceIpv4 : false;
    }
    
    public void setYoutubeForceIpv4(Boolean youtubeForceIpv4) {
        this.youtubeForceIpv4 = youtubeForceIpv4;
    }
    
    public Boolean getYoutubeForceIpv6() {
        return youtubeForceIpv6 != null ? youtubeForceIpv6 : false;
    }
    
    public void setYoutubeForceIpv6(Boolean youtubeForceIpv6) {
        this.youtubeForceIpv6 = youtubeForceIpv6;
    }
    
    public String getYoutubeUserAgent() {
        return youtubeUserAgent;
    }
    
    public void setYoutubeUserAgent(String youtubeUserAgent) {
        this.youtubeUserAgent = youtubeUserAgent;
    }
    
    public String getYoutubeExtractorArgs() {
        return youtubeExtractorArgs;
    }
    
    public void setYoutubeExtractorArgs(String youtubeExtractorArgs) {
        this.youtubeExtractorArgs = youtubeExtractorArgs;
    }
    
    public String getYoutubeImpersonate() {
        return youtubeImpersonate;
    }
    
    public void setYoutubeImpersonate(String youtubeImpersonate) {
        this.youtubeImpersonate = youtubeImpersonate;
    }
    
    public YtDlpUpdateChannel getYoutubeUpdateChannel() {
        return youtubeUpdateChannel != null ? youtubeUpdateChannel : YtDlpUpdateChannel.STABLE;
    }
    
    public void setYoutubeUpdateChannel(YtDlpUpdateChannel youtubeUpdateChannel) {
        this.youtubeUpdateChannel = youtubeUpdateChannel;
    }
    
    public String getYoutubePlayerClient() {
        return youtubePlayerClient;
    }
    
    public void setYoutubePlayerClient(String youtubePlayerClient) {
        this.youtubePlayerClient = youtubePlayerClient;
    }

    public String getTmdbApiKey() {
        return tmdbApiKey;
    }

    public void setTmdbApiKey(String tmdbApiKey) {
        this.tmdbApiKey = tmdbApiKey;
    }
    
    public Boolean getThumbnailPreferApi() {
        return thumbnailPreferApi != null ? thumbnailPreferApi : true;
    }
    
    public void setThumbnailPreferApi(Boolean thumbnailPreferApi) {
        this.thumbnailPreferApi = thumbnailPreferApi;
    }
      
}
