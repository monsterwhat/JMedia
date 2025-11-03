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
    private int durationSeconds; // store as integer seconds
    private String path; // local file path or URL
    private String coverImagePath;
    private String genre;
    @Column(length = Integer.MAX_VALUE) // Set to max value to hint for CLOB/TEXT type
    private String artworkBase64;
    private java.time.LocalDateTime dateAdded;

    @Transient
    private byte[] artwork;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return id != null && id.equals(song.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
