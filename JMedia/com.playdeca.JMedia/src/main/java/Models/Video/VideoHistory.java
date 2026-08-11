package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class VideoHistory extends PanacheEntity {

    @ManyToOne
    public MediaFile mediaFile;

    @Column(name = "profile_id")
    public Long profileId;

    public LocalDateTime playedAt;

    public VideoHistory() {
    }
}
