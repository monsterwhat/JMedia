package API.Rest;

import API.ApiResponse;
import Models.Settings.PairingClaim;
import Models.Settings.User;
import Services.AuthService;
import Services.PairingService;
import Utils.IpResolutionUtils;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QR pairing for TV clients.
 *
 * The TV shows a short code (POST /start), the signed-in phone claims it
 * (POST /claim), the TV polls (GET /land/{code}) and redeems a single-use
 * token for a session cookie (POST /redeem). No password crosses the wire.
 * See Services.PairingService for lifecycle and limits.
 */
@Path("/api/pairing")
@Produces(MediaType.APPLICATION_JSON)
public class PairingAPI {

    private static final Logger log = Logger.getLogger(PairingAPI.class);

    @Inject
    PairingService pairingService;

    @Inject
    AuthService authService;

    @Inject
    HttpServerRequest vertxRequest;

    @ConfigProperty(name = "jmedia.local-network-cidrs",
            defaultValue = "192.168.100.0/24,10.50.0.0/24,127.0.0.1/32,::1/128")
    List<String> localNetworkCidrs;

    @POST
    @Path("/start")
    public Response start(@Context ContainerRequestContext requestContext) {
        try {
            String ip = IpResolutionUtils.getClientIp(requestContext, vertxRequest);
            return Response.ok(ApiResponse.success(pairingService.start(ip))).build();
        } catch (PairingService.PairingException e) {
            return Response.status(e.status).entity(ApiResponse.error(e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Pairing start failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Could not start pairing")).build();
        }
    }

    @GET
    @Path("/land/{code}")
    public Response land(@PathParam("code") String code,
                         @Context ContainerRequestContext requestContext) {
        try {
            PairingClaim claim = pairingService.land(code == null ? "" : code.strip().toUpperCase());
            if (claim == null || PairingClaim.PENDING.equals(claim.status)) {
                // Pending and nonexistent share 404 so code existence is not oracle-able.
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Not claimed yet")).build();
            }
            if (PairingClaim.EXPIRED.equals(claim.status)
                    || PairingClaim.REDEEMED.equals(claim.status)) {
                return Response.status(Response.Status.GONE)
                        .entity(ApiResponse.error("Pairing code expired")).build();
            }
            long secondsLeft = 0;
            if (claim.tokenExpiresAt != null) {
                secondsLeft = Math.max(0, Instant.now().until(claim.tokenExpiresAt, java.time.temporal.ChronoUnit.SECONDS));
            }
            String profileName = null;
            if (claim.profileId != null) {
                Models.Settings.Profile profile = Models.Settings.Profile.findById(claim.profileId);
                if (profile != null) profileName = profile.name;
            }
            Map<String, Object> handover = new HashMap<>();
            handover.put("targetUrl", claim.targetUrl);
            handover.put("token", claim.token);
            handover.put("expiresIn", secondsLeft);
            handover.put("profileId", claim.profileId);
            handover.put("profileName", profileName);
            handover.put("username", claim.username);
            return Response.ok(ApiResponse.success(handover)).build();
        } catch (Exception e) {
            log.errorf("Pairing land failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Could not check pairing status")).build();
        }
    }

    @POST
    @Path("/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response claim(Map<String, Object> body, @Context HttpHeaders headers) {
        User user = authService.getCurrentUser(headers);
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Sign in on this device first")).build();
        }
        if (body == null || body.get("code") == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("code is required")).build();
        }
        try {
            String code = String.valueOf(body.get("code")).strip().toUpperCase();
            String targetUrl = body.get("targetUrl") == null ? null : String.valueOf(body.get("targetUrl"));
            Long profileId = body.get("profileId") == null
                    ? null : ((Number) body.get("profileId")).longValue();
            String deviceName = body.get("deviceName") == null
                    ? null : String.valueOf(body.get("deviceName"));
            pairingService.claim(code, user, targetUrl, profileId, deviceName);
            return Response.ok(ApiResponse.success(Map.of("message", "TV paired"))).build();
        } catch (PairingService.PairingException e) {
            return Response.status(e.status).entity(ApiResponse.error(e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Pairing claim failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Could not claim pairing code")).build();
        }
    }

    @POST
    @Path("/redeem")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response redeem(Map<String, Object> body,
                           @Context ContainerRequestContext requestContext) {
        if (body == null || body.get("token") == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("token is required")).build();
        }
        try {
            String ip = IpResolutionUtils.getClientIp(requestContext, vertxRequest);
            PairingService.Redemption redemption =
                    pairingService.redeem(String.valueOf(body.get("token")), ip);

            // Same cookie contract as login: Secure everywhere except the LAN.
            NewCookie.Builder cookieBuilder = new NewCookie.Builder("JMEDIA_SESSION")
                    .value(redemption.session.sessionId)
                    .path("/")
                    .maxAge(604800)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .comment("JMedia authentication session");
            if (!IpResolutionUtils.isInAnyCidr(ip, localNetworkCidrs)) {
                cookieBuilder.secure(true);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("message", "Pairing complete");
            result.put("username", redemption.username);
            result.put("profileId", redemption.profileId);
            result.put("profileName", redemption.profileName);
            return Response.ok()
                    .entity(ApiResponse.success(result))
                    .cookie(cookieBuilder.build())
                    .build();
        } catch (PairingService.PairingException e) {
            return Response.status(e.status).entity(ApiResponse.error(e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Pairing redeem failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Could not redeem pairing token")).build();
        }
    }
}
