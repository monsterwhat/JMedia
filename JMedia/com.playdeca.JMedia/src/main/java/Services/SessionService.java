package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SessionService {
    
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);
    private static final long SESSION_TIMEOUT_MINUTES = 30 * 24 * 60; // 30 days
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    public final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();
    
    public UserSession createSession(String userId, String username, String ipAddress) {
        String sessionId = UUID.randomUUID().toString();
        UserSession session = new UserSession();
        session.sessionId = sessionId;
        session.userId = userId;
        session.username = username;
        session.ipAddress = ipAddress;
        session.createdAt = Instant.now();
        session.lastActivity = Instant.now();
        session.setActive();
        
        sessions.put(sessionId, session);
        
        LOG.info("Created session {} for user {} from IP {}. Total sessions: {}", sessionId, username, ipAddress, sessions.size());
        
        return session;
    }
    
    public boolean validateSession(String sessionId, String ipAddress) {
        LOG.debug("Validating session {} from IP {}. Total sessions in memory: {}", sessionId, ipAddress, sessions.size());
        
        if (sessionId == null || sessionId.trim().isEmpty()) {
            LOG.debug("Session ID is null or empty");
            return false;
        }
        
        UserSession session = sessions.get(sessionId);
        
        if (session == null) {
            LOG.warn("Session {} not found in {} active sessions. Available sessions: {}", sessionId, sessions.size(), sessions.keySet());
            return false;
        }
        
        // Check if session is active first
        if (!session.active) {
            LOG.warn("Session {} is inactive for user {}", sessionId, session.username);
            return false;
        }
        
        // Check if session is expired
        if (session.lastActivity != null && 
                   session.lastActivity.plusSeconds(SESSION_TIMEOUT_MINUTES * 60).isBefore(Instant.now())) {
            LOG.info("Session {} expired for user {} (last activity: {})", sessionId, session.username, session.lastActivity);
            invalidateSession(sessionId);
            return false;
        }
        
        // Temporarily disable IP address validation for debugging
        LOG.debug("Session {} IP check: expected {}, got {}", sessionId, session.ipAddress, ipAddress);
        // IP address validation disabled temporarily
        
        // Update last activity
        session.lastActivity = Instant.now();
        session.setActive();
        
        LOG.debug("Session {} validated successfully for user {} from IP {}", sessionId, session.username, ipAddress);
        return true;
    }
    
    public void invalidateSession(String sessionId) {
        UserSession session = sessions.remove(sessionId);
        
        if (session != null) {
            LOG.info("Invalidated session {} for user {}", sessionId, session.username);
            session.active = false;
        } else {
            LOG.debug("Session {} not found for invalidation", sessionId);
        }
    }
    
    public void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minusSeconds(SESSION_TIMEOUT_MINUTES * 60);
        
        sessions.entrySet().removeIf(entry -> {
            UserSession session = entry.getValue();
            if (session.lastActivity.isBefore(cutoff)) {
                LOG.info("Cleaning up expired session {} for user {}", entry.getKey(), session.username);
                session.active = false;
                return true;
            }
            return false;
        });
    }
    
    public static class UserSession {
        public String sessionId;
        public String userId;
        public String username;
        public String ipAddress;
        public Instant createdAt;
        public Instant lastActivity;
        public boolean active;
        
        public boolean isExpired() {
            return lastActivity != null && 
                   lastActivity.plus(SESSION_TIMEOUT_MINUTES, java.time.temporal.ChronoUnit.MINUTES).isBefore(Instant.now());
        }
        
        public void setActive() {
            this.active = true;
        }
        
        public void setInactive() {
            this.active = false;
        }
    }
}