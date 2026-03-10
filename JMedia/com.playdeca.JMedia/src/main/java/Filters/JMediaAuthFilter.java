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
            "/api/auth/logout"
    );

    private static final List<String> STATIC_RESOURCES = Arrays.asList(
            "/css/",
            "/js/",
            "/logo.png",
            "/manifest.json",
            "/login.html"
    );

    @Inject
    SessionService sessionService;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    HttpServerRequest vertxRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        UriInfo uriInfo = requestContext.getUriInfo();
        String path = uriInfo.getRequestUri().getPath();
        String clientIp = IpResolutionUtils.getClientIp(requestContext, vertxRequest);

        LOG.debug("Processing request path: '{}'", path);

        // 1. Allow public endpoints and static resources immediately
        if (isPublicEndpoint(path) || isStaticResource(path)) {
            LOG.debug("Path '{}' is public - allowing access", path);

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
            // Valid session - allow request immediately
            LOG.debug("Allowing authenticated request from {}", clientIp);
            return;
        }

        // 4. Handle unauthenticated requests
        LOG.info("Unauthenticated request from {} - sessionId: {}, redirecting to login", clientIp, sessionId);

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
        String cookieHeader = requestContext.getHeaderString("Cookie");
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                cookie = cookie.trim();
                if (cookie.startsWith("JMEDIA_SESSION=")) {
                    return cookie.substring("JMEDIA_SESSION=".length());
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

    private boolean isSessionValid(String sessionId, String clientIp) {
        if (sessionId == null) {
            return false;
        }

        // Check session exists in SessionService
        return sessionService.validateSession(sessionId, clientIp);
    }
}
