package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
public class AudioTrack extends PanacheEntity {

    // File Information
    public String filename;
    @Column(length = 500)
    public String fullPath;
    public String format; // "aac", "ac3", "eac3", "dts", "mp3", "opus", "flac"
    
    // Language & Display
    public String languageCode; // ISO 639-2: "eng", "fre", "spa"
    public String languageName; // "English", "Français", "Español"
    public String displayName; // "English", "Español (Commentary)", etc.
    
    // Track Properties
    public boolean isDefault;     // Default track for this video
    public boolean isEmbedded;     // Embedded in video container
    
    // Technical Metadata
    public Integer trackIndex;     // Stream index
    public String codec;          // "aac", "ac3", "eac3", "dts", etc.
    public Integer channels;      // 2, 6 (5.1), 8 (7.1)
    public Integer bitrate;       // Audio bitrate
    public Integer sampleRate;    // 48000, 44100, etc.
    public String title;          // From ffprobe tags (e.g. "Director's Commentary")
    
    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Video video;
    
    // System fields
    public boolean isActive = true;
}
