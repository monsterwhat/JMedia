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
    private final Set<Session> videoSessions = ConcurrentHashMap.newKeySet();

    private final Map<String, Long> sessionProfileMap = new ConcurrentHashMap<>();
    private final Map<Long, Set<Session>> profileSessionsMap = new ConcurrentHashMap<>();

    /** How often an open music socket is pinged to prove the client still exists. */
    private static final long PRESENCE_PING_EVERY_MS = 10_000L;
    /**
     * A socket that has not answered for this long is not a listener. Only
     * {@code isOpen()} used to decide, and that can stay true for hours after a
     * laptop sleeps or the network drops (no close frame, OS TCP keepalive default
     * is ~2h), so auto-pause never fired for the case that matters.
     */
    private static final long PRESENCE_STALE_MS = 30_000L;

    /** Last time each music session proved it is alive (open, pong or client message). */
    private final Map<String, Long> musicLastSeen = new ConcurrentHashMap<>();


  
    public void addSession(Session session, Long profileId) {
        sessionProfileMap.put(session.getId(), profileId);
        profileSessionsMap.computeIfAbsent(profileId, k -> ConcurrentHashMap.newKeySet()).add(session);
        // Also add to musicSessions for compatibility until music-specific broadcasts are fully removed
        musicSessions.add(session);
        musicLastSeen.put(session.getId(), System.currentTimeMillis());
    }

    public void removeSession(Session session) {
        String sessionId = session.getId();
        musicLastSeen.remove(sessionId);
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

    public void broadcastToVideo(String message) { // Added for video sessions
        broadcast(videoSessions, message);
    }

    public void broadcastToProfile(Long profileId, String message) {
        Set<Session> sessions = profileSessionsMap.get(profileId);
        if (sessions != null) {
            broadcast(sessions, message);
        }
    }

    /**
     * Broadcasts only to the profile's MUSIC sockets.
     *
     * <p>{@code profileSessionsMap} holds both endpoint types for a profile (music and
     * video), so the plain {@link #broadcastToProfile} delivered music state to video
     * clients as well. The video player applies any {@code state} message to its own
     * element, so a music pause ("playing": false, no currentVideoId) paused the video
     * — and vice versa. Keep the two channels apart.
     */
    public void broadcastToMusicProfile(Long profileId, String message) {
        broadcastToProfileType(profileId, musicSessions, message);
    }

    /** Broadcasts only to the profile's VIDEO sockets. */
    public void broadcastToVideoProfile(Long profileId, String message) {
        broadcastToProfileType(profileId, videoSessions, message);
    }

    private void broadcastToProfileType(Long profileId, Set<Session> typeSessions, String message) {
        Set<Session> sessions = profileSessionsMap.get(profileId);
        if (sessions == null) {
            return;
        }
        sessions.stream()
                .filter(typeSessions::contains)
                .forEach(session -> {
                    if (session.isOpen()) {
                        session.getAsyncRemote().sendText(message);
                    }
                });
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
        musicLastSeen.remove(sessionId);
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

    /**
     * Count open music WebSocket sessions for the given profile.
     * Side-effect free: does not mutate any internal state.
     *
     * <p>A socket that has stopped answering pings is not a listener: {@code isOpen()}
     * can stay true for hours after a laptop sleeps or Wi-Fi drops (no close frame,
     * OS keepalive defaults to ~2h), which is exactly the case auto-pause must catch.
     */
    public int getMusicSessionCount(Long profileId) {
        if (profileId == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int count = 0;
        for (Session session : musicSessions) {
            if (!session.isOpen()) {
                continue;
            }
            Long pid = sessionProfileMap.get(session.getId());
            if (!profileId.equals(pid)) {
                continue;
            }
            Long seen = musicLastSeen.get(session.getId());
            if (seen == null || now - seen > PRESENCE_STALE_MS) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** Records proof of life for a music session: a pong, or any client message. */
    public void markMusicSessionAlive(Session session) {
        if (session != null) {
            musicLastSeen.put(session.getId(), System.currentTimeMillis());
        }
    }

    /**
     * Presence heartbeat. Browsers answer a WebSocket ping at the protocol level, so a
     * pong proves the client is still there even when the page sends nothing. Clients
     * that do not pong are covered by the small "ping" message the music page sends.
     */
    @Scheduled(every = "10s")
    void pingMusicSessions() {
        for (Session session : musicSessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.getAsyncRemote().sendPing(java.nio.ByteBuffer.wrap(new byte[]{1}));
            } catch (Exception e) {
                LOG.debug("Presence ping failed for session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}