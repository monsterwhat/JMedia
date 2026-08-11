package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "m3u_playlist", indexes = {
    @Index(name = "idx_m3u_profile", columnList = "profile_id")
})
public class M3uPlaylist extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    @Column(length = 2000)
    public String url;

    @Column(length = 500)
    public String name;

    public String type; // "live", "vod", "mixed"

    public Integer channelCount = 0;

    public LocalDateTime lastRefreshed;

    public Boolean isActive = true;

    public LocalDateTime createdAt;
}
