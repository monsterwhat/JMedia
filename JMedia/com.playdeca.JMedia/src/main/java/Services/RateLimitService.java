package Services;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RateLimitService {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitService.class);
    
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int ATTEMPT_WINDOW_MINUTES = 15;
    private static final int BLOCK_DURATION_MINUTES = 30;

    // Use a ConcurrentHashMap with manual expiration for simple implementation
    // In a full production app, use Caffeine via @CacheResult or Redis
    private final ConcurrentHashMap<String, FailedLoginRecord> failedAttempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String ip, String username) {
        String key = getRateLimitKey(ip, username);
        FailedLoginRecord record = failedAttempts.get(key);
        
        if (record == null) return false;
        
        if (record.blockedUntil != null && record.blockedUntil.isAfter(Instant.now())) {
            LOG.warn("Rate limit check: User '{}' from IP {} is blocked until {}", username, ip, record.blockedUntil);
            return true;
        }
        
        // Clean up old record if block expired
        if (record.blockedUntil != null) {
            failedAttempts.remove(key);
            return false;
        }
        
        return false;
    }

    public void recordFailedAttempt(String ip, String username) {
        String key = getRateLimitKey(ip, username);
        FailedLoginRecord record = failedAttempts.computeIfAbsent(key, k -> new FailedLoginRecord());
        
        Instant now = Instant.now();
        record.attempts.add(now);
        
        // Remove attempts outside the window
        record.attempts.removeIf(t -> t.isBefore(now.minus(ATTEMPT_WINDOW_MINUTES, ChronoUnit.MINUTES)));
        
        if (record.attempts.size() >= MAX_FAILED_ATTEMPTS) {
            record.blockedUntil = now.plus(BLOCK_DURATION_MINUTES, ChronoUnit.MINUTES);
            LOG.error("SECURITY: IP {} blocked for {} minutes after {} failed attempts for user '{}'", 
                      ip, BLOCK_DURATION_MINUTES, record.attempts.size(), username);
        }
    }

    public void recordSuccessfulLogin(String ip, String username) {
        failedAttempts.remove(getRateLimitKey(ip, username));
        // Also clear IP-only block if any
        failedAttempts.remove(getRateLimitKey(ip, null));
    }

    private String getRateLimitKey(String ip, String username) {
        return (ip != null ? ip : "unknown") + ":" + (username != null ? username : "anonymous");
    }

    private static class FailedLoginRecord {
        public Instant blockedUntil;
        public final List<Instant> attempts = new ArrayList<>();
    }
    
    // Simple periodic cleanup could be added with @Scheduled
}
