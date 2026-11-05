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

    /**
     * Every canonical genre this song belongs to. Derived from the raw {@link #genre}
     * tag; "Rock/Pop" yields two rows so the song lists under both.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private java.util.List<SongGenre> genres = new java.util.ArrayList<>();

    /**
     * Re-resolve the raw genre into the {@link #genres} link rows. Idempotent:
     * when the resolved set is unchanged it does nothing, so ordinary updates
     * (play counts, artwork, etc.) do not churn the link table.
     */
    public void syncGenresFromRaw() {
        java.util.List<Utils.GenreNormalizer.Genre> resolved = Utils.GenreNormalizer.resolveAll(genre);
        boolean same = genres != null && genres.size() == resolved.size();
        if (same) {
            for (int i = 0; i < resolved.size(); i++) {
                if (!resolved.get(i).key().equals(genres.get(i).genreKey)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        if (genres == null) genres = new java.util.ArrayList<>();
        genres.clear();
        int position = 0;
        for (Utils.GenreNormalizer.Genre g : resolved) {
            SongGenre link = new SongGenre();
            link.song = this;
            link.genreKey = g.key();
            link.genreName = g.name();
            link.sortOrder = position++;
            genres.add(link);
        }
    }

    @PrePersist
    @PreUpdate
    private void syncGenresOnWrite() {
        syncGenresFromRaw();
    }

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
