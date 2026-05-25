package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "collection_entry")
public class CollectionEntry extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    public MediaCollection collection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    public Video video;

    public int orderIndex;

    @Column(length = 500)
    public String notes;
}
