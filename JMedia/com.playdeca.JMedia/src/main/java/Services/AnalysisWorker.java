package Services;

import Controllers.PlaybackController;
import Models.Music.Song;
import Models.Music.SongAnalysis;
import Models.Settings.Settings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Background worker that processes pending audio analysis records (SongAnalysis with status=PENDING).
 * Runs every 10 seconds, processing a configurable batch of songs per tick
 * (analysisWorkerBatchSize setting, 1-10, default 2). Skips entirely when the
 * audio analysis pool is disabled in system settings (audioAnalysisThreads = -1).
 *
 * Skips processing while video transcoding is active to avoid CPU contention
 * (both audio analysis and transcoding use FFmpeg) — but only for a bounded
 * time, so a long-running video job can never starve DJ Mode analysis forever.
 *
 * Resilience: the previous tick guard is a timestamp-based watchdog rather than
 * a hard semaphore. If a tick hangs (e.g. the ffmpeg pipe spawned by
 * AudioDispatcherFactory.fromPipe never returns), the next tick detects it after
 * TICK_HANG_TIMEOUT_MS and takes over, so pending songs are still analyzed.
 */
@ApplicationScoped
public class AnalysisWorker {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    private static final int DEFAULT_BATCH_SIZE = 2;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 10;
    private static final long FAILED_RETRY_AFTER_MS = 300_000; // 5 minutes
    private static final long TICK_HANG_TIMEOUT_MS = 600_000;  // 10 minutes
    private static final long MAX_DEFER_MS = 120_000;          // 2 minutes

    // Watchdog state. tickStartedAt is 0 when idle, otherwise the epoch millis
    // the current tick began. Volatile because scheduler invocations may land on
    // different threads and a hung tick must be observable from the next tick.
    private volatile long tickStartedAt = 0;

    // Transcoding deferral: 0 = not currently deferring; otherwise the epoch
    // millis the current deferral window began.
    private volatile long deferStartedAt = 0;

    @Inject
    AudioAnalysisService audioAnalysisService;

    @Inject
    SettingsService settingsService;

    @Inject
    TranscodingService transcodingService;

    @Inject
    PlaybackController playbackController;

    @PersistenceContext(unitName = "music")
    EntityManager em;

    private int resolveBatchSize() {
        try {
            Settings s = settingsService.getSettingsOrNull();
            Integer configured = s != null ? s.getAnalysisWorkerBatchSize() : null;
            if (configured == null) {
                return DEFAULT_BATCH_SIZE;
            }
            return Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, configured));
        } catch (Exception e) {
            return DEFAULT_BATCH_SIZE;
        }
    }

    @Scheduled(every = "10s")
    void processPendingAnalyses() {
        long now = System.currentTimeMillis();

        // Watchdog: a tick that started too long ago and never cleared its marker
        // is hung. Take over its slot so pending analyses still get processed
        // instead of starving forever on a single stuck song.
        long started = tickStartedAt;
        if (started != 0 && now - started < TICK_HANG_TIMEOUT_MS) {
            LOG.debug("AnalysisWorker: previous tick still running, skipping");
            return;
        }
        if (started != 0) {
            LOG.error("AnalysisWorker: previous tick appears hung (started {} ms ago) - forcing a new tick", now - started);
        }
        tickStartedAt = now;

        try {
            if (!audioAnalysisService.isAnalysisEnabled()) {
                LOG.debug("AnalysisWorker: audio analysis disabled in system settings, skipping");
                return;
            }

            int batchSize = resolveBatchSize();

            // Skip while video transcoding is active to avoid FFmpeg CPU
            // contention — but only for a bounded window. If transcoding has been
            // running continuously for longer than MAX_DEFER_MS, process anyway
            // so DJ Mode analysis is never starved by background video work.
            if (transcodingService.isAnyTranscodingActive()) {
                if (deferStartedAt == 0) {
                    deferStartedAt = now;
                    LOG.info("AnalysisWorker: deferring — video transcoding in progress ({} active)",
                        transcodingService.getActiveTranscodeCount());
                }
                long deferringFor = now - deferStartedAt;
                if (deferringFor < MAX_DEFER_MS) {
                    LOG.debug("AnalysisWorker: still deferring for video transcoding ({} ms so far)", deferringFor);
                    return;
                }
                LOG.warn("AnalysisWorker: video transcoding active for {} ms — processing analysis anyway to avoid starving DJ Mode", deferringFor);
                deferStartedAt = 0; // re-arm the deferral window for the next tick
            } else {
                deferStartedAt = 0;
            }

            // Phase 1: Process PENDING records. Fetch only IDs first to avoid
            // eagerly loading each SongAnalysis row's Song FK (which carries the
            // Song entity with lyrics + artworkBase64 CLOBs and SongAnalysis JSON
            // blobs) into heap at once. We then process one record at a time.
            List<Long> pendingIds = em.createQuery(
                    "SELECT sa.id FROM SongAnalysis sa WHERE sa.status = :status ORDER BY sa.id", Long.class)
                    .setParameter("status", SongAnalysis.AnalysisStatus.PENDING)
                    .setMaxResults(batchSize)
                    .getResultList();

            for (Long analysisId : pendingIds) {
                processAnalysisById(analysisId, "PENDING");
            }

            // Phase 2: If nothing PENDING, retry FAILED records older than 5 minutes
            if (pendingIds.isEmpty()) {
                long cutoff = System.currentTimeMillis() - FAILED_RETRY_AFTER_MS;
                List<Long> failedIds = em.createQuery(
                        "SELECT sa.id FROM SongAnalysis sa WHERE sa.status = :status AND sa.analysisTimestamp < :cutoff ORDER BY sa.id", Long.class)
                        .setParameter("status", SongAnalysis.AnalysisStatus.FAILED)
                        .setParameter("cutoff", cutoff)
                        .setMaxResults(batchSize)
                        .getResultList();

                for (Long analysisId : failedIds) {
                    processAnalysisById(analysisId, "FAILED");
                }
            }

        } catch (org.hibernate.exception.JDBCConnectionException e) {
            // Transient: database file channel closed during compaction (e.g. after resetVideoDatabase).
            // H2 MVStore will recover on next connection cycle — skip this tick quietly.
            LOG.warn("AnalysisWorker: database temporarily unavailable, skipping tick: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("AnalysisWorker: unexpected error in processing tick", e);
        } finally {
            // Clear only our own marker. If a newer tick already took over (hung
            // tick recovery), leave its marker intact.
            if (tickStartedAt == now) {
                tickStartedAt = 0;
            }
        }
    }

    @Transactional
    public void processAnalysisById(Long analysisId, String phase) {
        // Load ONLY id + song.id via projection. SongAnalysis holds 3 large CLOB
        // JSON columns (segmentFeaturesJson, similarBeatsJson, beatMetadataJson,
        // each @Column(length=Integer.MAX_VALUE)) plus an eager @ElementCollection
        // of beat timestamps. Loading the full entity here blew the heap on the
        // 5.7GB music DB. analyzeSong writes a fresh SongAnalysis row, so we
        // never need to read the existing record's payload.
        Object[] row;
        try {
            row = (Object[]) em.createQuery(
                    "SELECT sa.id, sa.song.id FROM SongAnalysis sa WHERE sa.id = :id")
                    .setParameter("id", analysisId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException nre) {
            return;
        }
        Long saId = (Long) row[0];
        Long songId = (Long) row[1];
        if (songId == null) {
            LOG.warn("AnalysisWorker: {} record {} has no song, deleting", phase, saId);
            em.createQuery("DELETE FROM SongAnalysis sa WHERE sa.id = :id")
                    .setParameter("id", saId)
                    .executeUpdate();
            return;
        }
        Song song = em.getReference(Song.class, songId);
        LOG.info("AnalysisWorker: {} record {} for song id={}", phase, saId, songId);
        SongAnalysis result = audioAnalysisService.analyzeSong(song);
        if (result != null && result.getStatus() == SongAnalysis.AnalysisStatus.COMPLETED) {
            playbackController.replanDjTransitionsForAnalyzedSong(songId);
        }
    }
}
