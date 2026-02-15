package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
public class SubtitleTrack extends PanacheEntity {

    // File Information
    public String filename;
    public String fullPath;
    public String format; // "srt", "vtt", "ass", "ssa"
    public String encoding; // "UTF-8", "ISO-8859-1"
    public Long fileSize;
    
    // Language & Display
    public String languageCode; // ISO 639-2: "eng", "fre", "spa"
    public String languageName; // "English", "Français", "Español"
    public String displayName; // "English", "Français (Forced)", "English SDH"
    
    // Track Properties
    public boolean isForced;      // Forced display subtitles
    public boolean isSDH;         // Subtitles for Deaf/Hard-of-hearing
    public boolean isDefault;     // Default track for this video
    public boolean isEmbedded;     // Embedded in video container
    
    // Styling (for ASS/SSA)
    @Lob
    public String stylingData;    // Preserved styling information
    
    // Technical Metadata
    public Integer trackIndex;     // Stream index for embedded subtitles
    public String codec;          // "subrip", "ass", "webvtt"
    
    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    public Video video;
    
    // User Preferences
    public Integer userPreferenceOrder; // User's ranking of this track
    
    // System fields
    public boolean isActive = true;
}