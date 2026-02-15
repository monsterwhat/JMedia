package API.Filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import Models.Session;
import Services.SettingsService;

@Provider
public class UserContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Context
    HttpHeaders headers;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        setCurrentUser();
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        SettingsService.clearCurrentUserId();
    }

    private void setCurrentUser() {
        try {
            String sessionId = getSessionId();
            if (sessionId != null) {
                Session session = Session.findBySessionId(sessionId);
                if (session != null && session.active) {
                    try {
                        Long userId = Long.parseLong(session.userId);
                        SettingsService.setCurrentUserId(userId);
                    } catch (NumberFormatException e) {
                        // Invalid userId in session, ignore
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors in filter
        }
    }

    private String getSessionId() {
        if (headers != null && headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            return headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        return null;
    }
}
