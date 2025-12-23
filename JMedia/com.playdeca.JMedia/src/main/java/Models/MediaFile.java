package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class MediaFile extends PanacheEntity {

    public String path;
    public long size;
    public long lastModified;
    public String type; // "video" or "subtitle"
    
    // Basic video metadata
    public int durationSeconds;
    public int width;
    public int height;
    
    // Enhanced technical metadata for smart detection
    public String videoCodec;
    public String audioCodec;
    public String containerFormat;
    public int bitrate;
    public float frameRate;
    public String pixelFormat;
    public int audioChannels;
    public String audioLanguage;
    public String subtitleLanguages;
    public String aspectRatio;
    
    // Detection-related fields
    public boolean hasMultipleAudioTracks;
    public boolean hasEmbeddedSubtitles;
    public String releaseGroup;
    public String source; // WEB-DL, BluRay, etc.
    
    // Utility methods for smart naming
    public boolean isHighQuality() {
        return (width >= 1920 && height >= 1080) || bitrate >= 5000000;
    }
    
    public boolean isTypicalMovieDuration() {
        return durationSeconds >= 70 * 60 && durationSeconds <= 240 * 60; // 70-240 minutes
    }
    
    public boolean isTypicalEpisodeDuration() {
        return durationSeconds >= 18 * 60 && durationSeconds <= 65 * 60; // 18-65 minutes
    }
    
    public String getQualityIndicator() {
        if (width >= 3840) return "4K";
        if (width >= 2560) return "2K";
        if (width >= 1920) return "1080p";
        if (width >= 1280) return "720p";
        return "SD";
    }
    
    public String getResolutionString() {
        return width + "x" + height;
    }
    
    public boolean isWidescreen() {
        if (aspectRatio != null) {
            try {
                String[] parts = aspectRatio.split(":");
                if (parts.length == 2) {
                    float ratio = Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
                    return ratio > 1.5;
                }
            } catch (Exception e) {
                // Fall back to calculation
            }
        }
        return (width * 9) / (height * 16.0f) >= 0.9; // Allow some tolerance
    }
}
