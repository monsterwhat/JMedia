package Services;

import Models.Music.Song;
import Models.Music.SongEnrichment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Persistence layer for the {@link SongEnrichment} cache.
 *
 * <p>Writes happen at the end of every successful enrichment ({@code MusicEnrichmentService.enrichSong}
 * and {@code API.Rest.MetadataEnrichmentApi.updateSongMetadata}, including the async artwork path).
 * Reads happen during import ({@code SettingsController.processFile}) and at the top of
 * {@code enrichSong} so a cache hit skips all external API calls.
 *
 * <p>The cache is deliberately independent of the {@code Song} lifecycle: song deletion and library
 * clears never touch it, so enrichment survives a clear + re-scan.
 */
@ApplicationScoped
public class SongEnrichmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SongEnrichmentService.class);

    /** Batch size for the one-time library backfill (also bounds transient blob memory). */
    private static final int BACKFILL_BATCH_SIZE = 200;

    /**
     * Marker row key marking a completed library backfill. Never collides with a real
     * cache entry: every song key is normalized "artist :: title" and contains " :: ",
     * this marker does not.
     */
    private static final String BACKFILL_MARKER_KEY = "__jmedia_backfill_complete__";

    @PersistenceContext(unitName = "music")
    EntityManager em;

    @Inject
    SongEnrichmentService self; // self-reference for REQUIRES_NEW batch boundary

    // ─────────────────────────────────────────────────────────────────────────
    // Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upserts the enrichment cache from a song's current state.
     * Joins the caller's transaction (safe inside {@code enrichSong}'s REQUIRED tx,
     * the async artwork {@code thenAccept} path, and the backfill's REQUIRES_NEW batch).
     * Skips songs whose artist/title is blank or an "Unknown *" sentinel — caching those
     * would poison the key space and spread wrong metadata to unrelated untagged files.
     */
    @Transactional
    public void save(Song song) {
        if (song == null) {
            return;
        }
        String key = cacheKey(song.getArtist(), song.getTitle());
        if (key == null) {
            return; // unknown/blank artist or title — nothing stable to cache under
        }

        SongEnrichment existing = SongEnrichment.find("cacheKey", key).firstResult();
        if (existing == null) {
            existing = new SongEnrichment();
            existing.setCacheKey(key);
            try {
                existing.persist();
            } catch (PersistenceException e) {
                // Concurrent upsert of the same key (scan thread + DJ worker).
                if (!(e.getCause() instanceof org.hibernate.exception.ConstraintViolationException)) {
                    throw e;
                }
                em.clear(); // persistence context may be tainted by the failed insert
                SongEnrichment concurrent = SongEnrichment.find("cacheKey", key).firstResult();
                if (concurrent == null) {
                    LOGGER.warn("SongEnrichment: concurrent insert lost for key '{}'", key);
                    return;
                }
                existing = concurrent;
            }
        }

        if (song.getMusicbrainzId() != null) {
            existing.setMusicbrainzId(song.getMusicbrainzId());
        }
        if (song.getArtist() != null) {
            existing.setArtist(song.getArtist());
        }
        if (song.getTitle() != null) {
            existing.setTitle(song.getTitle());
        }
        if (song.getAlbum() != null && !"Unknown Album".equals(song.getAlbum())) {
            existing.setAlbum(song.getAlbum());
        }
        if (song.getGenre() != null && !"Unknown Genre".equals(song.getGenre())) {
            existing.setGenre(song.getGenre());
        }
        if (song.getReleaseDate() != null && !song.getReleaseDate().isBlank()) {
            existing.setReleaseDate(song.getReleaseDate());
        }
        if (song.getBpm() > 0) {
            existing.setBpm(song.getBpm());
        }
        if (song.getArtworkBase64() != null && !song.getArtworkBase64().isBlank()) {
            existing.setArtworkBase64(song.getArtworkBase64());
            if (existing.getArtworkSource() == null) {
                existing.setArtworkSource("enrichment");
            }
        }
        if (existing.getEnrichedAt() == null) {
            existing.setEnrichedAt(LocalDateTime.now());
        }
        existing.setUpdatedAt(LocalDateTime.now());

        em.merge(existing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Backfills missing fields on a song from the cache. Only fills fields that are
     * null/blank/0/"Unknown *" — file tags always win over cached data, so a cache hit
     * can never clobber fresher or manually-edited tags. Restores {@code musicbrainzId}
     * too; without it {@code enrichSong}'s guard still sees needsMusicBrainz=true and
     * pays the MusicBrainz API call + rate-limit sleep even on a full cache hit.
     *
     * @return true when at least one field was filled (callers must persist the song)
     */
    @Transactional
    public boolean applyToSong(Song song) {
        if (song == null) {
            return false;
        }
        String key = cacheKey(song.getArtist(), song.getTitle());
        if (key == null) {
            return false;
        }

        SongEnrichment entry = SongEnrichment.find("cacheKey", key).firstResult();
        if (entry == null && song.getMusicbrainzId() != null) {
            entry = SongEnrichment.find("musicbrainzId", song.getMusicbrainzId()).firstResult();
        }
        if (entry == null) {
            return false;
        }

        boolean applied = false;
        if (isMissing(song.getArtist())) {
            song.setArtist(entry.getArtist());
            applied = true;
        }
        if (isMissing(song.getTitle())) {
            song.setTitle(entry.getTitle());
            applied = true;
        }
        if (isMissing(song.getAlbum())) {
            song.setAlbum(entry.getAlbum());
            applied = true;
        }
        if (isMissing(song.getGenre())) {
            song.setGenre(entry.getGenre());
            applied = true;
        }
        if (isMissing(song.getReleaseDate())) {
            song.setReleaseDate(entry.getReleaseDate());
            applied = true;
        }
        if (song.getBpm() <= 0) {
            song.setBpm(entry.getBpm());
            applied = true;
        }
        if (isMissing(song.getArtworkBase64())) {
            song.setArtworkBase64(entry.getArtworkBase64());
            applied = true;
        }
        if (isMissing(song.getMusicbrainzId())) {
            song.setMusicbrainzId(entry.getMusicbrainzId());
            applied = true;
        }
        return applied;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // One-time library backfill (old entity → cache migration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Migrates enrichment data already stored on existing {@code Song} rows into the cache,
     * so a library that was enriched before this feature existed is preserved across a
     * clear + re-scan. Idempotent: upserts by cacheKey, so re-runs are safe.
     *
     * <p>Crash-resumable: batches commit independently (REQUIRES_NEW), and completion is
     * marked by a dedicated marker row — a key without the " :: " separator can never equal
     * a real song key, since every cached key contains it. If the app dies mid-backfill, the
     * next boot resumes (skipping already-cached keys via upsert) instead of aborting forever.
     * Never blocks application startup — the trigger bean fires it on a daemon thread.
     */
    @ActivateRequestContext
    public void backfillFromLibrary() {
        try {
            if (isBackfillComplete()) {
                LOGGER.info("SongEnrichment backfill skipped — already completed");
                return;
            }
            LOGGER.info("SongEnrichment backfill: scanning library for existing enrichment data...");
            long lastId = 0L;
            int totalBatches = 0;
            while (true) {
                long nextLastId = self.backfillBatch(lastId);
                if (nextLastId <= lastId) {
                    break;
                }
                lastId = nextLastId;
                totalBatches++;
            }
            self.markBackfillComplete();
            LOGGER.info("SongEnrichment backfill complete — processed {} batches, cache now has {} rows",
                    totalBatches, SongEnrichment.count());
        } catch (Exception e) {
            LOGGER.error("SongEnrichment backfill failed (non-fatal, app continues): {}", e.getMessage(), e);
        }
    }

    private boolean isBackfillComplete() {
        return SongEnrichment.find("cacheKey", BACKFILL_MARKER_KEY).firstResult() != null;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markBackfillComplete() {
        if (isBackfillComplete()) {
            return;
        }
        SongEnrichment marker = new SongEnrichment();
        marker.setCacheKey(BACKFILL_MARKER_KEY);
        marker.setUpdatedAt(LocalDateTime.now());
        marker.persist();
    }

    /**
     * Processes one id-window batch in its own transaction. Returns the highest song id
     * seen (the next {@code lastId}), or the same value when the batch was empty.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long backfillBatch(long lastId) {
        List<Song> batch = Song.find("id > ?1 order by id", lastId)
                .page(0, BACKFILL_BATCH_SIZE)
                .list();
        if (batch.isEmpty()) {
            return lastId;
        }
        long maxId = lastId;
        for (Song song : batch) {
            maxId = Math.max(maxId, song.id);
            if (hasEnrichmentData(song)) {
                save(song);
            }
        }
        em.clear(); // detach the batch's blob-loaded entities before the next window
        return maxId;
    }

    /** Only backfill songs that actually carry enrichment-worthy data. */
    private boolean hasEnrichmentData(Song song) {
        return song.getMusicbrainzId() != null
                || (song.getArtworkBase64() != null && !song.getArtworkBase64().isBlank())
                || (song.getGenre() != null && !"Unknown Genre".equals(song.getGenre()) && !song.getGenre().isBlank())
                || song.getBpm() > 0
                || (song.getReleaseDate() != null && !song.getReleaseDate().isBlank())
                || (song.getAlbum() != null && !"Unknown Album".equals(song.getAlbum()) && !song.getAlbum().isBlank());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the normalized "artist :: title" cache key, or null when either side is
     * blank or an "Unknown *" sentinel. Normalization matches how the enrichment APIs
     * are queried (artist + title only).
     */
    public String cacheKey(String artist, String title) {
        String a = normalizePart(artist);
        String t = normalizePart(title);
        if (a == null || t == null) {
            return null;
        }
        return a + " :: " + t;
    }

    private String normalizePart(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (v.isEmpty() || isSentinel(v)) {
            return null;
        }
        return v;
    }

    private boolean isSentinel(String normalized) {
        return normalized.equals("unknown")
                || normalized.equals("unknown artist")
                || normalized.equals("unknown title")
                || normalized.equals("unknown album")
                || normalized.equals("unknown genre")
                || normalized.equals("various artists");
    }

    /** True when a field is null, blank, or an "Unknown *" sentinel (i.e. worth filling). */
    private boolean isMissing(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return isSentinel(value.trim().toLowerCase(Locale.ROOT));
    }
}