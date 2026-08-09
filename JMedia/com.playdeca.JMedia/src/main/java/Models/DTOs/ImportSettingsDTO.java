package Models.DTOs;

import lombok.Data;
import Models.Settings;

@Data
public class ImportSettingsDTO {
    private String outputFormat;
    private int downloadThreads;
    private int searchThreads;
    
    // Advanced import source configuration
    private Settings.DownloadSource primarySource;
    private Settings.DownloadSource secondarySource;
    private boolean youtubeEnabled;
    private boolean spotdlEnabled;
    
    // Retry and rate limiting configuration
    private int maxRetryAttempts;
    private long retryWaitTimeMs;
    private Settings.RetrySwitchStrategy switchStrategy;
    private int switchThreshold;
    
    // Smart rate limiting configuration
    private boolean enableSmartRateLimitHandling;
    private boolean fallbackOnLongWait;
    private long maxAcceptableWaitTimeMs;
    
    // YouTube (yt-dlp) advanced options
    private boolean youtubeForceIpv4;
    private boolean youtubeForceIpv6;
    private String youtubeUserAgent;
    private String youtubeExtractorArgs;
    private String youtubeImpersonate;
    private Settings.YtDlpUpdateChannel youtubeUpdateChannel;
    private String youtubePlayerClient;
    private String tmdbApiKey;

    // Metadata source toggle fields
    private Boolean tmdbEnabled;
    private Boolean omdbEnabled;
    private Boolean imdbDevEnabled;
    private Boolean tvmazeEnabled;
    private Boolean introDbEnabled;
    private Boolean musicBrainzEnabled;
    private Boolean acousticBrainzEnabled;
    private Boolean deezerEnabled;
    private Boolean theAudioDbEnabled;
    private Boolean openSubtitlesEnabled;
    private String omdbApiKey;
    private String openSubtitlesApiKey;
    private String openSubtitlesUsername;
    private String openSubtitlesPassword;
    
    // Getters and setters for Lombok issues
    public Settings.DownloadSource getPrimarySource() { return primarySource; }
    public void setPrimarySource(Settings.DownloadSource primarySource) { this.primarySource = primarySource; }
    
    public Settings.DownloadSource getSecondarySource() { return secondarySource; }
    public void setSecondarySource(Settings.DownloadSource secondarySource) { this.secondarySource = secondarySource; }
    
    public boolean isYoutubeEnabled() { return youtubeEnabled; }
    public void setYoutubeEnabled(boolean youtubeEnabled) { this.youtubeEnabled = youtubeEnabled; }
    
    public boolean isSpotdlEnabled() { return spotdlEnabled; }
    public void setSpotdlEnabled(boolean spotdlEnabled) { this.spotdlEnabled = spotdlEnabled; }
    
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
    
    public long getRetryWaitTimeMs() { return retryWaitTimeMs; }
    public void setRetryWaitTimeMs(long retryWaitTimeMs) { this.retryWaitTimeMs = retryWaitTimeMs; }
    
    public Settings.RetrySwitchStrategy getSwitchStrategy() { return switchStrategy; }
    public void setSwitchStrategy(Settings.RetrySwitchStrategy switchStrategy) { this.switchStrategy = switchStrategy; }
    
    public int getSwitchThreshold() { return switchThreshold; }
    public void setSwitchThreshold(int switchThreshold) { this.switchThreshold = switchThreshold; }
    
    public boolean isEnableSmartRateLimitHandling() { return enableSmartRateLimitHandling; }
    public void setEnableSmartRateLimitHandling(boolean enableSmartRateLimitHandling) { this.enableSmartRateLimitHandling = enableSmartRateLimitHandling; }
    
    public boolean isFallbackOnLongWait() { return fallbackOnLongWait; }
    public void setFallbackOnLongWait(boolean fallbackOnLongWait) { this.fallbackOnLongWait = fallbackOnLongWait; }
    
    public long getMaxAcceptableWaitTimeMs() { return maxAcceptableWaitTimeMs; }
    public void setMaxAcceptableWaitTimeMs(long maxAcceptableWaitTimeMs) { this.maxAcceptableWaitTimeMs = maxAcceptableWaitTimeMs; }
    
    // Existing getters/setters compatibility
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    
    public int getDownloadThreads() { return downloadThreads; }
    public void setDownloadThreads(int downloadThreads) { this.downloadThreads = downloadThreads; }
    
    public int getSearchThreads() { return searchThreads; }
    public void setSearchThreads(int searchThreads) { this.searchThreads = searchThreads; }
    
    // YouTube advanced options getters and setters
    public boolean isYoutubeForceIpv4() { return youtubeForceIpv4; }
    public void setYoutubeForceIpv4(boolean youtubeForceIpv4) { this.youtubeForceIpv4 = youtubeForceIpv4; }
    
    public boolean isYoutubeForceIpv6() { return youtubeForceIpv6; }
    public void setYoutubeForceIpv6(boolean youtubeForceIpv6) { this.youtubeForceIpv6 = youtubeForceIpv6; }
    
    public String getYoutubeUserAgent() { return youtubeUserAgent; }
    public void setYoutubeUserAgent(String youtubeUserAgent) { this.youtubeUserAgent = youtubeUserAgent; }
    
    public String getYoutubeExtractorArgs() { return youtubeExtractorArgs; }
    public void setYoutubeExtractorArgs(String youtubeExtractorArgs) { this.youtubeExtractorArgs = youtubeExtractorArgs; }
    
    public String getYoutubeImpersonate() { return youtubeImpersonate; }
    public void setYoutubeImpersonate(String youtubeImpersonate) { this.youtubeImpersonate = youtubeImpersonate; }
    
    public Settings.YtDlpUpdateChannel getYoutubeUpdateChannel() { return youtubeUpdateChannel; }
    public void setYoutubeUpdateChannel(Settings.YtDlpUpdateChannel youtubeUpdateChannel) { this.youtubeUpdateChannel = youtubeUpdateChannel; }
    
    public String getYoutubePlayerClient() { return youtubePlayerClient; }
    public void setYoutubePlayerClient(String youtubePlayerClient) { this.youtubePlayerClient = youtubePlayerClient; }

    public String getTmdbApiKey() { return tmdbApiKey; }
    public void setTmdbApiKey(String tmdbApiKey) { this.tmdbApiKey = tmdbApiKey; }

    // Metadata source toggle getters and setters
    public Boolean getTmdbEnabled() { return tmdbEnabled; }
    public void setTmdbEnabled(Boolean tmdbEnabled) { this.tmdbEnabled = tmdbEnabled; }
    
    public Boolean getOmdbEnabled() { return omdbEnabled; }
    public void setOmdbEnabled(Boolean omdbEnabled) { this.omdbEnabled = omdbEnabled; }
    
    public Boolean getImdbDevEnabled() { return imdbDevEnabled; }
    public void setImdbDevEnabled(Boolean imdbDevEnabled) { this.imdbDevEnabled = imdbDevEnabled; }
    
    public Boolean getTvmazeEnabled() { return tvmazeEnabled; }
    public void setTvmazeEnabled(Boolean tvmazeEnabled) { this.tvmazeEnabled = tvmazeEnabled; }
    
    public Boolean getIntroDbEnabled() { return introDbEnabled; }
    public void setIntroDbEnabled(Boolean introDbEnabled) { this.introDbEnabled = introDbEnabled; }
    
    public Boolean getMusicBrainzEnabled() { return musicBrainzEnabled; }
    public void setMusicBrainzEnabled(Boolean musicBrainzEnabled) { this.musicBrainzEnabled = musicBrainzEnabled; }
    
    public Boolean getAcousticBrainzEnabled() { return acousticBrainzEnabled; }
    public void setAcousticBrainzEnabled(Boolean acousticBrainzEnabled) { this.acousticBrainzEnabled = acousticBrainzEnabled; }
    
    public Boolean getDeezerEnabled() { return deezerEnabled; }
    public void setDeezerEnabled(Boolean deezerEnabled) { this.deezerEnabled = deezerEnabled; }
    
    public Boolean getTheAudioDbEnabled() { return theAudioDbEnabled; }
    public void setTheAudioDbEnabled(Boolean theAudioDbEnabled) { this.theAudioDbEnabled = theAudioDbEnabled; }
    
    public Boolean getOpenSubtitlesEnabled() { return openSubtitlesEnabled; }
    public void setOpenSubtitlesEnabled(Boolean openSubtitlesEnabled) { this.openSubtitlesEnabled = openSubtitlesEnabled; }
    
    public String getOmdbApiKey() { return omdbApiKey; }
    public void setOmdbApiKey(String omdbApiKey) { this.omdbApiKey = omdbApiKey; }
    
    public String getOpenSubtitlesApiKey() { return openSubtitlesApiKey; }
    public void setOpenSubtitlesApiKey(String openSubtitlesApiKey) { this.openSubtitlesApiKey = openSubtitlesApiKey; }

    public String getOpenSubtitlesUsername() { return openSubtitlesUsername; }
    public void setOpenSubtitlesUsername(String openSubtitlesUsername) { this.openSubtitlesUsername = openSubtitlesUsername; }

    public String getOpenSubtitlesPassword() { return openSubtitlesPassword; }
    public void setOpenSubtitlesPassword(String openSubtitlesPassword) { this.openSubtitlesPassword = openSubtitlesPassword; }
}
