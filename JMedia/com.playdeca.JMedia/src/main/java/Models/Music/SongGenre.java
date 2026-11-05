package Models.Music;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One resolved canonical genre for a song. A song with "Rock/Pop" has two rows,
 * so genre browsing lists it under both.
 */
@Entity
@Table(name = "song_genres",
        uniqueConstraints = @UniqueConstraint(name = "uk_song_genres_song_key",
                columnNames = {"song_id", "genre_key"}),
        indexes = {
                @Index(name = "idx_song_genres_key", columnList = "genre_key"),
                @Index(name = "idx_song_genres_name", columnList = "genre_name")
        })
public class SongGenre extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id", nullable = false)
    public Song song;

    @Column(name = "genre_key", nullable = false, length = 128)
    public String genreKey;

    @Column(name = "genre_name", nullable = false, length = 128)
    public String genreName;

    @Column(name = "sort_order", nullable = false)
    public int sortOrder;
}
