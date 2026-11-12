package Services;

import Models.Video.Video;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-off maintenance pass over the video library for durations that are the container
 * length rather than the playable length.
 *
 * <p>MKV files whose subtitle streams run past the picture report the subtitle length as
 * the container duration, so a 23:52 episode showed as 34:33, the progress bar drew ten
 * minutes of nothing, and playback stopped itself at the real end.
 *
 * <p>Runs in the background, one video at a time, and is safe to start twice (the second
 * call is refused while a pass is in progress). The scan itself is I/O heavy: each file
 * may need one demux pass to find its real end, so this is deliberately a maintenance
 * action rather than part of normal scanning.
 */
@ApplicationScoped
public class VideoDurationRepairService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoDurationRepairService.class);

    @Inject
    MediaAnalysisService mediaAnalysisService;

    @Inject
    TranscodingService transcodingService;

    /** Optional series filter: repairing one show takes minutes instead of hours. */
    private volatile String seriesFilter = null;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger repaired = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private volatile String state = "idle";
    private volatile long startedAt = 0;

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("state", state);
        s.put("processed", processed.get());
        s.put("repaired", repaired.get());
        s.put("total", total.get());
        s.put("startedAt", startedAt);
        return s;
    }

    /**
     * Starts a pass in the background.
     *
     * @param seriesTitle when set, only videos of that series are re-probed
     * @return false when a pass is already running
     */
    public boolean start(String seriesTitle) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        seriesFilter = (seriesTitle == null || seriesTitle.isBlank()) ? null : seriesTitle;
        processed.set(0);
        repaired.set(0);
        total.set(0);
        startedAt = System.currentTimeMillis();
        state = "running";
        Thread t = new Thread(this::runPass, "video-duration-repair");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void runPass() {
        try {
            final String series = seriesFilter;
            List<Long> ids = QuarkusTransaction.requiringNew().call(() -> {
                String query = "select v.id from Video v where v.duration > 0 and v.path is not null"
                        + (series != null ? " and v.seriesTitle = ?1" : "")
                        + " order by v.id";
                var q = series != null ? Video.find(query, series) : Video.find(query);
                return q.project(Long.class).list();
            });
            total.set(ids.size());
            LOG.info("Duration repair: checking {} videos{}", ids.size(),
                    series != null ? " for series '" + series + "'" : "");

            for (Long id : ids) {
                // Yield to playback: a transcode reading the same disks must not queue
                // behind this pass, and the pass is useless if it makes playback stutter.
                try {
                    if (transcodingService != null && transcodingService.getActiveTranscodeCount() > 0) {
                        Thread.sleep(2000);
                    } else {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    state = "interrupted";
                    return;
                }
                try {
                    if (mediaAnalysisService.repairDurationIfStretched(id)) {
                        repaired.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOG.warn("Duration repair failed for video {}: {}", id, e.getMessage());
                }
                int done = processed.incrementAndGet();
                if (done % 100 == 0) {
                    LOG.info("Duration repair progress: {}/{} ({} corrected)", done, total.get(), repaired.get());
                }
            }

            state = "done";
            LOG.info("Duration repair finished: {}/{} checked, {} corrected in {}s",
                    processed.get(), total.get(), repaired.get(),
                    (System.currentTimeMillis() - startedAt) / 1000);
        } catch (Throwable t) {
            state = "failed";
            LOG.error("Duration repair aborted: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }
}
