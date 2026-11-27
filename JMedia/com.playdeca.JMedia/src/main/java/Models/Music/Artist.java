package Models.Music;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A real artist row, created from {@code Song.artist}. Gives artist pages a
 * stable numeric id instead of a URL-encoded name.
 */
@Entity
@Table(name = "artist",
        indexes = {
                @Index(name = "idx_artist_name_key", columnList = "name_key", unique = true)
        })
public class Artist extends PanacheEntity {

    /** Display name, exactly as tagged. */
    @Column(name = "name", nullable = false, length = 512)
    public String name;

    /** Normalised lookup key (trim + collapse spaces + lowercase). */
    @Column(name = "name_key", nullable = false, length = 512)
    public String nameKey;
}
