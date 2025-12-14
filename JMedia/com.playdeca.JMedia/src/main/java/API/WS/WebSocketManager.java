package API.WS;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.Session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class WebSocketManager {

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

    public void addVideoSession(Session session) { // Added for video sessions
        videoSessions.add(session);
    }

    public void removeVideoSession(Session session) { // Added for video sessions
        videoSessions.remove(session);
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
}