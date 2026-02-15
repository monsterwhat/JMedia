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
    public Double imdbRating;
    public Double tmdbRating;
    public Double userRating;
    public String mpaaRating; // "PG-13", "R", etc.
    public Integer voteCount;
    public Double popularityScore;
    
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
    public Integer bitrate;
    public Integer frameRate;
    public Double aspectRatio;
    
    public String audioCodec;
    public String audioProfile;
    public Integer audioChannels;
    public String primaryAudioLanguage; // Primary audio language
    public Integer audioBitrate;
    
    public String container; // "mp4", "mkv", "avi"
    public String format; // Legacy compatibility
    public Long duration; // milliseconds
    public Long size; // bytes
    public String quality; // "HD", "Full HD", "4K", "8K"
    public Long lastModified;
    
    // Media Paths
    public String thumbnailPath;
    public String posterPath;
    public String backdropPath;
    public String fanartPath;
    
    // Subtitle Information
    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    public List<SubtitleTrack> subtitleTracks;
    
    public Long defaultSubtitleTrackId;
    public String preferredSubtitleLanguage; // User preference
    public boolean autoSelectSubtitles; // Enable auto-selection based on audio
    
    // User Interaction Fields
    public LocalDateTime dateAdded;
    public LocalDateTime lastWatched;
    public LocalDateTime dateModified;
    public Double watchProgress; // 0.0 to 1.0
    public boolean watched;
    public boolean favorite;
    public LocalDateTime favoritedAt;
    public Integer watchCount;
    public Long totalWatchTime; // milliseconds
    
    // User Ratings and Preferences
    public Integer userRatingStars; // 1-10 stars
    public LocalDateTime userRatingDate;
    public String userNotes;
    
    // Playback Statistics
    public Integer playCount;
    public Double averagePlaybackSpeed;
    public boolean skipIntroEnabled;
    public boolean autoplayNext;
    
    // System Fields
    public boolean isActive = true;
    
    // Legacy compatibility fields (deprecated but kept for compatibility)
    @Deprecated
    public boolean hasSubtitles;
    @Deprecated
    public String subtitlePath;
    @Deprecated
    public String genre;
    @Deprecated
    public String rating;
    @Deprecated
    public String dateAddedString; // String version, replaced by LocalDateTime
    @Deprecated
    public String lastWatchedString; // String version, replaced by LocalDateTime
    @Deprecated
    public double fileSize; // Replaced by Long size
    @Deprecated
    public double watchProgressDouble; // Replaced by Double for precision
}