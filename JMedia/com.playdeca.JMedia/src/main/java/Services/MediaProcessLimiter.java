package Services;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many background media processes (ffprobe scans, audio analysis decodes,
 * enrichment) may run at once, separately from the streaming transcode permits.
 *
 * <p>The point is priority, not just arithmetic: streaming transcodes own their own
 * semaphore in {@link TranscodingService}, so a scan or an analysis can never hold a
 * slot a viewer needs. Background work is capped at {@link #BACKGROUND_PERMITS} on top
 * of that, which bounds the total number of ffmpeg/ffprobe children the service cgroup
 * can spawn — the failure mode that took the host down was four pools each running at
 * its own private limit and summing to ~7 GB.
 */
@ApplicationScoped
public class MediaProcessLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(MediaProcessLimiter.class);

    /** Background children allowed alongside the streaming transcode pool. */
    private static final int BACKGROUND_PERMITS = 2;

    /** How long a background task waits for a slot before giving up and running anyway. */
    private static final long ACQUIRE_TIMEOUT_SECONDS = 120;

    private final Semaphore background = new Semaphore(BACKGROUND_PERMITS, true);

    /**
     * Blocks until a background slot is free.
     *
     * @return true when a permit was taken and must be released, false when the caller
     *         proceeded without one (timeout or interrupt) and must not release.
     */
    public boolean acquireBackground(String what) {
        try {
            if (background.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            LOG.warn("No background media slot free after {}s; running '{}' anyway",
                    ACQUIRE_TIMEOUT_SECONDS, what);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for a background media slot for '{}'", what);
            return false;
        }
    }

    public void releaseBackground() {
        background.release();
    }

    /** Slots currently in use, for diagnostics. */
    public int backgroundInUse() {
        return BACKGROUND_PERMITS - background.availablePermits();
    }
}
