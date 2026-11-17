package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Stable Xtream category id for a normalized live-TV group name.
 *
 * <p>IPtv clients cache categories by {@code category_id}, so the id must not
 * change across restarts or playlist re-imports. The old name-hash scheme could
 * collide (and silently dropped the second group); this table makes the mapping
 * authoritative and unique.
 */
@Entity
@Table(name = "live_group_category", indexes = {
    @Index(name = "idx_livegroup_key", columnList = "group_key", unique = true),
    @Index(name = "idx_livegroup_category", columnList = "category_id", unique = true)
})
public class LiveGroupCategory extends PanacheEntity {

    /** Normalized (trimmed, lower-cased) M3U group-title. */
    @Column(name = "group_key", length = 500, nullable = false, unique = true)
    public String groupKey;

    /** Numeric Xtream category id, stable for the lifetime of the group. */
    @Column(name = "category_id", nullable = false, unique = true)
    public Long categoryId;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
