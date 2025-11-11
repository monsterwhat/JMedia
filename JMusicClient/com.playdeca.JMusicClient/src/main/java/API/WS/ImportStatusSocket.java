package API.WS;

import Controllers.ImportController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws/import-status")
@ApplicationScoped
public class ImportStatusSocket {

    Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    ImportController importController;

    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), session);
        System.out.println("[INFO] ImportStatusSocket: Session opened: " + session.getId());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());
        System.out.println("[INFO] ImportStatusSocket: Session closed: " + session.getId());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        sessions.remove(session.getId());
        System.err.println("[ERROR] ImportStatusSocket: Error on session " + session.getId() + ": " + throwable.getMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("[INFO] ImportStatusSocket: Received message from session " + session.getId() + ": " + message);
        try {
            if (message.contains("\"type\":\"start-import\"")) {
                String url = extractJsonValue(message, "url");
                String format = extractJsonValue(message, "format");
                Integer downloadThreads = Integer.parseInt(extractJsonValue(message, "downloadThreads"));
                Integer searchThreads = Integer.parseInt(extractJsonValue(message, "searchThreads"));
                String downloadPath = extractJsonValue(message, "downloadPath");

                new Thread(() -> {
                    try {
                        importController.download(url, format, downloadThreads, searchThreads, downloadPath, session.getId());
                    } catch (Exception e) {
                        System.err.println("[ERROR] Error during import process for session " + session.getId() + ": " + e.getMessage());
                        sendToSession(session.getId(), "ERROR: " + e.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Error parsing message or triggering import for session " + session.getId() + ": " + e.getMessage());
            sendToSession(session.getId(), "ERROR: Failed to process request: " + e.getMessage());
        }
    }

    public void broadcast(String message) {
        sessions.values().forEach(session -> {
            session.getAsyncRemote().sendText(message, result -> {
                if (result.getException() != null) {
                    System.err.println("[ERROR] ImportStatusSocket: Unable to send message to session " + session.getId() + ": " + result.getException().getMessage());
                }
            });
        });
    }

    public void sendToSession(String sessionId, String message) {
        Session session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendText(message, result -> {
                if (result.getException() != null) {
                    System.err.println("[ERROR] ImportStatusSocket: Unable to send message to session " + sessionId + ": " + result.getException().getMessage());
                }
            });
        } else {
            System.err.println("[ERROR] ImportStatusSocket: Session " + sessionId + " not found or not open. Cannot send message.");
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex != -1) {
            startIndex += searchKey.length();
            int endIndex = json.indexOf("\"", startIndex);
            if (endIndex != -1) {
                return json.substring(startIndex, endIndex);
            }
        }
        searchKey = "\"" + key + "\":"; // For numbers
        startIndex = json.indexOf(searchKey);
        if (startIndex != -1) {
            startIndex += searchKey.length();
            int endIndex = json.indexOf(",", startIndex);
            if (endIndex == -1) { // Last element
                endIndex = json.indexOf("}", startIndex);
            }
            if (endIndex != -1) {
                return json.substring(startIndex, endIndex);
            }
        }
        return null;
    }
}
