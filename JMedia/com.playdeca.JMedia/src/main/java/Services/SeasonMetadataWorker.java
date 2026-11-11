package Services;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes a small batch of stale TMDb season snapshots every 30 minutes.
 * Seasons that are finished are only checked again after 90 days, so this
 * usually does nothing; it exists so airing seasons pick up new episodes
 * without any page view paying for a TMDb call.
 */
@ApplicationScoped
public class SeasonMetadataWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SeasonMetadataWorker.class);

    @Inject
    TmdbCatalogService catalogService;

    @Scheduled(every = "30m")
    void refreshStaleSeasons() {
        try {
            int refreshed = catalogService.refreshDue(8);
            if (refreshed > 0) {
                LOG.info("[SeasonCache] refreshed {} stale season(s)", refreshed);
            }
        } catch (RuntimeException e) {
            LOG.warn("[SeasonCache] scheduled refresh failed: {}", e.getMessage());
        }
    }
}
