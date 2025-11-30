package API.WS;

import Controllers.ImportController;
import Models.DTOs.ImportInstallationStatus; 
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;

import jakarta.websocket.server.PathParam; // Added import
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws/import-status/{profileId}")
@ApplicationScoped
public class ImportStatusSocket {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionProfileMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    ImportController importController;

    @Inject
    Vertx vertx;

    WorkerExecutor executor;

    @PostConstruct
    void init() {
        executor = vertx.createSharedWorkerExecutor("import-worker", 10);
    }
  
    @OnOpen
    public void onOpen(Session session, @PathParam("profileId") Long profileId) { // Modified signature
        sessions.put(session.getId(), session);
        sessionProfileMap.put(session.getId(), profileId); // Store profile association

        // ✔ Modern executeBlocking using Callable (no deprecation)
        executor
                .<String>executeBlocking(() -> {
                    // blocking code allowed here
                    ImportInstallationStatus status = importController.getInstallationStatus();
                    return objectMapper.writeValueAsString(status);
                })
                .onComplete(res -> {
                    if (res.succeeded()) {
                        session.getAsyncRemote().sendText(res.result());
                    } else {
                        session.getAsyncRemote().sendText("ERROR: Unable to load installation status.");
                        res.cause().printStackTrace();
                    }
                });

        // Non-blocking code stays normal
        String outputHistory = importController.getOutputCache();
        if (outputHistory != null && !outputHistory.isEmpty()) {
            session.getAsyncRemote().sendText(outputHistory);
        }

        if (importController.isImporting()) {
            session.getAsyncRemote().sendText("[IMPORT_IN_PROGRESS]");
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        sessions.remove(session.getId());
        sessionProfileMap.remove(session.getId());
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
                Long profileId = sessionProfileMap.get(session.getId());
                if (profileId == null) {
                    System.err.println("[ERROR] ImportStatusSocket: Profile ID not found for session: " + session.getId());
                    session.getAsyncRemote().sendText("ERROR: Profile ID not found for your session. Cannot start import.");
                    return;
                }
                importController.startDownload(
                        request.url,
                        request.format,
                        request.downloadThreads,
                        request.searchThreads,
                        request.downloadPath,
                        request.playlistName,
                        request.queueAfterDownload,
                        profileId
                );
            }
        } catch (IOException e) {
            System.err.println("[ERROR] ImportStatusSocket: Failed to parse import request: " + e.getMessage());
            session.getAsyncRemote().sendText("ERROR: Invalid request format.");
        }
    }

    public void broadcast(String message, Long profileId) {
        sessions.values().forEach(session -> {
            Long sessionProfileId = sessionProfileMap.get(session.getId());
            if (session.isOpen() && sessionProfileId != null && sessionProfileId.equals(profileId)) {
                session.getAsyncRemote().sendText(message, result -> {
                    if (result.getException() != null) {
                        System.err.println("[WARN] ImportStatusSocket: Unable to send message to session "
                                + session.getId() + " for profile " + profileId + ": " + result.getException().getMessage());
                    }
                });
            }
        });
    }

    private static class ImportRequest {

        public String type;
        public String url;
        public String format;
        public Integer downloadThreads;
        public Integer searchThreads;
        public String downloadPath;
        public String playlistName;
        public boolean queueAfterDownload;
    }
}
