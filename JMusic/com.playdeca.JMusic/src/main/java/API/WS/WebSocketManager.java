package API.WS;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.Session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WebSocketManager {

    private final Set<Session> musicSessions = ConcurrentHashMap.newKeySet();
    private final Set<Session> logSessions = ConcurrentHashMap.newKeySet();

    public void addMusicSession(Session session) {
        musicSessions.add(session);
    }

    public void removeMusicSession(Session session) {
        musicSessions.remove(session);
    }
  
    public void addLogSession(Session session) {
        logSessions.add(session);
    }

    public void removeLogSession(Session session) {
        logSessions.remove(session);
    }

    public void broadcastToMusic(String message) {
        broadcast(musicSessions, message);
    }
 
    public void broadcastToLogs(String message) {
        broadcast(logSessions, message);
    }

    private void broadcast(Set<Session> sessions, String message) {
        sessions.forEach(session -> {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText(message);
            }
        });
    }
}
