package API.WS;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

/**
 * Stashes the WebSocket handshake (and thus the JMEDIA_SESSION cookie) into the
 * endpoint config's user properties so the @OnOpen handler can authenticate the
 * connecting client and verify it owns the requested profileId. Without this, the
 * WebSocket layer accepted any profileId, letting one user bind to (and receive)
 * another user's playback.
 */
public class WebSocketAuthConfigurator extends ServerEndpointConfig.Configurator {

    public static final String HANDSHAKE_REQUEST_KEY = "JMEDIA_HANDSHAKE_REQUEST";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        sec.getUserProperties().put(HANDSHAKE_REQUEST_KEY, request);
    }
}