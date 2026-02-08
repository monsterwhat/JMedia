package Filters;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.UriInfo;
import Services.SessionService;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
    
    // Rate limiting configuration for failed login attempts only
    private static final int MAX_FAILED_ATTEMPTS = 15;
    private static final int ATTEMPT_WINDOW_MINUTES = 5;
    private static final long BLOCK_DURATION_MINUTES = 15;
    
    // Failed login attempts tracking by IP+Username combination
    private final ConcurrentHashMap<String, FailedLoginRecord> failedAttempts = new ConcurrentHashMap<>();
    
    @Inject
    SessionService sessionService;
    
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        UriInfo uriInfo = requestContext.getUriInfo();
        String path = uriInfo.getRequestUri().getPath();
        String clientIp = getClientIpAddress(requestContext);
        
        LOG.debug("Processing request path: '{}'", path);
        
        // 1. Allow public endpoints and static resources immediately
        if (isPublicEndpoint(path) || isStaticResource(path)) {
            LOG.debug("Path '{}' is public - allowing access", path);
            return;
        }
        
        // 2. Check for valid session (authenticated users bypass rate limiting)
        String sessionId = getSessionCookie(requestContext);
        LOG.debug("Session cookie check - sessionId: {}, clientIp: {}", sessionId, clientIp);
        
        if (sessionId != null && isSessionValid(sessionId, clientIp)) {
            // Valid session - allow request immediately (no rate limiting for authenticated users)
            LOG.debug("Allowing authenticated request from {}", clientIp);
            return;
        }
        
        // 3. Rate limiting only for unauthenticated requests to login endpoint
        if (path.equals("/api/auth/login") && isRateLimitedForLogin(clientIp, requestContext)) {
            LOG.warn("Rate limiting login attempt from IP: {}", clientIp);
            requestContext.abortWith(Response.status(429)
                    .entity("{\"error\":\"Too many failed login attempts. Try again later.\"}")
                    .type("application/json")
                    .build());
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
        LOG.debug("Redirecting to login URL: {}", loginUrl);
        
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
    
    private boolean isRateLimitedForLogin(String clientIp, ContainerRequestContext requestContext) {
        // Extract username from login request if possible
        String username = extractUsernameFromLoginRequest(requestContext);
        String rateLimitKey = clientIp + ":" + (username != null ? username : "unknown");
        
        FailedLoginRecord record = failedAttempts.computeIfAbsent(rateLimitKey, k -> new FailedLoginRecord());
        
        // Check if blocked
        if (record.blockedUntil != null && record.blockedUntil.isAfter(Instant.now())) {
            LOG.debug("Login from {} for user '{}' is still blocked until {}", clientIp, username, record.blockedUntil);
            return true;
        }
        
        // Check attempts in window
        long recentAttempts = record.attempts.stream()
                .filter(attempt -> attempt.isAfter(Instant.now().minus(ATTEMPT_WINDOW_MINUTES, java.time.temporal.ChronoUnit.MINUTES)))
                .count();
        
        if (recentAttempts >= MAX_FAILED_ATTEMPTS) {
            record.attempts.add(Instant.now()); // Add current attempt timestamp
            record.blockedUntil = Instant.now().plus(BLOCK_DURATION_MINUTES, java.time.temporal.ChronoUnit.MINUTES);
            failedAttempts.put(rateLimitKey, record);
            
            LOG.warn("Rate limiting login from IP {} for user '{}' - {} attempts in last {} minutes", 
                      clientIp, username, recentAttempts, ATTEMPT_WINDOW_MINUTES);
            return true;
        }
        
        // Record failed attempt
        record.attempts.add(Instant.now());
        record.lastAttempt = Instant.now();
        failedAttempts.put(rateLimitKey, record);
        
        return false;
    }
    
    private String extractUsernameFromLoginRequest(ContainerRequestContext requestContext) {
        // Try to extract username from request body for login endpoint
        // This is a best effort approach - may not always be possible since we can't consume the entity
        try {
            // We can't consume the entity here as it would break the downstream processing
            // For now, we'll rely on IP-based tracking primarily
            LOG.debug("Using IP-based rate limiting for login requests (username extraction skipped to preserve request body)");
        } catch (Exception e) {
            LOG.debug("Error during username extraction attempt: {}", e.getMessage());
        }
        return null;
    }
    

    
    private boolean isSessionValid(String sessionId, String clientIp) {
        if (sessionId == null) {
            return false;
        }
        
        // Check session exists in SessionService (would validate against database)
        return sessionService.validateSession(sessionId, clientIp);
    }
    
    private String getClientIpAddress(ContainerRequestContext requestContext) {
        String xForwardedFor = requestContext.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take first IP from X-Forwarded-For header
            return xForwardedFor.split(",")[0].trim();
        }
        
        // Check other headers
        String xRealIp = requestContext.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        // Fallback to localhost for development
        return "127.0.0.1";
    }
    
    private static class FailedLoginRecord {
        public Instant blockedUntil;
        public Instant lastAttempt;
        public List<Instant> attempts;
        
        public FailedLoginRecord() {
            this.lastAttempt = Instant.now();
            this.attempts = new java.util.ArrayList<>();
        }
    }
}