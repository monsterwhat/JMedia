package Utils;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
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

    /**
     * Returns true if {@code ipStr} is contained in any of the provided CIDR ranges
     * (e.g. {@code 192.168.100.0/24}, {@code 127.0.0.1/32}, {@code ::1/128}).
     * Entries without a {@code /} prefix are treated as exact host matches (IPv4 or IPv6).
     * Both IPv4 and IPv6 are supported, but the address family of the entry must match the IP.
     */
    public static boolean isInAnyCidr(String ipStr, List<String> cidrs) {
        if (ipStr == null || ipStr.isBlank() || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        for (String entry : cidrs) {
            if (entry == null || entry.isBlank()) continue;
            if (isInCidr(ipStr, entry.trim())) return true;
        }
        return false;
    }

    private static boolean isInCidr(String ipStr, String cidr) {
        int slash = cidr.indexOf('/');
        try {
            if (slash < 0) {
                return InetAddress.getByName(ipStr).equals(InetAddress.getByName(cidr));
            }
            String networkStr = cidr.substring(0, slash);
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            byte[] ip = InetAddress.getByName(ipStr).getAddress();
            byte[] network = InetAddress.getByName(networkStr).getAddress();
            if (ip.length != network.length) return false;
            if (prefix < 0 || prefix > ip.length * 8) return false;
            int fullBytes = prefix / 8;
            int partialBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != network[i]) return false;
            }
            if (partialBits > 0 && fullBytes < ip.length) {
                int mask = 0xFF << (8 - partialBits);
                if ((ip[fullBytes] & mask) != (network[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
