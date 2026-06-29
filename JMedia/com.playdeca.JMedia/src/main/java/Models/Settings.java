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

    @Column(columnDefinition = "TEXT")
    private String activeProfileIdsJson; // JSON map: {"userId1": profileId1, "userId2": profileId2}
    
    private Boolean runAsService = false;
      
    private String outputFormat = "mp3";
    private Integer downloadThreads = 4;
    private Integer searchThreads = 4;
     
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
    private String omdbApiKey; // OMDb API key for additional ratings and details
    private String openSubtitlesApiKey; // OpenSubtitles.org API key
    
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
    
    // DJ Mode settings for seamless BPM-matched transitions
    private Boolean djModeEnabled = false;
    private Integer djModeSections = 12; // Divide song into 12 sections, trigger at last section (default)
    private Integer djModeTriggerPercent = 15; // Alternative: trigger when X% of song remains (default 15%)
    private Integer djModeBpmTolerance = 5; // Tighter BPM tolerance when DJ mode triggers
    private Integer djModeCrossfadeSeconds = 8; // Longer crossfade during DJ mode
    
    // Metadata enrichment during reload
    private Boolean enableMetadataEnrichment = true; // Enrich missing metadata from external APIs during reload
    private Boolean enableBpmExtraction = true; // Extract BPM using FFmpeg during reload
    
    // Video auto-skip settings
    private Boolean autoSkipIntro = false;
    private Boolean autoSkipRecap = false;
    private Boolean autoSkipOutro = false;

    // Sync configuration
    private Boolean syncEnabled = false;
    private String syncSchedule = "0 0 3 * * ?"; // Daily at 3 AM
    private String syncApiKey; // Incoming API key for remote servers to authenticate
    private Boolean syncMusicEnabled = true;
    private Boolean syncVideoEnabled = false;
    private Boolean syncTimelinesEnabled = false;
    private Boolean syncPlaylistsEnabled = false;

    private String defaultPlayer = "oplayer";

    // Streaming / transcoding configuration
    private Integer maxConcurrentTranscodes = 2; // Max concurrent transcode sessions (0 = use default)
    private Boolean hardwareAccelerationEnabled = true; // Enable hardware-accelerated transcoding

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
    
    // DJ Mode getters and setters
    public Boolean getDjModeEnabled() {
        return djModeEnabled != null ? djModeEnabled : false;
    }
    
    public void setDjModeEnabled(Boolean djModeEnabled) {
        this.djModeEnabled = djModeEnabled;
    }
    
    public Integer getDjModeSections() {
        return djModeSections != null ? djModeSections : 12;
    }
    
    public void setDjModeSections(Integer djModeSections) {
        this.djModeSections = djModeSections;
    }
    
    public Integer getDjModeTriggerPercent() {
        return djModeTriggerPercent != null ? djModeTriggerPercent : 15;
    }
    
    public void setDjModeTriggerPercent(Integer djModeTriggerPercent) {
        this.djModeTriggerPercent = djModeTriggerPercent;
    }
    
    public Integer getDjModeBpmTolerance() {
        return djModeBpmTolerance != null ? djModeBpmTolerance : 5;
    }
    
    public void setDjModeBpmTolerance(Integer djModeBpmTolerance) {
        this.djModeBpmTolerance = djModeBpmTolerance;
    }
    
    public Integer getDjModeCrossfadeSeconds() {
        return djModeCrossfadeSeconds != null ? djModeCrossfadeSeconds : 8;
    }
    
    public void setDjModeCrossfadeSeconds(Integer djModeCrossfadeSeconds) {
        this.djModeCrossfadeSeconds = djModeCrossfadeSeconds;
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
    
    // Video auto-skip getters and setters
    public Boolean getAutoSkipIntro() {
        return autoSkipIntro != null ? autoSkipIntro : false;
    }

    public void setAutoSkipIntro(Boolean autoSkipIntro) {
        this.autoSkipIntro = autoSkipIntro;
    }

    public Boolean getAutoSkipRecap() {
        return autoSkipRecap != null ? autoSkipRecap : false;
    }

    public void setAutoSkipRecap(Boolean autoSkipRecap) {
        this.autoSkipRecap = autoSkipRecap;
    }

    public Boolean getAutoSkipOutro() {
        return autoSkipOutro != null ? autoSkipOutro : false;
    }

    public void setAutoSkipOutro(Boolean autoSkipOutro) {
        this.autoSkipOutro = autoSkipOutro;
    }

    public String getDefaultPlayer() {
        return defaultPlayer != null ? defaultPlayer : "simple";
    }

    public void setDefaultPlayer(String defaultPlayer) {
        this.defaultPlayer = defaultPlayer;
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

    // Sync getters and setters
    public Boolean getSyncEnabled() {
        return syncEnabled != null ? syncEnabled : false;
    }

    public void setSyncEnabled(Boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getSyncSchedule() {
        return syncSchedule;
    }

    public void setSyncSchedule(String syncSchedule) {
        this.syncSchedule = syncSchedule;
    }

    public Boolean getSyncMusicEnabled() {
        return syncMusicEnabled != null ? syncMusicEnabled : true;
    }

    public void setSyncMusicEnabled(Boolean syncMusicEnabled) {
        this.syncMusicEnabled = syncMusicEnabled;
    }

    public Boolean getSyncVideoEnabled() {
        return syncVideoEnabled != null ? syncVideoEnabled : false;
    }

    public void setSyncVideoEnabled(Boolean syncVideoEnabled) {
        this.syncVideoEnabled = syncVideoEnabled;
    }

    public Boolean getSyncTimelinesEnabled() {
        return syncTimelinesEnabled != null ? syncTimelinesEnabled : false;
    }

    public void setSyncTimelinesEnabled(Boolean syncTimelinesEnabled) {
        this.syncTimelinesEnabled = syncTimelinesEnabled;
    }

    public Boolean getSyncPlaylistsEnabled() {
        return syncPlaylistsEnabled != null ? syncPlaylistsEnabled : false;
    }

    public void setSyncPlaylistsEnabled(Boolean syncPlaylistsEnabled) {
        this.syncPlaylistsEnabled = syncPlaylistsEnabled;
    }

    public String getSyncApiKey() {
        return syncApiKey;
    }

    public void setSyncApiKey(String syncApiKey) {
        this.syncApiKey = syncApiKey;
    }

    public String getTmdbApiKey() {
        return tmdbApiKey;
    }

    public void setTmdbApiKey(String tmdbApiKey) {
        this.tmdbApiKey = tmdbApiKey;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getLastUpdateCheck() {
        return lastUpdateCheck;
    }

    public void setLastUpdateCheck(String lastUpdateCheck) {
        this.lastUpdateCheck = lastUpdateCheck;
    }
    
    public Boolean getAutoUpdateEnabled() {
        return autoUpdateEnabled != null ? autoUpdateEnabled : true;
    }

    public void setAutoUpdateEnabled(Boolean autoUpdateEnabled) {
        this.autoUpdateEnabled = autoUpdateEnabled;
    }
    
    public Boolean getThumbnailPreferApi() {
        return thumbnailPreferApi != null ? thumbnailPreferApi : true;
    }
    
    public void setThumbnailPreferApi(Boolean thumbnailPreferApi) {
        this.thumbnailPreferApi = thumbnailPreferApi;
    }
      
}
