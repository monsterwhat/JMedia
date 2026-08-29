package Models.Music;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Lightweight projection of {@link Song} used by paginated list endpoints.
 *
 * The full {@link Song} entity carries 2 CLOB fields ({@code lyrics},
 * {@code artworkBase64}, both up to several hundred KB) and an eagerly-loaded
 * {@link SongAnalysis} reference that itself contains 3 more CLOB columns and
 * an {@code @ElementCollection} of beat times. Loading 12 such songs per page
 * blows the heap under concurrent UI traffic.
 *
 * The list UI only needs {@code id, title, artist, isFlac, album, duration}.
 * This projection skips the heavy columns entirely.
 */
@RegisterForReflection
public class SongListItem {

    public Long id;
    public String title;
    public String artist;
    public String album;
    public int durationSeconds;
    public String path;
    public java.time.LocalDateTime dateAdded;
    public int trackNumber;

    public SongListItem() {
    }

    public SongListItem(Long id, String title, String artist, String album,
                        int durationSeconds, String path,
                        java.time.LocalDateTime dateAdded, int trackNumber) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationSeconds = durationSeconds;
        this.path = path;
        this.dateAdded = dateAdded;
        this.trackNumber = trackNumber;
    }

    @JsonIgnore
    public boolean isFlac() {
        return path != null && path.toLowerCase().endsWith(".flac");
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getPath() {
        return path;
    }
}
