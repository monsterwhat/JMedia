package Services;

import Controllers.PlaybackController;
import Models.Song;
import Models.SongAnalysis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Background worker that processes pending audio analysis records (SongAnalysis with status=PENDING).
 * Runs every 10 seconds, processes up to 2 songs per tick.
 * 
 * Skips processing when video transcoding is active to avoid CPU contention
 * (both audio analysis and transcoding use FFmpeg).
 */
@ApplicationScoped
public class AnalysisWorker {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    private static final int MAX_PER_TICK = 2;
    private static final long FAILED_RETRY_AFTER_MS = 300_000; // 5 minutes

    private final Semaphore guard = new Semaphore(1);

    @Inject
    AudioAnalysisService audioAnalysisService;

    @Inject
    TranscodingService transcodingService;

    @Inject
    PlaybackController playbackController;

    @PersistenceContext
    EntityManager em;

    @Scheduled(every = "10s")
    void processPendingAnalyses() {
        if (!guard.tryAcquire()) {
            LOG.debug("AnalysisWorker: previous tick still running, skipping");
            return;
        }
        try {
            // Skip if video transcoding is active — avoid FFmpeg CPU contention
            if (transcodingService.isAnyTranscodingActive()) {
                LOG.debug("AnalysisWorker: deferring — video transcoding in progress ({} active)",
                    transcodingService.getActiveTranscodeCount());
                return;
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

        } catch (Exception e) {
            LOG.error("AnalysisWorker: unexpected error in processing tick", e);
        } finally {
            guard.release();
        }
    }
}
