package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "media_collection", indexes = {
    @Index(name = "idx_collection_profile", columnList = "profile_id")
})
public class MediaCollection extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    public Profile profile;

    public Boolean isPublic = false;

    public String name;

    @Column(length = 2000)
    public String description;

    public int sortOrder = 0;

    public LocalDateTime createdDate;

    public Long coverVideoId;
}
