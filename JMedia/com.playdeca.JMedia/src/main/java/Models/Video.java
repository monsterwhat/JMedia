package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "video",
       indexes = {
           @Index(name = "idx_video_type", columnList = "type"),
           @Index(name = "idx_video_title", columnList = "title"),
           @Index(name = "idx_video_series", columnList = "seriesTitle"),
           @Index(name = "idx_video_year", columnList = "releaseYear"),
           @Index(name = "idx_video_active", columnList = "isActive"),
           @Index(name = "idx_video_watch_progress", columnList = "watchProgress"),
           @Index(name = "idx_video_favorite", columnList = "favorite"),
           @Index(name = "idx_video_composite", columnList = "type, releaseYear, isActive")
       })
public class Video extends PanacheEntity {

    // Core Identification
    @Column(length = 500)
    public String path;
    public String filename;
    public String type; // "movie", "episode", "short", "documentary"
    
    // Titles and Basic Information
    public String title; // Primary title
    public String seriesTitle; // For episodes
    public String episodeTitle; // Episode-specific title
    public Integer seasonNumber;
    public Integer episodeNumber;
    public Integer releaseYear;
    public String description;
    public String tagline;
    public String overview;
    
    // Entertainment Metadata
    public Double imdbRating = 0.0;
    public Double tmdbRating = 0.0;
    public Double userRating = 0.0;
    public String mpaaRating; // "PG-13", "R", etc.
    public Integer voteCount = 0;
    public Double popularityScore = 0.0;
    
    // People Information
    @ElementCollection
    @CollectionTable(name = "video_genres_list")
    @Column(name = "genre")
    public List<String> genres;
    
    @ElementCollection
    @CollectionTable(name = "video_directors")
    @Column(name = "director")
    public List<String> directors;
    
    @ElementCollection
    @CollectionTable(name = "video_writers")
    @Column(name = "writer")
    public List<String> writers;
    
    @ElementCollection
    @CollectionTable(name = "video_cast")
    @Column(name = "cast_member")
    public List<String> cast;
    
    // External IDs
    public String imdbId;
    public String tmdbId;
    public String tvdbId;
    
    // Collections and Franchises
    public String collectionName; // "Marvel Cinematic Universe"
    public String franchiseName;
    public Integer collectionOrder;
    
    // Technical Metadata
    public String resolution; // "1920x1080"
    public String displayResolution; // "1080p"
    public String videoCodec;
    public String videoProfile;
    public Integer bitrate = 0;
    public Integer frameRate = 0;
    public Double aspectRatio = 0.0;
    public String releaseGroup;
    public String source; // WEB-DL, BluRay, etc.
    public Double confidenceScore = 0.0;
    
    public String audioCodec;
    public String audioProfile;
    public Integer audioChannels = 0;
    public String primaryAudioLanguage; // Primary audio language
    public Integer audioBitrate = 0;
    
    public String container; // "mp4", "mkv", "avi"
    public String format; // Legacy compatibility
    public Long duration = 0L; // milliseconds
    public Long size = 0L; // bytes
    public Long fileSize = 0L; // Legacy DB column compatibility
    public String quality; // "HD", "Full HD", "4K", "8K"
    public Long lastModified = 0L;

    /**
     * Helper for templates to get duration in seconds.
     * @return Duration in seconds.
     */
    public Integer getDurationSeconds() {
        return duration != null ? (int) (duration / 1000) : 0;
    }

    public Integer getWatchProgressPercent() {
        return watchProgress != null ? (int) (watchProgress * 100) : 0;
    }
    
    // Media Paths
    public String thumbnailPath;
    public String posterPath;
    public String backdropPath;
    public String fanartPath;
    
    // Subtitle Information
    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<SubtitleTrack> subtitleTracks;
    
    public Long defaultSubtitleTrackId;
    public String preferredSubtitleLanguage; // User preference
    public boolean hasSubtitles; // Whether video has embedded/sidecar subtitles
    public boolean autoSelectSubtitles; // Enable auto-selection based on audio
    
    // User Interaction Fields
    public LocalDateTime dateAdded;
    public LocalDateTime lastWatched;
    public LocalDateTime dateModified;
    public Long resumeTime = 0L; // Current playback position in milliseconds
    public Double watchProgress = 0.0; // 0.0 to 1.0
    public Double watchProgressDouble = 0.0; // Compatibility field
    public boolean watched = false;
    public boolean favorite = false;
    public LocalDateTime favoritedAt;
    public Integer watchCount = 0;
    public Long totalWatchTime = 0L; // milliseconds
    
    // User Ratings and Preferences
    public Integer userRatingStars = 0; // 1-10 stars
    public LocalDateTime userRatingDate;
    public String userNotes;
    
    // Playback Statistics
    public Integer playCount = 0;
    public Double averagePlaybackSpeed = 1.0;
    public boolean skipIntroEnabled = false;
    public boolean autoplayNext = false;
    
    // System Fields
    public boolean isActive = true;
    
    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath;
    }
     
}