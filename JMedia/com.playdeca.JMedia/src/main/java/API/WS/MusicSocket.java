package API.WS;

import Controllers.DesktopController;
import Controllers.PlaybackController;
import Models.Music.PlaybackState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/api/music/ws/{profileId}", configurator = WebSocketAuthConfigurator.class)
@ApplicationScoped
public class MusicSocket {

    @Inject
    WebSocketManager webSocketManager;

    @Inject
    PlaybackController playbackController;

    @Inject
    DesktopController viewSession;

    @Inject
    WebSocketAuthService webSocketAuthService;

    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session, EndpointConfig config, @PathParam("profileId") Long profileId) {
        HandshakeRequest handshake = (HandshakeRequest) config.getUserProperties()
                .get(WebSocketAuthConfigurator.HANDSHAKE_REQUEST_KEY);
        if (!webSocketAuthService.isAuthorized(handshake, profileId)) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Profile access denied"));
            } catch (IOException ignored) {
            }
            return;
        }
        CompletableFuture.runAsync(() -> {
            webSocketManager.addSession(session, profileId);
            sendCurrentState(session, profileId);
            viewSession.clientConnected();
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    @OnClose
    public void onClose(Session session) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeSession(session);
            viewSession.clientDisconnected();
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("[MusicSocket] Error in session " + session.getId() + ": " + throwable.getMessage());
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeSession(session);
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

                Long profileId = webSocketManager.getProfileIdForSession(session.getId());
                if (profileId == null) {
                    System.err.println("Profile ID not found for session: " + session.getId());
                    return; // Or handle error appropriately
                }

                switch (type) {
                    // SECURITY: Disabled until proper user-owns-profile validation is implemented
                    // Any authenticated user could hijack any profile's playback session
                    // case "setProfile":
                    //     Long newProfileId = payload.get("profileId").asLong();
                    //     webSocketManager.setSessionProfile(session, newProfileId);
                    //     sendCurrentState(session, newProfileId);
                    //     break;
                    case "seek":
                        double seekValue = payload.get("value").asDouble();
                        playbackController.setSeconds(seekValue, profileId);
                        break;
                    case "volume":
                        playbackController.changeVolume((float) payload.get("value").asDouble(), profileId);
                        break;
                    case "next":
                        playbackController.next(profileId);
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).exceptionally(ex -> { LoggerFactory.getLogger(getClass()).error("WebSocket async error", ex); return null; });
    }

    private void sendCurrentState(Session session, Long profileId) {
        PlaybackState state = playbackController.getState(profileId);
        if (state != null) {
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

    public void broadcastLibraryUpdate(Long profileId) {
        PlaybackState state = playbackController.getState(profileId);
        broadcastAll(state, profileId);
    }

    public void broadcastLibraryUpdateToAllProfiles() {
        webSocketManager.getAllActiveProfileIds().forEach(profileId -> {
            broadcastLibraryUpdate(profileId);
        });
    }

    public void broadcastAll(PlaybackState stateToBroadcast, Long profileId) {
        if (stateToBroadcast == null) {
            System.out.println("[MusicSocket] broadcastAll: stateToBroadcast is null, not broadcasting.");
            return;
        }

        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "state");
            message.set("payload", mapper.valueToTree(stateToBroadcast));
            webSocketManager.broadcastToProfile(profileId, mapper.writeValueAsString(message));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void broadcastHistoryUpdate(Long profileId) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "history-update");
            message.put("profileId", profileId);
            String messageJson = mapper.writeValueAsString(message);
            System.out.println("[MusicSocket] Broadcasting history update for profile " + profileId + ": " + messageJson);
            webSocketManager.broadcastToProfile(profileId, messageJson);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
