package API.WS;

import Controllers.DesktopController;
import Controllers.PlaybackController;
import Models.PlaybackState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@ServerEndpoint("/api/music/ws/{profileId}")
@ApplicationScoped
public class MusicSocket {

    @Inject
    WebSocketManager webSocketManager;

    @Inject
    PlaybackController playbackController;

    @Inject
    DesktopController viewSession;

    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session, @PathParam("profileId") Long profileId) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.addSession(session, profileId);
            sendCurrentState(session, profileId);
            viewSession.clientConnected();
        });

    }

    @OnClose
    public void onClose(Session session) {
        CompletableFuture.runAsync(() -> {
            webSocketManager.removeSession(session);
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

                Long profileId = webSocketManager.getProfileIdForSession(session.getId());
                if (profileId == null) {
                    System.err.println("Profile ID not found for session: " + session.getId());
                    return; // Or handle error appropriately
                }

                switch (type) {
                    case "setProfile":
                        Long newProfileId = payload.get("profileId").asLong();
                        webSocketManager.setSessionProfile(session, newProfileId);
                        sendCurrentState(session, newProfileId); // Send updated state for new profile
                        break;
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
        });
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
