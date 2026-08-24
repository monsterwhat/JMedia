package API.WS;

import Models.Settings.Profile;
import Models.Settings.Session;
import Services.ProfileService;
import Services.SessionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.server.HandshakeRequest;

import java.util.List;
import java.util.Map;

/**
 * Authenticates a WebSocket handshake from its JMEDIA_SESSION cookie and verifies
 * the requested profileId belongs to the authenticated user. This closes the gap
 * where any client could bind a WS session to any profileId (cross-user playback
 * bleed). Returns false for missing/invalid sessions and for foreign profiles.
 */
@ApplicationScoped
public class WebSocketAuthService {

    @Inject
    SessionService sessionService;

    @Inject
    ProfileService profileService;

    public boolean isAuthorized(HandshakeRequest handshake, Long profileId) {
        if (handshake == null || profileId == null) {
            return false;
        }

        String sessionId = extractSessionId(handshake);
        if (sessionId == null) {
            return false;
        }

        Session session = sessionService.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return false;
        }

        Long userId;
        try {
            userId = Long.parseLong(session.userId);
        } catch (NumberFormatException e) {
            return false;
        }

        Profile profile = profileService.findById(profileId);
        return profile != null && profile.userId != null && profile.userId.equals(userId);
    }

    private String extractSessionId(HandshakeRequest handshake) {
        Map<String, List<String>> headers = handshake.getHeaders();
        List<String> cookieHeaders = headers.get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            if (header == null) {
                continue;
            }
            for (String part : header.split(";")) {
                part = part.trim();
                if (part.startsWith("JMEDIA_SESSION=")) {
                    return part.substring("JMEDIA_SESSION=".length()).trim();
                }
            }
        }
        return null;
    }
}