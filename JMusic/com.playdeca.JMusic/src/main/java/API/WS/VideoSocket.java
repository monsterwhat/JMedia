package API.WS;

import Controllers.DesktopController;
import Controllers.VideoController; // Inject VideoController
import Models.VideoState;           // Use VideoState
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
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
        VideoState state = videoController.getState(); // Get VideoState
        if (state != null && state.getCurrentVideoId() != null) {
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
        VideoState state = videoController.getState();
        broadcastAll(state);
    }

    public void broadcastAll(VideoState stateToBroadcast) { // Broadcast VideoState
        if (stateToBroadcast == null) {
            System.out.println("[VideoSocket] broadcastAll: stateToBroadcast is null, not broadcasting.");
            return;
        }

        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "state");
            message.set("payload", mapper.valueToTree(stateToBroadcast));
            webSocketManager.broadcastToVideo(mapper.writeValueAsString(message)); // Broadcast to video sessions
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
