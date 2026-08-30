package Models.Music;

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
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Basic(fetch = FetchType.LAZY)
    @Column(length = Integer.MAX_VALUE)
    private String lyrics;
    private boolean explicit;
    private int bpm;
    private int durationSeconds;
    private String path;

    private java.time.LocalDateTime dateAdded;
    private java.time.LocalDateTime updatedAt;
    private Long size;
    private Long lastModified;
    private String musicbrainzId;

    private String artworkPath;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @OneToOne(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private SongAnalysis analysis;



    public boolean isFlac() {
        return path != null && path.toLowerCase().endsWith(".flac");
    }

    public boolean hasArtwork() {
        return artworkPath != null && !artworkPath.isBlank();
    }

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
