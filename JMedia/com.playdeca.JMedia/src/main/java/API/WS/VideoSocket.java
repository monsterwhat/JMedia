package API.WS;

import Controllers.DesktopController;
import Controllers.VideoController;
import Models.ProfileSessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@ServerEndpoint("/api/video/ws") // Video WebSocket endpoint
@ApplicationScoped
public class VideoSocket {

    @Inject
    WebSocketManager webSocketManager;

    @Inject
    VideoController videoController; // Inject VideoController

    @Inject
    DesktopController viewSession; // Reusing DesktopController for client connected/disconnected status

    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.addVideoSession(session); // Add to video sessions
            sendCurrentState(session);
            viewSession.clientConnected(); // Still relevant for any client connection
        });

    }

    @OnClose
    public void onClose(Session session) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeVideoSession(session); // Remove from video sessions
            viewSession.clientDisconnected(); // Still relevant for any client disconnection
        });
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("[VideoSocket] Error in session " + session.getId() + ": " + throwable.getMessage());
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeVideoSession(session);
            viewSession.clientDisconnected();
        });
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                ObjectNode node = mapper.readValue(message, ObjectNode.class);
                String type = node.get("type").asText();
                JsonNode payload = node.get("payload");
                switch (type) {
                    case "seek":
                        double seekValue = payload.get("value").asDouble();
                        videoController.setSeconds(seekValue); // Call videoController method
                        break;
                    case "volume":
                        videoController.changeVolume((float) payload.get("value").asDouble()); // Call videoController method
                        break;
                    case "next":
                        videoController.next(); // Call videoController method
                        break;
                    case "toggle-play": // Added toggle-play action for video
                        videoController.togglePlay();
                        break;
                    case "previous": // Added previous action for video
                        videoController.previous();
                        break; 
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void sendCurrentState(Session session) {
        ProfileSessionState state = videoController.getState(); // Get ProfileSessionState
        if (state != null && state.currentVideoId != null) {
            try {
                ObjectNode message = mapper.createObjectNode();
                message.put("type", "state");
                message.set("payload", mapper.valueToTree(state));
                session.getAsyncRemote().sendText(mapper.writeValueAsString(message));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void broadcastLibraryUpdate() {
        // This might need to be more specific for video library updates,
        // but for now, we'll just broadcast the current video state.
        ProfileSessionState state = videoController.getState();
        broadcastAll(state);
    }

    public void broadcastAll(ProfileSessionState stateToBroadcast) {
        if (stateToBroadcast == null) return;

        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "state");
            message.set("payload", mapper.valueToTree(stateToBroadcast));
            webSocketManager.broadcastToVideo(mapper.writeValueAsString(message));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
