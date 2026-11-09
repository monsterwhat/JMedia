package Services;

import Models.Music.PlaybackState;
import Models.Music.Song;
import Models.Music.SongGenre;
import Utils.GenreNormalizer;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time, idempotent backfill of {@link SongGenre} rows from each song's raw
 * genre tag. Runs on a daemon thread after startup in 200-row windows so a
 * large library never blocks boot or holds a long transaction.
 */
@ApplicationScoped
@Startup
public class MusicGenreBackfillService {

    private static final Logger LOG = LoggerFactory.getLogger(MusicGenreBackfillService.class);
    private static final long STARTUP_DELAY_MS = 20_000L;
    private static final int BATCH_SIZE = 200;

    @PersistenceContext(unitName = "music")
    EntityManager em;

    /** Proxy so REQUIRES_NEW and the request context apply on each batch. */
    @Inject
    MusicGenreBackfillService self;

    @PostConstruct
    void init() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            self.backfill();
        }, "MusicGenre-backfill");
        thread.setDaemon(true);
        thread.start();
    }

    public void backfill() {
        try {
            long missing = self.countMissing();
            if (missing == 0) {
                LOG.info("Music genre backfill skipped - every song already has genre links");
            } else {
                LOG.info("Music genre backfill: {} song(s) without genre links", missing);
                long lastId = 0L;
                int batches = 0;
                while (true) {
                    long nextId = self.backfillBatch(lastId);
                    if (nextId <= lastId) break;
                    lastId = nextId;
                    batches++;
                }
                LOG.info("Music genre backfill complete - {} batch(es), {} song(s) still missing",
                        batches, self.countMissing());
            }
            self.migrateDjPools();
        } catch (Exception e) {
            LOG.error("Music genre backfill failed (non-fatal, app continues)", e);
        }
    }

    /**
     * Re-resolve every song's genre links. Needed after the normalizer rules
     * change, because {@link #backfill()} only fills songs that have no links
     * at all. Idempotent: songs whose resolved keys already match are skipped
     * by {@link Song#syncGenresFromRaw()}.
     */
    public void rebuildAll() {
        try {
            LOG.info("Music genre rebuild started");
            long lastId = 0L;
            int batches = 0;
            while (true) {
                long nextId = self.backfillBatch(lastId);
                if (nextId <= lastId) break;
                lastId = nextId;
                batches++;
            }
            LOG.info("Music genre rebuild complete - {} batch(es), {} song(s) still missing",
                    batches, self.countMissing());
            self.migrateDjPools();
        } catch (Exception e) {
            LOG.error("Music genre rebuild failed", e);
        }
    }

    /**
     * Canonicalize stored DJ genre pools. Existing pools may contain raw labels
     * ("Hip Hop", "rock", "Rock/Pop"); resolve each to canonical names, dedupe,
     * and keep the first-seen order. Idempotent.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void migrateDjPools() {
        List<PlaybackState> states = PlaybackState.listAll();
        int changed = 0;
        for (PlaybackState state : states) {
            List<String> pool = state.getDjGenrePool();
            if (pool == null || pool.isEmpty()) continue;
            LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
            for (String raw : pool) {
                if (raw == null) continue;
                for (GenreNormalizer.Genre g : GenreNormalizer.resolveAll(raw)) {
                    canonical.putIfAbsent(g.key(), g.name());
                }
            }
            List<String> next = new ArrayList<>(canonical.values());
            if (!next.equals(pool)) {
                LOG.info("DJ pool migration profile={}: {} -> {}", state.id, pool, next);
                state.setDjGenrePool(next);
                changed++;
            }
        }
        LOG.info("DJ pool migration complete - {} profile(s) updated", changed);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long countMissing() {
        Long count = em.createQuery(
                "SELECT COUNT(s) FROM Song s WHERE s.id NOT IN (SELECT sg.song.id FROM SongGenre sg)",
                Long.class).getSingleResult();
        return count != null ? count : 0L;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long backfillBatch(long lastId) {
        java.util.List<Song> batch = Song.find("id > ?1 order by id", lastId)
                .page(0, BATCH_SIZE)
                .list();
        if (batch.isEmpty()) return lastId;
        long maxId = lastId;
        for (Song song : batch) {
            maxId = Math.max(maxId, song.id);
            song.syncGenresFromRaw();
        }
        em.flush();
        em.clear();
        return maxId;
    }
}
