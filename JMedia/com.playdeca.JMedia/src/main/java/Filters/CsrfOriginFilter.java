package Filters;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * CSRF protection via Origin/Referer header validation.
 * <p>
 * For unsafe HTTP methods (POST/PUT/PATCH/DELETE), the request's {@code Origin}
 * header (falling back to {@code Referer} when Origin is absent) must reference
 * the same host as the request's own {@code Host} header. If both headers are
 * absent the request is allowed, matching OWASP's recommended modern posture:
 * modern browsers always send {@code Origin} on cross-site unsafe requests, so
 * the worst case for "both absent" is a legacy same-origin client.
 * <p>
 * Host comparison is used (not full origin) so that TLS terminating at a reverse
 * proxy does not break legitimate same-origin POSTs whose {@code Origin} carries
 * the public scheme/port while the backend sees the implicit-default Host header.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class CsrfOriginFilter implements ContainerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of(
            "POST", "PUT", "PATCH", "DELETE"
    );

    @Override
    public void filter(ContainerRequestContext ctx) {
        String method = ctx.getMethod();
        if (method == null || !UNSAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return;
        }

        String origin = ctx.getHeaderString("Origin");
        String referer = ctx.getHeaderString("Referer");
        String host = ctx.getHeaderString("Host");

        if (origin != null && !origin.isBlank()) {
            if (host == null || host.isBlank() || !sameHost(origin, host)) {
                reject(ctx);
            }
            return;
        }

        if (referer != null && !referer.isBlank()) {
            if (host == null || host.isBlank() || !sameHost(referer, host)) {
                reject(ctx);
            }
        }
        // Both absent: allow (legacy same-origin clients).
    }

    private static boolean sameHost(String originOrReferer, String hostHeader) {
        try {
            URI uri = URI.create(originOrReferer);
            String originHost = uri.getHost();
            if (originHost == null || originHost.isBlank()) return false;
            String requestHost = stripPort(hostHeader);
            return requestHost.equalsIgnoreCase(originHost);
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripPort(String hostHeader) {
        if (hostHeader == null) return "";
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            return close > 0 ? hostHeader.substring(0, close + 1) : hostHeader;
        }
        int colon = hostHeader.indexOf(':');
        return colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private static void reject(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"Cross-origin request not allowed\"}")
                .type("application/json")
                .build());
    }
}