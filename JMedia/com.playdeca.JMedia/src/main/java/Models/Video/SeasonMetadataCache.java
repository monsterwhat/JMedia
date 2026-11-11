package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-season snapshot of TMDb episode metadata, decoupled from the local
 * {@link Video} rows so a missing or unreleased episode can be shown without
 * creating a fake library entry.
 *
 * <p>Keyed by (tmdbId, seasonNumber). {@code nextCheckAt} drives the refresher:
 * an airing season is checked every few hours, a finished one every 90 days.
 */
@Entity
@Table(name = "season_metadata_cache", indexes = {
        @Index(name = "idx_season_cache_key", columnList = "tmdbId,seasonNumber", unique = true),
        @Index(name = "idx_season_cache_next_check", columnList = "nextCheckAt")
})
public class SeasonMetadataCache extends PanacheEntity {

    public Integer tmdbId;
    public Integer seasonNumber;

    /** JSON: [{number,name,overview,airDate,stillPath,released}, ...] */
    @Lob
    public String payloadJson;

    public Integer episodeCount;
    public String lastAirDate;
    public String nextAirDate;
    public String showStatus;

    public Instant fetchedAt;
    public Instant nextCheckAt;

    /** All episodes aired and the show is Ended/Canceled. A hint, not a lock. */
    public boolean complete;

    /** User said "stop checking this season"; cleared by a manual refresh. */
    public boolean manualComplete;
}
