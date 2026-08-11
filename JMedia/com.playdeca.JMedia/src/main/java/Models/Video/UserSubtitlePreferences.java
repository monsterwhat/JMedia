package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "user_subtitle_preferences")
public class UserSubtitlePreferences extends PanacheEntity {

    @Column(name = "user_id")
    public Long userId;
    
    public String preferredLanguage;         // ISO language code
    public boolean enableAutoSelection = true;      // Enable intelligent auto-selection
    public boolean preferForcedSubtitles = false;   // Preference for forced tracks
    public boolean preferSDHSubtitles = false;      // Preference for SDH tracks
    public String subtitleStyle = "default";             // "default", "large", "high-contrast"
    
    @Lob
    public String subtitleAppearance;        // JSON with custom styling
}