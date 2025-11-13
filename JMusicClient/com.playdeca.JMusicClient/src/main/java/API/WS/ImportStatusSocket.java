package API.WS;

import Controllers.ImportController;
import Models.DTOs.ImportInstallationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws/import-status")
@ApplicationScoped
public class ImportStatusSocket {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    ImportController importController;

    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), session);
        System.out.println("[INFO] ImportStatusSocket: New session opened: " + session.getId());

        // 1. Send current installation status
        try {
            ImportInstallationStatus status = importController.getInstallationStatus();
            session.getAsyncRemote().sendText(objectMapper.writeValueAsString(status));
        } catch (IOException e) {
            System.err.println("[ERROR] ImportStatusSocket: Error sending installation status to session " + session.getId() + ": " + e.getMessage());
        }

        // 2. Send the cached output log to the newly connected client
        String outputHistory = importController.getOutputCache();
        if (outputHistory != null && !outputHistory.isEmpty()) {
            session.getAsyncRemote().sendText(outputHistory);
        }

        // 3. If an import is running, send a status message so the UI can disable controls
        if (importController.isImporting()) {
            session.getAsyncRemote().sendText("[IMPORT_IN_PROGRESS]");
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        sessions.remove(session.getId());
        System.out.println("[INFO] ImportStatusSocket: Session " + session.getId() + " closed. Reason: " + closeReason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        sessions.remove(session.getId());
        System.err.println("[ERROR] ImportStatusSocket: Error in session " + session.getId() + ": " + throwable.getMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("[INFO] ImportStatusSocket: Received message from session " + session.getId() + ": " + message);
        try {
            ImportRequest request = objectMapper.readValue(message, ImportRequest.class);
            if ("start-import".equals(request.type)) {
                importController.startDownload(
                    request.url,
                    request.format,
                    request.downloadThreads,
                    request.searchThreads,
                    request.downloadPath,
                    request.playlistName,
                    request.queueAfterDownload
                );
            }
        } catch (IOException e) {
            System.err.println("[ERROR] ImportStatusSocket: Failed to parse import request message from session " + session.getId() + ": " + e.getMessage());
            session.getAsyncRemote().sendText("ERROR: Invalid request format.");
        }
    }

    public void broadcast(String message) {
        sessions.values().forEach(session -> {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText(message, result -> {
                    if (result.getException() != null) {
                        System.err.println("[WARN] ImportStatusSocket: Unable to send message to session " + session.getId() + ": " + result.getException().getMessage());
                    }
                });
            }
        });
    }

    // DTO for incoming start-import messages
    private static class ImportRequest {
        public String type;
        public String url;
        public String format;
        public Integer downloadThreads;
        public Integer searchThreads;
        public String downloadPath;
        public String playlistName;
        public boolean queueAfterDownload = false; // Default to false
    }
}