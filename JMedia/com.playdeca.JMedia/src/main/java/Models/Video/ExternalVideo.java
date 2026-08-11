package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "external_video",
        indexes = {
            @Index(name = "idx_external_profile", columnList = "profile_id"),
            @Index(name = "idx_external_updated", columnList = "profile_id, lastUpdated"),
            @Index(name = "idx_external_series", columnList = "seriesTitle"),
            @Index(name = "idx_external_entrytype", columnList = "entryType")
        })
public class ExternalVideo extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    @Column(length = 2048)
    public String url;

    @Column(columnDefinition = "TEXT")
    public String alternativeUrls;

    @Column(length = 500)
    public String title;

    public String sourceType;

    public double currentTime;
    public Double watchProgress = 0.0;
    public Boolean watched = false;
    public LocalDateTime lastUpdated;

    @Column(length = 500)
    public String seriesTitle;

    public Integer seasonNumber;
    public Integer episodeNumber;

    @Column(length = 500)
    public String episodeTitle;

    @Enumerated(EnumType.STRING)
    public ExistingVideo entryType;
}
