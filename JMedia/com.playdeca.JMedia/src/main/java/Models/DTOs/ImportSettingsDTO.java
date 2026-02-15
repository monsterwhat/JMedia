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
}
