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


    
    private Long activeProfileId;
    
    private Boolean runAsService = false;
      
    private String outputFormat = "mp3";
    private Integer downloadThreads = 4;
    private Integer searchThreads = 4;
     
    @OneToMany(cascade = CascadeType.ALL, fetch=FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "settings_id")
    private List<SettingsLog> logs = new ArrayList<>();
    
    
}
