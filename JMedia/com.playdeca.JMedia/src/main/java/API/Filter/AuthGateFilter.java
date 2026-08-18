package API.Filter;

import Models.Settings.Session;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import org.jboss.logging.Logger;

@Provider
@Priority(6000)
public class AuthGateFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AuthGateFilter.class);
    private static final String SESSION_COOKIE = "JMEDIA_SESSION";
    private static final String[] EXEMPT_PATHS = {
        "/login.html",
        "/api/auth",
        "/api/setup",
        "/api/installation",
        "/player_api.php",
        "/api/music/stream/",
        "/api/video/stream/",
        "/api/video/progress/",
        "/api/video/storyboard/",
        "/api/hls/master/",
        "/api/hls/playlist/",
        "/api/hls/media/"
    };

    @Context
    HttpHeaders headers;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) return;

        String path = requestContext.getUriInfo().getPath();
        if (isExempt(path)) return;

        if (hasValidSession()) return;

        LOG.warnf("Unauthenticated request blocked: %s %s", requestContext.getMethod(), path);
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity("{\"error\":\"Authentication required\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private boolean isExempt(String path) {
        for (String exempt : EXEMPT_PATHS) {
            if (path.equals(exempt) || path.startsWith(exempt + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValidSession() {
        try {
            if (headers == null || headers.getCookies() == null) return false;
            Cookie cookie = headers.getCookies().get(SESSION_COOKIE);
            if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) return false;
            Session session = Session.findBySessionId(cookie.getValue());
            return session != null && session.active;
        } catch (Exception e) {
            LOG.warnv("Error validating session in AuthGateFilter: {0}", e.getMessage());
            return false;
        }
    }
}