package Filters;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.UriInfo;
import Services.SessionService;
import Services.RateLimitService;
import Services.SettingsService;
import Services.UserService;
import Models.Settings.Settings;
import Utils.IpResolutionUtils;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class JMediaAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JMediaAuthFilter.class);
    private static final List<String> PUBLIC_ENDPOINTS = Arrays.asList(
            "/api/auth/login",
            "/api/auth/logout",
            // QR pairing: starting, polling and redeeming need no session.
            // POST /api/pairing/claim is intentionally NOT here — the phone
            // must be signed in to hand over its account.
            "/api/pairing/start",
            "/api/pairing/land/",
            "/api/pairing/redeem",
            "/player_api.php",
            "/xmltv.php",
            "/get.php",
            "/api/epg/"
    );

    private static final List<String> STATIC_RESOURCES = Arrays.asList(
            "/css/",
            "/js/",
            "/logo.png",
            "/manifest.json",
            "/login.html"
    );

    // Streaming endpoints that must work even without auth (video element can't handle 401 JSON)
    // Xtream /movie|/series|/live carry their own path credentials, enforced by XtreamStreamAPI.
    private static final List<String> STREAMING_ENDPOINTS = Arrays.asList(
            "/movie/",
            "/series/",
            "/live/",
            "/timeshift/",
            "/streaming/timeshift.php",
            "/art/",
            "/api/video/stream/",
            "/api/video/progress/",
            "/api/video/storyboard/",
            "/api/hls/master/",
            "/api/hls/playlist/",
            "/api/hls/media/"
    );

    /**
     * Reachable while an account still carries its bootstrap password: the
     * change-password flow itself, session status, logout, and the assets the
     * change-password page needs to render.
     */
    private static final List<String> PASSWORD_CHANGE_ALLOWED = Arrays.asList(
            "/api/auth/",
            "/change-password",
            "/login.html",
            "/css/",
            "/js/",
            "/logo.png",
            "/manifest.json"
    );

    @Inject
    SessionService sessionService;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    SettingsService settingsService;

    @Inject
    UserService userService;

    @Inject
    HttpServerRequest vertxRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        UriInfo uriInfo = requestContext.getUriInfo();
        String path = uriInfo.getRequestUri().getPath();
        String clientIp = IpResolutionUtils.getClientIp(requestContext, vertxRequest);

        LOG.debug("Processing request path: '{}'", path);

        // 1. Allow public endpoints, static resources, and streaming endpoints immediately
        if (isPublicEndpoint(path) || isStaticResource(path) || isStreamingEndpoint(path)) {
            LOG.debug("Path '{}' is public/streaming - allowing access", path);

            // Still check for block status on login endpoint
            if (path.equals("/api/auth/login") && rateLimitService.isBlocked(clientIp, null)) {
                requestContext.abortWith(Response.status(429)
                        .entity("{\"error\":\"Too many failed login attempts. IP temporarily blocked.\"}")
                        .type("application/json")
                        .build());
            }
            return;
        }

        // 2. Check for valid session
        String sessionId = getSessionCookie(requestContext);

        if (sessionId != null && isSessionValid(sessionId, clientIp)) {
            // A bootstrap password has to be replaced before anything else works.
            if (requiresPasswordChange(sessionId, path)) {
                LOG.info("Blocking '{}' for {} until the bootstrap password is changed", path, clientIp);
                if (path.startsWith("/api/")) {
                    requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                            .entity("{\"error\":\"Password change required\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}")
                            .type("application/json")
                            .build());
                } else {
                    requestContext.abortWith(Response.status(Response.Status.TEMPORARY_REDIRECT)
                            .header("Location", "/change-password.html")
                            .header("Cache-Control", "no-cache, no-store, must-revalidate")
                            .build());
                }
                return;
            }
            // Valid session - allow request immediately
            LOG.debug("Allowing authenticated request from {}", clientIp);
            return;
        }

        // Peer instances authenticate with a header, not a cookie. Keep this to
        // the two endpoints a peer actually calls: everything else under
        // /api/sync (settings, servers, trigger, logs) stays session-only, or a
        // peer key would be able to rotate its own key.
        boolean peerEndpoint = "/api/sync/exchange".equals(path) || "/api/sync/ping".equals(path);
        if (peerEndpoint) {
            String syncKey = requestContext.getHeaderString("X-JMedia-Sync-Key");
            Settings settings = settingsService.getSettingsOrNull();
            String stored = settings != null ? settings.getSyncApiKey() : null;
            if (stored != null && syncKey != null && syncKey.equals(stored)) {
                LOG.debug("Allowing sync API request with valid key from {}", clientIp);
                return;
            }
            LOG.info("Sync key rejected for '{}': header={} stored={} settingsId={}",
                    path,
                    syncKey == null ? "absent" : syncKey.length() + " chars",
                    stored == null ? "absent" : stored.length() + " chars",
                    settings == null ? "no settings row" : settings.id);
        }

        // 4. Handle unauthenticated requests
        LOG.info("Unauthenticated request from {} - path: '{}', sessionId: {}, redirecting to login", clientIp, path, sessionId);

        // For API endpoints, return 401
        if (path.startsWith("/api/")) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Authentication required\"}")
                    .type("application/json")
                    .build());
            return;
        }

        // For web pages, redirect to login
        String originalUrl = uriInfo.getRequestUri().toString();
        String loginUrl = "/login.html?redirect=" + java.net.URLEncoder.encode(originalUrl, java.nio.charset.StandardCharsets.UTF_8);

        LOG.info("🛑 Access Denied to '{}': Redirecting to login.html", path);

        requestContext.abortWith(Response.status(Response.Status.TEMPORARY_REDIRECT)
                .header("Location", loginUrl)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build());
    }

    private String getSessionCookie(ContainerRequestContext requestContext) {
        // Parse via JAX-RS standard cookie handling rather than splitting the raw
        // Cookie header on ';': browsers may send multiple cookies with the same
        // name comma-separated (RFC 2109), which the manual split mishandled and
        // produced a session id like "abc,JMEDIA_SESSION=abc" -> "not found".
        if (requestContext.getCookies() != null && requestContext.getCookies().containsKey("JMEDIA_SESSION")) {
            jakarta.ws.rs.core.Cookie c = requestContext.getCookies().get("JMEDIA_SESSION");
            if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        // Fallback to manual header parsing for environments where cookies aren't populated.
        String cookieHeader = requestContext.getHeaderString("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split("[;,]")) {
                String cookie = part.trim();
                if (cookie.startsWith("JMEDIA_SESSION=")) {
                    String val = cookie.substring("JMEDIA_SESSION=".length()).trim();
                    if (!val.isEmpty()) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private boolean isPublicEndpoint(String path) {
        return PUBLIC_ENDPOINTS.stream().anyMatch(publicEndpoint -> path.startsWith(publicEndpoint));
    }

    private boolean isStaticResource(String path) {
        return STATIC_RESOURCES.stream().anyMatch(resource -> path.startsWith(resource));
    }

    private boolean isStreamingEndpoint(String path) {
        return STREAMING_ENDPOINTS.stream().anyMatch(endpoint -> path.startsWith(endpoint));
    }

    private boolean isSessionValid(String sessionId, String clientIp) {
        if (sessionId == null) {
            return false;
        }

        // Check session exists in SessionService
        return sessionService.validateSession(sessionId, clientIp);
    }

    /**
     * True when the session's account still has to replace its bootstrap
     * password, and this path is not part of the change-password flow.
     */
    private boolean requiresPasswordChange(String sessionId, String path) {
        if (PASSWORD_CHANGE_ALLOWED.stream().anyMatch(path::startsWith)) {
            return false;
        }
        return userService.requiresPasswordChange(sessionId);
    }
}
