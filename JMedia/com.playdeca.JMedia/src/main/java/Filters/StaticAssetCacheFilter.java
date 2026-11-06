package Filters;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forces revalidation of browser-cached static assets (JS/CSS/HTML) on every
 * request. Without this, Quarkus serves them with only a Last-Modified header
 * and no Cache-Control, so browsers (Firefox in particular) may keep stale
 * copies across deploys — which is exactly how a shipped subtitle fix can fail
 * to reach the browser. Revalidation (304) is cheap and guarantees the latest
 * bytes are used after every deploy.
 */
public class StaticAssetCacheFilter {

    private static final Logger LOG = LoggerFactory.getLogger(StaticAssetCacheFilter.class);

    @Inject
    Filters filters;

    void onStart(@Observes StartupEvent ev) {
        filters.register(ctx -> {
            try {
                String path = ctx.normalizedPath();
                if (isPlayerAsset(path)) {
                    ctx.response().headers().set("Cache-Control", "no-cache, must-revalidate");
                }
            } catch (Exception e) {
                LOG.debug("Static asset cache filter error on {}: {}", ctx.normalizedPath(), e.getMessage());
            } finally {
                ctx.next();
            }
        }, 1000);
        LOG.info("Registered static asset cache-control filter (no-cache revalidation for JS/CSS/HTML)");
    }

    private static boolean isPlayerAsset(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        if (lower.startsWith("/js/") || lower.startsWith("/css/") || lower.startsWith("/views/")) {
            return true;
        }
        return lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html");
    }
}