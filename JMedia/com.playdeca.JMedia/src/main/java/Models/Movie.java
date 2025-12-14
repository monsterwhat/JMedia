package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "movies")
public class Movie extends PanacheEntity {

    public String title;
    
    public Integer releaseYear;
    
    public String videoPath;

    @ElementCollection
    public List<String> subtitlePaths;
}
