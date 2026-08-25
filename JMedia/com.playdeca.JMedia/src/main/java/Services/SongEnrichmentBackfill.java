package Services;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fires the one-time {@link SongEnrichmentService#backfillFromLibrary()} migration on a
 * daemon thread shortly after startup. Deliberately non-blocking: it never runs inside a
 * {@code StartupEvent} observer or {@code @PostConstruct} body, so a slow or failing
 * first run can never prevent the app from booting on the live server.
 */
@ApplicationScoped
@Startup
public class SongEnrichmentBackfill {

    private static final long STARTUP_DELAY_MS = 30_000;

    @Inject
    SongEnrichmentService songEnrichmentService;

    @PostConstruct
    void init() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            songEnrichmentService.backfillFromLibrary();
        }, "SongEnrichment-backfill");
        thread.setDaemon(true);
        thread.start();
    }
}