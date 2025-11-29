package API.WS;

import Controllers.SettingsController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.List;
import io.vertx.core.Vertx;

@ServerEndpoint("/api/logs/ws")
@ApplicationScoped
public class LogSocket {

    @Inject
    WebSocketManager webSocketManager;

    @Inject
    SettingsController settingsController;

    @Inject
    Vertx vertx;

    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session) {
        webSocketManager.addLogSession(session);
        // On connection, send all existing logs
        vertx.executeBlocking(() -> {
            return settingsController.getLogs();
        }).onComplete(ar -> {
            if (ar.succeeded()) {
                List<String> logs = ar.result();
                logs.forEach(log -> {
                    try {
                        ObjectNode message = mapper.createObjectNode();
                        message.put("type", "log");
                        message.put("payload", log);
                        session.getAsyncRemote().sendText(mapper.writeValueAsString(message));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            } else {
                ar.cause().printStackTrace();
            }
        });
    }

    @OnClose
    public void onClose(Session session) {
        webSocketManager.removeLogSession(session);
    }

    public void broadcast(String log) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "log");
            message.put("payload", log);
            webSocketManager.broadcastToLogs(mapper.writeValueAsString(message));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
