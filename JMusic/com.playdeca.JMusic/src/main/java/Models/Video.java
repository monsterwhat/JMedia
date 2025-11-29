package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class Video extends PanacheEntity {

    private String title;
    private String mediaType; // E.g., "Movie", "Episode"
    private String seriesTitle;
    private Integer seasonNumber;
    private Integer episodeNumber;
    private String episodeTitle;
    private Integer releaseYear;
    private int durationSeconds;
    private String path;
    @Column(length = Integer.MAX_VALUE)
    private String thumbnailBase64;
    private LocalDateTime dateAdded;
    private int width;
    private int height;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Video video = (Video) o;
        return id != null && id.equals(video.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
