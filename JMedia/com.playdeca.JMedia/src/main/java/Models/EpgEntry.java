package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "epg_entry", indexes = {
    @Index(name = "idx_epg_channel", columnList = "epgChannelId"),
    @Index(name = "idx_epg_start", columnList = "startTime"),
    @Index(name = "idx_epg_end", columnList = "endTime"),
    @Index(name = "idx_epg_channel_start", columnList = "epgChannelId, startTime")
})
public class EpgEntry extends PanacheEntity {

    @Column(length = 200)
    public String epgChannelId;

    @Column(length = 500)
    public String title;

    @Column(length = 2000)
    public String description;

    @Column(length = 2000)
    public String icon;

    public LocalDateTime startTime;

    public LocalDateTime endTime;

    @Column(length = 100)
    public String language;

    @Column(length = 100)
    public String episode;

    @Column(length = 200)
    public String category;

    public LocalDateTime importedAt;
}
