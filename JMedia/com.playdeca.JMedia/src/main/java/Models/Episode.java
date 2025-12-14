package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "episodes")
public class Episode extends PanacheEntity {

    public String title;
    public int seasonNumber;
    public int episodeNumber;
    public String videoPath;

    @ManyToOne
    public Season season;

    @ElementCollection
    public List<String> subtitlePaths;
}
