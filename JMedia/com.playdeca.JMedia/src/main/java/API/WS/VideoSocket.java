package API.WS;

import Controllers.DesktopController;
import Controllers.VideoController;
import Models.Video.ProfileSessionState;
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
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@ServerEndpoint("/api/video/ws/{profileId}") // Video WebSocket endpoint (per-profile isolation)
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
    public void onOpen(Session session, @PathParam("profileId") Long profileId) {
        CompletableFuture.runAsync(() -> {
            // Bind this session to the profile so broadcasts route only to same-profile
            // clients. The previous profile-blind addVideoSession(session) caused cross-
            // profile state bleed: a state broadcast for profile 2 reached profile 1's
            // tab, whose OPlayerAdapter then swapped sources ("Remote source swap -> 3753")
            // because currentVideoId didn't match its own videoId.
            webSocketManager.addVideoSession(session, profileId);
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
                    case "state": // Client reports its current playback state
                        Long profileId = payload.has("profileId") ? payload.get("profileId").asLong() : null;
                        Long videoId = payload.has("currentVideoId") ? payload.get("currentVideoId").asLong() : (payload.has("currentVideo") && payload.get("currentVideo").has("id") ? payload.get("currentVideo").get("id").asLong() : null);
                        if (videoId == null) return;
                        boolean playing = payload.get("playing").asBoolean();
                        double currentTime = payload.has("currentTime") ? payload.get("currentTime").asDouble() : 0.0;
                        videoController.reportClientState(profileId, videoId, playing, currentTime);
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
            // Per-profile isolation: route to the state's profile only. The previous
            // global broadcastToVideo pushed every state change to every connected
            // client, so profile 2's "now playing video 3753" reached profile 1's
            // tab and triggered a Remote source swap.
            Long pid = stateToBroadcast.profileId;
            if (pid != null) {
                webSocketManager.broadcastToProfile(pid, mapper.writeValueAsString(message));
            } else {
                webSocketManager.broadcastToVideo(mapper.writeValueAsString(message));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Broadcast a command to connected video WebSocket clients on the active profile.
     * Used by REST endpoints to trigger actions like subtitle/audio switching.
     * Sends message in format: {"type":"command","payload":{"commandType":"...","commandPayload":{...}}}
     */
    public void broadcastCommand(String commandType, JsonNode commandPayload) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "command");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("commandType", commandType);
            payload.set("commandPayload", commandPayload);
            message.set("payload", payload);
            // Route to the active playing profile so commands issued by REST endpoints
            // (toggle-play, next, previous, seek, select-subtitle, select-audio) only
            // reach clients on the same profile — matches the per-profile broadcast
            // isolation now applied to state broadcasts above.
            ProfileSessionState currentState = videoController.getState();
            Long pid = currentState != null ? currentState.profileId : null;
            String json = mapper.writeValueAsString(message);
            if (pid != null) {
                webSocketManager.broadcastToProfile(pid, json);
            } else {
                webSocketManager.broadcastToVideo(json);
            }
        } catch (Exception e) {
            System.err.println("[VideoSocket] Error broadcasting command: " + e.getMessage());
        }
    }
}