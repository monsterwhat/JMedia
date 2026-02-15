package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import Models.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SessionService {
    
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);
    private static final long SESSION_TIMEOUT_MINUTES = 30 * 24 * 60;
    
    @Transactional
    public Session createSession(String userId, String username, String ipAddress) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session();
        session.sessionId = sessionId;
        session.userId = userId;
        session.username = username;
        session.ipAddress = ipAddress;
        session.createdAt = Instant.now();
        session.lastActivity = Instant.now();
        session.active = true;
        
        session.persist();
        
        LOG.info("Created session {} for user {} from IP {}", sessionId, username, ipAddress);
        
        return session;
    }
    
    public boolean validateSession(String sessionId, String ipAddress) {
        LOG.debug("Validating session {} from IP {}", sessionId, ipAddress);
        
        if (sessionId == null || sessionId.trim().isEmpty()) {
            LOG.debug("Session ID is null or empty");
            return false;
        }
        
        Session session = Session.findBySessionId(sessionId);
        
        if (session == null) {
            LOG.warn("Session {} not found", sessionId);
            return false;
        }
        
        if (!session.active) {
            LOG.warn("Session {} is inactive for user {}", sessionId, session.username);
            return false;
        }
        
        if (session.lastActivity != null && 
                   session.lastActivity.plusSeconds(SESSION_TIMEOUT_MINUTES * 60).isBefore(Instant.now())) {
            LOG.info("Session {} expired for user {} (last activity: {})", sessionId, session.username, session.lastActivity);
            invalidateSession(sessionId);
            return false;
        }
        
        LOG.debug("Session {} IP check: expected {}, got {}", sessionId, session.ipAddress, ipAddress);
        
        session.lastActivity = Instant.now();
        session.active = true;
        
        LOG.debug("Session {} validated successfully for user {} from IP {}", sessionId, session.username, ipAddress);
        return true;
    }
    
    @Transactional
    public void invalidateSession(String sessionId) {
        Session session = Session.findBySessionId(sessionId);
        
        if (session != null) {
            LOG.info("Invalidated session {} for user {}", sessionId, session.username);
            session.active = false;
            session.delete();
        } else {
            LOG.debug("Session {} not found for invalidation", sessionId);
        }
    }
    
    @Transactional
    public void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minusSeconds(SESSION_TIMEOUT_MINUTES * 60);
        
        long deleted = Session.delete("lastActivity < ?1 and active = true", cutoff);
        if (deleted > 0) {
            LOG.info("Cleaned up {} expired sessions", deleted);
        }
    }
}
