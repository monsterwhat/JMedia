package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
public class Settings extends PanacheEntity {

    private String libraryPath;
    private String videoLibraryPath;

    private Boolean firstTimeSetup = true;
    
    private Long activeProfileId;
    
    private Boolean runAsService = false;
      
    private String outputFormat = "mp3";
    private Integer downloadThreads = 4;
    private Integer searchThreads = 4;
     
    @OneToMany(cascade = CascadeType.ALL, fetch=FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "settings_id")
    private List<SettingsLog> logs = new ArrayList<>();
    
    private String currentVersion = "0.9.0";
    private String lastUpdateCheck;
    private Boolean autoUpdateEnabled = true;
    
    // Thumbnail processing settings
    private Integer thumbnailApiDelayMs = 1000;  // Delay between API calls in milliseconds
    private Integer thumbnailMaxRetries = 3;      // Maximum retry attempts for failed thumbnails
    private Integer thumbnailProcessingThreads = 2; // Number of thumbnail processing threads
    private Boolean thumbnailPreferApi = true;   // Prefer API thumbnails over local extraction
    private Boolean thumbnailRegenerateOnReload = true; // Regenerate all thumbnails during metadata reload
    
    
}
