package Utils;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IpResolutionUtils {
    private static final Logger LOG = LoggerFactory.getLogger(IpResolutionUtils.class);
    
    // In a real production environment, this would be loaded from configuration
    private static final List<String> TRUSTED_PROXIES = Collections.singletonList("127.0.0.1");
    private static final boolean TRUST_ALL_PROXIES = false; // Set to true only if behind a mandatory internal proxy

    /**
     * Safely resolves the client's real IP address from a JAX-RS request context.
     */
    public static String getClientIp(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        String remoteAddress = vertxRequest.remoteAddress().hostAddress();
        
        // 1. Check if the direct connection is from a trusted source
        if (!TRUST_ALL_PROXIES && !TRUSTED_PROXIES.contains(remoteAddress)) {
            return remoteAddress;
        }

        // 2. If trusted, attempt to read forwarding headers
        String xForwardedFor = requestContext.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take the first IP in the chain
            String clientIp = xForwardedFor.split(",")[0].trim();
            if (isValidIp(clientIp)) {
                return clientIp;
            }
        }

        String xRealIp = requestContext.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && isValidIp(xRealIp)) {
            return xRealIp;
        }

        return remoteAddress;
    }

    /**
     * Simple validation to ensure the IP doesn't contain malicious characters.
     */
    private static boolean isValidIp(String ip) {
        return ip != null && ip.matches("^[a-fA-F0-9:.]+$");
    }
}
