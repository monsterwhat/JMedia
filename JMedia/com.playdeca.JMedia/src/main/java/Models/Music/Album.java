package Models.Music;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A real album row. Songs used to be grouped on the fly with
 * {@code GROUP BY s.album, s.albumArtist}; this entity gives that group a stable
 * numeric id so it can be addressed (deep links, shareable URLs, drag targets)
 * and lets the UI talk about an album without passing its name around.
 *
 * The (album, albumArtist) pairing matches the old grouping exactly, including
 * compilations, whose albumArtist is blank ("") rather than null.
 */
@Entity
@Table(name = "album",
        uniqueConstraints = @UniqueConstraint(name = "uk_album_name_artist",
                columnNames = {"name_key", "artist_key"}),
        indexes = {
                @Index(name = "idx_album_name_key", columnList = "name_key"),
                @Index(name = "idx_album_artist_key", columnList = "artist_key")
        })
public class Album extends PanacheEntity {

    /** Display name, exactly as tagged. */
    @Column(name = "name", nullable = false, length = 512)
    public String name;

    /** Album artist exactly as tagged; "" for compilations (never null). */
    @Column(name = "artist", nullable = false, length = 512)
    public String artist = "";

    /** Normalised lookup keys (trim + collapse spaces + lowercase). */
    @Column(name = "name_key", nullable = false, length = 512)
    public String nameKey;

    @Column(name = "artist_key", nullable = false, length = 512)
    public String artistKey = "";

    /** First artwork seen for the album, used by the grid. */
    @Column(name = "artwork_path", length = 1024)
    public String artworkPath;

    @Column(name = "release_date", length = 32)
    public String releaseDate;
}
