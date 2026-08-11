package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
public class MediaDirectory extends PanacheEntity {

    @Column(unique = true, nullable = false, length = 1000)
    public String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public MediaType mediaType;

    public Boolean enabled = true;

    public Instant dateAdded;

    public Long fileCount;

    public Instant lastScan;

    public Integer scanDurationSeconds;

    public Boolean scanInProgress;

    public Instant lastScanStart;

    public enum MediaType {
        MUSIC,
        VIDEO
    }

    // Helpers
    public static java.util.List<MediaDirectory> findByType(MediaType type) {
        return list("mediaType", type);
    }

    public static java.util.List<MediaDirectory> findEnabledByType(MediaType type) {
        return list("mediaType = ?1 and enabled = true", type);
    }

    public static MediaDirectory findByPath(String path) {
        return find("path", path).firstResult();
    }

    public boolean isMusic() {
        return mediaType == MediaType.MUSIC;
    }

    public boolean isVideo() {
        return mediaType == MediaType.VIDEO;
    }
}