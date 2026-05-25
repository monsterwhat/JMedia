package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"profile_id", "collection_id"}))
public class CollectionWatchProgress extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    @ManyToOne
    @JoinColumn(name = "collection_id")
    public MediaCollection collection;

    public Long lastVideoId;
    public int lastEntryIndex;
    public int totalEntries;
    public int completedEntries;
    public Double progress = 0.0;
    public LocalDateTime lastUpdated;
}
