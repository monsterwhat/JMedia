package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Song extends PanacheEntity {

    private String title;
    private String artist;
    private String album;
    private String albumArtist;
    private int trackNumber;
    private int discNumber;
    private String date;
    private String releaseDate;
    private String genre;
    @Column(length = Integer.MAX_VALUE)
    private String lyrics;
    private boolean explicit;
    private int bpm;
    private int durationSeconds;
    private String path;
    @Column(length = Integer.MAX_VALUE)
    private String artworkBase64;
    private java.time.LocalDateTime dateAdded;
 
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Song song = (Song) o;
        return id != null && id.equals(song.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
