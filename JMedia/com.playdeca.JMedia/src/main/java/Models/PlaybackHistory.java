package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class PlaybackHistory extends PanacheEntity {

    @ManyToOne
    public Song song;

    @ManyToOne
    public Profile profile;

    public LocalDateTime playedAt;

    public PlaybackHistory() {
    }
}