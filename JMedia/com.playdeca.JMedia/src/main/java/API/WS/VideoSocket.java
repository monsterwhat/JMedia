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
import org.slf4j.LoggerFactory;

@ServerEndpoint("/api/video/ws/{profileId}")
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
            sendCurrentState(session, profileId);
            viewSession.clientConnected(); // Still relevant for any client connection
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });

    }

    @OnClose
    public void onClose(Session session) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeVideoSession(session); // Remove from video sessions
            viewSession.clientDisconnected(); // Still relevant for any client disconnection
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("[VideoSocket] Error in session " + session.getId() + ": " + throwable.getMessage());
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeVideoSession(session);
            viewSession.clientDisconnected();
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                ObjectNode node = mapper.readValue(message, ObjectNode.class);
                String type = node.get("type").asText();
                JsonNode payload = node.get("payload");

                // Every video WS session is bound to the profileId in its connect URL,
                // so commands MUST operate on the SENDER's profile. Before this, the
                // controller resolved state via thread-local HTTP context, which is
                // absent on common-pool WS threads, so it fell back to the single
                // global activePlayingProfileId — profile B's seek/next/toggle drove
                // profile A's playback whenever A was globally active.
                Long profileId = webSocketManager.getProfileIdForSession(session.getId());
                if (profileId == null) {
                    System.err.println("Profile ID not found for session: " + session.getId());
                    return;
                }

                switch (type) {
                    case "seek":
                        double seekValue = payload.get("value").asDouble();
                        videoController.setSeconds(seekValue, profileId);
                        break;
                    case "volume":
                        videoController.changeVolume((float) payload.get("value").asDouble(), profileId);
                        break;
                    case "next":
                        videoController.next(profileId);
                        break;
                    case "toggle-play":
                        videoController.togglePlay(profileId);
                        break;
                    case "previous":
                        videoController.previous(profileId);
                        break;
                    case "state": // Client reports its current playback state
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
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    private void sendCurrentState(Session session, Long profileId) {
        // Send ONLY the connecting session's own profile state. The previous global
        // getState() resolved via HTTP thread-local context, which is absent on WS
        // threads, so a fresh tab for profile A while profile B was playing received
        // B's currentVideoId on connect and immediately swapped sources.
        ProfileSessionState state = videoController.getState(profileId);
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
            // tab and triggered a Remote source swap. States with no profileId
            // (fresh/unowned rows) are deliberately NOT broadcast anywhere.
            Long pid = stateToBroadcast.profileId;
            if (pid != null) {
                webSocketManager.broadcastToProfile(pid, mapper.writeValueAsString(message));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Broadcast a command to connected video WebSocket clients.
     * When profileId is non-null, routes only to that profile's sessions;
     * otherwise falls back to the active playing profile via ThreadLocal state.
     * Sends message in format: {"type":"command","payload":{"commandType":"...","commandPayload":{...}}}
     */
    public void broadcastCommand(String commandType, JsonNode commandPayload, Long profileId) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "command");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("commandType", commandType);
            payload.set("commandPayload", commandPayload);
            message.set("payload", payload);
            String json = mapper.writeValueAsString(message);
            Long pid = profileId;
            if (pid == null) {
                ProfileSessionState currentState = videoController.getState();
                pid = currentState != null ? currentState.profileId : null;
            }
            if (pid != null) {
                webSocketManager.broadcastToProfile(pid, json);
            }
        } catch (Exception e) {
            System.err.println("[VideoSocket] Error broadcasting command: " + e.getMessage());
        }
    }

    /** @deprecated Use {@link #broadcastCommand(String, JsonNode, Long)} with explicit profileId. */
    @Deprecated
    public void broadcastCommand(String commandType, JsonNode commandPayload) {
        broadcastCommand(commandType, commandPayload, null);
    }

}