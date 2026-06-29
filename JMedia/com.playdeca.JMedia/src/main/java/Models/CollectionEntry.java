package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "collection_entry", indexes = {
    @Index(name = "idx_entry_external_video", columnList = "external_video_id")
})
public class CollectionEntry extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    public MediaCollection collection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    public Video video;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_video_id")
    public ExternalVideo externalVideo;

    public int orderIndex;

    @Column(length = 500)
    public String notes;
}
