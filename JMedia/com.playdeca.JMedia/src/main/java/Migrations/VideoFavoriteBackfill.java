package Migrations;

import Services.VideoStateService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time backfill from the legacy global Video.favorite column into
 * per-profile VideoState rows.
 *
 * Before this change the watchlist was a single boolean on the shared Video
 * entity, so every profile saw the same list. The backfill copies each
 * favorited video into a favorite VideoState row for every profile, preserving
 * the original favoritedAt timestamp for watchlist ordering. That reproduces
 * exactly what everyone sees today, after which the toggle and read paths use
 * only the per-profile rows.
 *
 * Idempotent and self-terminating: rows that are already favorited are skipped,
 * so reruns (and fresh installs, where the source query is empty) are no-ops.
 * See VideoStateService.backfillFavoritesFromLegacyColumn().
 */
@ApplicationScoped
public class VideoFavoriteBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(VideoFavoriteBackfill.class);

    @Inject
    VideoStateService videoStateService;

    void onStart(@Observes StartupEvent event) {
        try {
            int filled = videoStateService.backfillFavoritesFromLegacyColumn();
            if (filled > 0) {
                LOG.info("[VideoFavoriteBackfill] Marked {} VideoState row(s) favorite from legacy column", filled);
            }
        } catch (Exception e) {
            LOG.error("[VideoFavoriteBackfill] Backfill failed unexpectedly", e);
        }
    }
}
