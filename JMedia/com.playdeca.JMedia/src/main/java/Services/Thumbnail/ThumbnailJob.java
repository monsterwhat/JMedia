package Services.Thumbnail;

import java.time.LocalDateTime;

/**
 * Represents a thumbnail generation job in the queue
 */
public class ThumbnailJob {
    
    public Long videoId;
    public String videoPath;
    public String title;        // For movies
    public String showName;     // For TV shows
    public Integer season;      // For episodes
    public Integer episode;     // For episodes
    public String mediaType;    // "movie", "episode", "show"
    public boolean priority;    // High priority (current view) vs low priority (background)
    public LocalDateTime queuedAt;
    public int retryCount;
    
    public ThumbnailJob() {
        this.queuedAt = LocalDateTime.now();
        this.retryCount = 0;
        this.priority = false; // Default to low priority
    }
    
    public ThumbnailJob(Long videoId, String videoPath, String mediaType) {
        this();
        this.videoId = videoId;
        this.videoPath = videoPath;
        this.mediaType = mediaType;
    }
    
    public ThumbnailJob(Long videoId, String videoPath, String title, String mediaType) {
        this(videoId, videoPath, mediaType);
        this.title = title;
    }
    
    public ThumbnailJob(Long videoId, String videoPath, String showName, Integer season, Integer episode, String mediaType) {
        this(videoId, videoPath, mediaType);
        this.showName = showName;
        this.season = season;
        this.episode = episode;
    }
    
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    public boolean shouldRetry(int maxRetries) {
        return this.retryCount < maxRetries;
    }
    
    @Override
    public String toString() {
        return "ThumbnailJob{" +
                "videoId=" + videoId +
                ", mediaType='" + mediaType + '\'' +
                ", title='" + title + '\'' +
                ", showName='" + showName + '\'' +
                ", season=" + season +
                ", episode=" + episode +
                ", priority=" + priority +
                ", retryCount=" + retryCount +
                '}';
    }
}