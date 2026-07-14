package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "live_channel", indexes = {
    @Index(name = "idx_livechannel_profile", columnList = "profile_id"),
    @Index(name = "idx_livechannel_group", columnList = "groupTitle"),
    @Index(name = "idx_livechannel_playlist", columnList = "playlist_id")
})
public class LiveChannel extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    @ManyToOne
    @JoinColumn(name = "playlist_id")
    public M3uPlaylist playlist;

    @Column(length = 500)
    public String name;

    @Column(length = 2000)
    public String streamUrl;

    @Column(length = 2000)
    public String logoUrl;

    @Column(length = 200)
    public String groupTitle;

    @Column(length = 200)
    public String tvgId;

    @Column(length = 200)
    public String tvgName;

    @Column(length = 100)
    public String country;

    public Integer channelNumber;

    public Boolean isFavorite = false;

    public String streamStatus;

    public LocalDateTime lastChecked;

    public LocalDateTime lastWatched;

    public LocalDateTime createdAt;
}
