package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class VideoHistory extends PanacheEntity {

    @ManyToOne
    public Video video; // Changed from Song

    @ManyToOne
    public Profile profile;

    public LocalDateTime playedAt;

    public VideoHistory() {
    }
}
