package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_progress", uniqueConstraints = @UniqueConstraint(columnNames = {"profile_id", "video_id"}))
public class VideoState extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    @ManyToOne
    @JoinColumn(name = "video_id")
    public Video video;

    public double currentTime; // Resume position (seconds)
    public Double watchProgress = 0.0; // 0.0 to 1.0
    public Boolean watched = false;
    public LocalDateTime lastUpdated; // For sorting Continue Watching
}
