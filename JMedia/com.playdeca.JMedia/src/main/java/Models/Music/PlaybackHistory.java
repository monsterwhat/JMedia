package Models.Music;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class PlaybackHistory extends PanacheEntity {

    @ManyToOne
    public Song song;

    @Column(name = "profile_id")
    public Long profileId;

    public LocalDateTime playedAt;

    public PlaybackHistory() {
    }
}