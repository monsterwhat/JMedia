package Services;

import Models.Music.Song;
import Models.Music.SongAnalysis;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background service that enriches songs for DJ mode.
 *
 * Architecture — two independent queues with separate thread pools:
 *   metadataQueue  → 1 thread  → API calls (MusicBrainz/Deezer/Artwork) — rate-limited, serialized
 *   analysisQueue  → 6 threads → TarsosDSP audio analysis — parallel, CPU-bound, no API calls
 *
 * Songs flow:  queueSong() → metadataQueue → [metadata enrichment] → analysisQueue → [audio analysis] → done
 * Metadata enrichment never blocks audio analysis and vice versa.
 */
@ApplicationScoped
@Startup
public class DjEnrichmentService {

    private static final Logger LOG = LoggerFactory.getLogger(DjEnrichmentService.class);

    private static final long POLL_TIMEOUT_SECONDS = 5;
    private static final int METADATA_THREADS = 1;
    private static final int ANALYSIS_THREADS = 6;

    @Inject
    MusicEnrichmentService musicEnrichmentService;

    @Inject
    AudioAnalysisService audioAnalysisService;

    @Inject
    RequestContextController requestContextController;

    // --- Queues ---
    private final BlockingQueue<Long> metadataQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Long> analysisQueue = new LinkedBlockingQueue<>();

    // --- Thread pools (separate, independent) ---
    private ExecutorService metadataPool;
    private ExecutorService analysisPool;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // --- Progress counters ---
    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final AtomicInteger metadataDone = new AtomicInteger(0);
    private final AtomicInteger analysisDone = new AtomicInteger(0);

    @PostConstruct
    void init() {
        start();
        Thread startupThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            queueAllUnenriched();
        }, "DjEnrichmentService-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    @PreDestroy
    void destroy() {
        stop();
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            // Metadata pool — 1 thread, API calls are serialized
            metadataPool = Executors.newFixedThreadPool(METADATA_THREADS, r -> {
                Thread t = new Thread(r, "DjEnrichment-metadata");
                t.setDaemon(true);
                return t;
            });
            metadataPool.submit(this::processMetadataQueue);

            // Analysis pool — 6 threads, parallel CPU-bound audio analysis
            analysisPool = Executors.newFixedThreadPool(ANALYSIS_THREADS, r -> {
                Thread t = new Thread(r, "DjEnrichment-analysis");
                t.setDaemon(true);
                return t;
            });
            for (int i = 0; i < ANALYSIS_THREADS; i++) {
                analysisPool.submit(this::processAnalysisQueue);
            }

            LOG.info("DjEnrichmentService started — metadata={} thread, analysis={} threads",
                METADATA_THREADS, ANALYSIS_THREADS);
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            shutdownPool(metadataPool, "metadata");
            shutdownPool(analysisPool, "analysis");
            LOG.info("DjEnrichmentService stopped");
        }
    }

    private void shutdownPool(ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------------
    // Metadata queue worker  (1 thread — API calls)
    // ----------------------------------------------------------------

    private void processMetadataQueue() {
        LOG.info("DjEnrichmentService metadata queue worker started");

        while (isRunning.get()) {
            try {
                Long songId = metadataQueue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (songId == null) continue;

                pending.decrementAndGet();
                processMetadata(songId);

            } catch (InterruptedException e) {
                LOG.info("DjEnrichmentService metadata worker interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("DjEnrichmentService: unexpected error in metadata worker", e);
            }
        }

        LOG.info("DjEnrichmentService metadata queue worker stopped");
    }

    private void processMetadata(Long songId) {
        requestContextController.activate();
        try {
            Song song = Song.findById(songId);
            if (song == null) {
                LOG.debug("DjEnrichmentService: song {} not found, skipping metadata", songId);
                return;
            }

            String title = song.getTitle();
            String artist = song.getArtist();
            LOG.info("DjEnrichmentService [metadata]: '{}' by {} (id={}) — artwork={}, genre={}, bpm={}",
                title, artist, songId,
                song.getArtworkBase64() != null ? "present" : "missing",
                song.getGenre() != null ? song.getGenre() : "missing",
                song.getBpm() > 0 ? song.getBpm() : "missing");

            // Single-threaded — no lock needed, but sleep after each call for rate limiting
            musicEnrichmentService.enrichSong(song);

            LOG.info("DjEnrichmentService [metadata]: done for '{}' — artwork={}, genre={}, bpm={}",
                title,
                song.getArtworkBase64() != null ? "present" : "missing",
                song.getGenre() != null ? song.getGenre() : "missing",
                song.getBpm() > 0 ? song.getBpm() : "missing");

            metadataDone.incrementAndGet();

            // Hand off to analysis queue (analysis is independent of metadata)
            if (needsAnalysis(song)) {
                analysisQueue.offer(songId);
                LOG.debug("DjEnrichmentService [metadata]: '{}' queued for analysis", title);
            } else {
                // Already analyzed — mark as fully done
                processed.incrementAndGet();
                LOG.info("DjEnrichmentService: '{}' already analyzed, skipping analysis ({}/{} processed)",
                    title, processed.get(), processed.get() + failed.get());
            }

        } catch (Exception e) {
            failed.incrementAndGet();
            LOG.error("DjEnrichmentService: metadata enrichment failed for song id={}: {}", songId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    // ----------------------------------------------------------------
    // Analysis queue worker  (6 threads — parallel CPU work)
    // ----------------------------------------------------------------

    private void processAnalysisQueue() {
        LOG.info("DjEnrichmentService analysis queue worker started");

        while (isRunning.get()) {
            try {
                Long songId = analysisQueue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (songId == null) continue;

                processAnalysis(songId);

            } catch (InterruptedException e) {
                LOG.info("DjEnrichmentService analysis worker interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("DjEnrichmentService: unexpected error in analysis worker", e);
            }
        }

        LOG.info("DjEnrichmentService analysis queue worker stopped");
    }

    private void processAnalysis(Long songId) {
        requestContextController.activate();
        try {
            Song song = Song.findById(songId);
            if (song == null) {
                LOG.debug("DjEnrichmentService: song {} not found, skipping analysis", songId);
                return;
            }

            String title = song.getTitle();
            LOG.info("DjEnrichmentService [analysis]: '{}' — starting audio analysis", title);

            SongAnalysis result = audioAnalysisService.analyzeSongAsync(song).join();
            if (result != null) {
                if (result.getStatus() == SongAnalysis.AnalysisStatus.COMPLETED) {
                    analysisDone.incrementAndGet();
                    if (result.getBeatCount() != null && result.getBeatCount() > 0) {
                        LOG.info("DjEnrichmentService [analysis]: done for '{}' — {} beats, BPM: {}",
                            title, result.getBeatCount(), result.getAverageBpm());
                    } else {
                        LOG.info("DjEnrichmentService [analysis]: done for '{}' — no beats detected (non-percussive?)", title);
                    }
                } else if (result.getStatus() == SongAnalysis.AnalysisStatus.FAILED) {
                    LOG.warn("DjEnrichmentService [analysis]: failed for '{}': {}", title, result.getErrorMessage());
                }
            }

            processed.incrementAndGet();
            LOG.info("DjEnrichmentService: finished '{}' ({}/{} processed, {} failed)",
                title, processed.get(), processed.get() + failed.get(), failed.get());

        } catch (Exception e) {
            failed.incrementAndGet();
            LOG.error("DjEnrichmentService: analysis failed for song id={}: {}", songId, e.getMessage(), e);
        } finally {
            requestContextController.deactivate();
        }
    }

    // ----------------------------------------------------------------
    // Queue management
    // ----------------------------------------------------------------

    public void queueSong(Long songId) {
        if (songId == null) return;
        metadataQueue.offer(songId);
        pending.incrementAndGet();
        LOG.debug("DjEnrichmentService: queued song {} for metadata (metadata queue: {}, analysis queue: {})",
            songId, metadataQueue.size(), analysisQueue.size());
    }

    public void queueAllUnenriched() {
        LOG.info("DjEnrichmentService: scanning library for unenriched songs...");
        requestContextController.activate();
        try {
            List<Song> allSongs = Song.findAll().list();
            int metadataQueued = 0;
            int analysisQueued = 0;

            for (Song song : allSongs) {
                boolean needsMeta = needsMetadata(song);
                boolean needsAna  = needsAnalysis(song);

                if (!needsMeta && !needsAna) continue;

                if (needsMeta) {
                    // Metadata first — analysis will be queued after metadata completes
                    metadataQueue.offer(song.id);
                    metadataQueued++;
                } else if (needsAna) {
                    // Only analysis needed — go directly to analysis queue
                    analysisQueue.offer(song.id);
                    analysisQueued++;
                }
            }

            pending.addAndGet(metadataQueued + analysisQueued);

            LOG.info("DjEnrichmentService: queued {} songs — {} for metadata, {} for analysis (total library: {})",
                metadataQueued + analysisQueued, metadataQueued, analysisQueued, allSongs.size());
        } catch (Exception e) {
            LOG.error("DjEnrichmentService: failed to scan library for unenriched songs", e);
        } finally {
            requestContextController.deactivate();
        }
    }

    // ----------------------------------------------------------------
    // Needs checks (split: metadata vs analysis)
    // ----------------------------------------------------------------

    private boolean needsMetadata(Song song) {
        return song.getArtworkBase64() == null
            || song.getGenre() == null
            || song.getBpm() <= 0;
    }

    private boolean needsAnalysis(Song song) {
        SongAnalysis analysis = SongAnalysis.find("song.id", song.id).firstResult();

        if (analysis != null && analysis.getStatus() == SongAnalysis.AnalysisStatus.PROCESSING) {
            return false;
        }

        return analysis == null
            || analysis.getStatus() != SongAnalysis.AnalysisStatus.COMPLETED
            || analysis.getBeatCount() == null
            || analysis.getAverageBpm() == null;
    }

    // ----------------------------------------------------------------
    // Progress / status
    // ----------------------------------------------------------------

    public EnrichmentProgress getProgress() {
        return new EnrichmentProgress(
            pending.get(),
            processed.get(),
            failed.get(),
            metadataDone.get(),
            analysisDone.get(),
            metadataQueue.size(),
            analysisQueue.size(),
            isRunning.get()
        );
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getQueueSize() {
        return metadataQueue.size() + analysisQueue.size();
    }

    public record EnrichmentProgress(
        int pending,
        int processed,
        int failed,
        int metadataDone,
        int analysisDone,
        int metadataQueueSize,
        int analysisQueueSize,
        boolean running
    ) {}
}
