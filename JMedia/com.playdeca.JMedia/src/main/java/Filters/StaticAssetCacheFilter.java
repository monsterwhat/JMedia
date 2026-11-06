package Filters;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Forces revalidation of browser-cached static assets (JS/CSS/HTML) on every
 * request. Without this, Quarkus serves them with only a Last-Modified header
 * and no Cache-Control, so browsers (Firefox in particular) may keep stale
 * copies across deploys - which is exactly how a shipped subtitle fix can fail
 * to reach the browser. Revalidation (304) is cheap and guarantees the latest
 * bytes are used after every deploy.
 */
@WebFilter(urlPatterns = "/*")
public class StaticAssetCacheFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(StaticAssetCacheFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;
                String path = req.getRequestURI();
                if (isPlayerAsset(path)) {
                    res.setHeader("Cache-Control", "no-cache, must-revalidate");
                }
            }
        } catch (Exception e) {
            LOG.debug("Static asset cache filter error: {}", e.getMessage());
        }
        chain.doFilter(request, response);
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
