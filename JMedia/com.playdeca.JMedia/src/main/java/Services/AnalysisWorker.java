package Services;

import Controllers.PlaybackController;
import Models.Music.Song;
import Models.Music.SongAnalysis;
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
 * Runs every 10 seconds, processes up to 2 songs per tick.
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

    private static final int MAX_PER_TICK = 2;
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
    TranscodingService transcodingService;

    @Inject
    PlaybackController playbackController;

    @PersistenceContext(unitName = "music")
    EntityManager em;

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

            // Phase 1: Process PENDING records
            List<SongAnalysis> pending = SongAnalysis.find("status", SongAnalysis.AnalysisStatus.PENDING)
                .page(0, MAX_PER_TICK)
                .list();

            for (SongAnalysis sa : pending) {
                Song song = sa.getSong();
                if (song == null) {
                    LOG.warn("AnalysisWorker: PENDING record {} has no song, deleting", sa.id);
                    sa.delete();
                    continue;
                }
                LOG.info("AnalysisWorker: analyzing '{}' (id={})", song.getTitle(), song.id);
                SongAnalysis result = audioAnalysisService.analyzeSong(song);
                if (result != null && result.getStatus() == SongAnalysis.AnalysisStatus.COMPLETED) {
                    playbackController.replanDjTransitionsForAnalyzedSong(song.id);
                }
            }

            // Phase 2: If nothing PENDING, retry FAILED records older than 5 minutes
            if (pending.isEmpty()) {
                long cutoff = System.currentTimeMillis() - FAILED_RETRY_AFTER_MS;
                List<SongAnalysis> failed = SongAnalysis.find(
                    "status = ?1 AND analysisTimestamp < ?2",
                    SongAnalysis.AnalysisStatus.FAILED,
                    cutoff
                ).page(0, MAX_PER_TICK).list();

                for (SongAnalysis sa : failed) {
                    Song song = sa.getSong();
                    if (song == null) {
                        LOG.warn("AnalysisWorker: FAILED record {} has no song, deleting", sa.id);
                        sa.delete();
                        continue;
                    }
                    LOG.info("AnalysisWorker: retrying failed analysis for '{}' (id={})", song.getTitle(), song.id);
                    SongAnalysis result = audioAnalysisService.analyzeSong(song);
                    if (result != null && result.getStatus() == SongAnalysis.AnalysisStatus.COMPLETED) {
                        playbackController.replanDjTransitionsForAnalyzedSong(song.id);
                    }
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
}
