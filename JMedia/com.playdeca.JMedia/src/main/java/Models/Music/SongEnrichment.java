package Models.Music;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Standalone cache of enriched metadata, decoupled from the {@link Song} lifecycle.
 *
 * <p>Enrichment results (MusicBrainz/Deezer/AcousticBrainz/TheAudioDB) are written here
 * after every successful enrichment and read back during imports / re-scans. Because this
 * table is never touched by {@code SongService.delete} or {@code SongService.clearSongsByDirectory},
 * deleting a song — or clearing the whole library and re-scanning — preserves the enrichment,
 * so the external APIs are not hit again.
 *
 * <p>Keyed by normalized "artist :: title" (lowercase, trimmed, whitespace collapsed), which
 * matches exactly how the enrichment APIs are queried (searchMusicBrainz/searchDeezer are both
 * artist+title lookups). MusicBrainz ID is stored as a secondary key for lookups when present.
 * Sentinel values ("Unknown Artist" / "Unknown Title" / etc.) are rejected by the service, never
 * stored here.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "SongEnrichment", indexes = {
        @Index(name = "idx_song_enrichment_cache_key", columnList = "cacheKey", unique = true)
})
public class SongEnrichment extends PanacheEntity {

    /** Normalized "artist :: title" lookup key. Never null in a persisted row. */
    private String cacheKey;

    /** MusicBrainz recording ID (secondary lookup key). Nullable. */
    private String musicbrainzId;

    private String artist;
    private String title;
    private String album;
    private String genre;
    private String releaseDate;

    /** BPM from AcousticBrainz or file tags. 0 = unknown. */
    private int bpm;

    /** Album artwork as base64 — huge column, mirrors {@link Song#artworkBase64}. */
    @Column(length = Integer.MAX_VALUE)
    private String artworkBase64;

    /** Where the artwork came from: Deezer / TheAudioDB / file / enrichment. */
    private String artworkSource;

    private LocalDateTime enrichedAt;
    private LocalDateTime updatedAt;
}