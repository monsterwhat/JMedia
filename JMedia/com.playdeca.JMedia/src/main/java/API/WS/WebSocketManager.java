package API.WS;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.Session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WebSocketManager {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketManager.class);

    private final Set<Session> musicSessions = ConcurrentHashMap.newKeySet();
    private final Set<Session> logSessions = ConcurrentHashMap.newKeySet();
    private final Set<Session> videoSessions = ConcurrentHashMap.newKeySet();

    private final Map<String, Long> sessionProfileMap = new ConcurrentHashMap<>();
    private final Map<Long, Set<Session>> profileSessionsMap = new ConcurrentHashMap<>();


  
    public void addSession(Session session, Long profileId) {
        sessionProfileMap.put(session.getId(), profileId);
        profileSessionsMap.computeIfAbsent(profileId, k -> ConcurrentHashMap.newKeySet()).add(session);
        // Also add to musicSessions for compatibility until music-specific broadcasts are fully removed
        musicSessions.add(session);
    }

    public void removeSession(Session session) {
        String sessionId = session.getId();
        Long profileId = sessionProfileMap.remove(sessionId);
        if (profileId != null) {
            Set<Session> sessions = profileSessionsMap.get(profileId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    profileSessionsMap.remove(profileId);
                }
            }
        }
        musicSessions.remove(session); // Remove from musicSessions as well
    }

    public Long getProfileIdForSession(String sessionId) {
        return sessionProfileMap.get(sessionId);
    }

    public void setSessionProfile(Session session, Long newProfileId) {
        String sessionId = session.getId();
        Long oldProfileId = sessionProfileMap.put(sessionId, newProfileId);

        // Remove from old profile's set
        if (oldProfileId != null && !oldProfileId.equals(newProfileId)) {
            Set<Session> oldSessions = profileSessionsMap.get(oldProfileId);
            if (oldSessions != null) {
                oldSessions.remove(session);
                if (oldSessions.isEmpty()) {
                    profileSessionsMap.remove(oldProfileId);
                }
            }
        }
        // Add to new profile's set
        profileSessionsMap.computeIfAbsent(newProfileId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public Set<Long> getAllActiveProfileIds() {
        return profileSessionsMap.keySet().stream().collect(Collectors.toSet());
    }

    public void addLogSession(Session session) {
        logSessions.add(session);
    }

    public void removeLogSession(Session session) {
        logSessions.remove(session);
    }

    public void addVideoSession(Session session) {
        videoSessions.add(session);
    }

    public void addVideoSession(Session session, Long profileId) {
        videoSessions.add(session);
        if (profileId != null) {
            sessionProfileMap.put(session.getId(), profileId);
            profileSessionsMap.computeIfAbsent(profileId, k -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    public void removeVideoSession(Session session) {
        videoSessions.remove(session);
        String sessionId = session.getId();
        Long profileId = sessionProfileMap.remove(sessionId);
        if (profileId != null) {
            Set<Session> sessions = profileSessionsMap.get(profileId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    profileSessionsMap.remove(profileId);
                }
            }
        }
    }

    public void broadcastToMusic(String message) {
        broadcast(musicSessions, message);
    }
 
    public void broadcastToLogs(String message) {
        broadcast(logSessions, message);
    }

    public void broadcastToVideo(String message) { // Added for video sessions
        broadcast(videoSessions, message);
    }

    public void broadcastToProfile(Long profileId, String message) {
        Set<Session> sessions = profileSessionsMap.get(profileId);
        if (sessions != null) {
            broadcast(sessions, message);
        }
    }

    private void broadcast(Set<Session> sessions, String message) {
        sessions.forEach(session -> {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText(message);
            }
        });
    }

    /**
     * Periodically evict stale WebSocket sessions (closed or errored)
     * from all internal data structures to prevent unbounded growth.
     * Catches edge cases where @OnClose or @OnError were not invoked.
     */
    @Scheduled(every = "60s")
    void cleanupStaleSessions() {
        int removed = 0;
        removed += cleanupSessionSet(musicSessions);
        removed += cleanupSessionSet(logSessions);
        removed += cleanupSessionSet(videoSessions);
        if (removed > 0) {
            LOG.info("Cleaned up {} stale WebSocket session(s)", removed);
        }
    }

    private int cleanupSessionSet(Set<Session> sessionSet) {
        int[] count = {0};
        sessionSet.removeIf(session -> {
            if (!session.isOpen()) {
                removeSessionData(session);
                count[0]++;
                return true;
            }
            return false;
        });
        return count[0];
    }

    private void removeSessionData(Session session) {
        String sessionId = session.getId();
        Long profileId = sessionProfileMap.remove(sessionId);
        if (profileId != null) {
            Set<Session> sessions = profileSessionsMap.get(profileId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    profileSessionsMap.remove(profileId);
                }
            }
        }
    }
}