package API.Rest;

import API.ApiResponse;
import Models.DeviceSyncSession;
import Models.DTOs.DeviceSyncQrCodeDTO;
import Models.Profile;
import Services.DeviceSyncService;
import Services.ProfileService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Path("/api/device-sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceSyncApi {

    private static final Logger LOGGER = Logger.getLogger(DeviceSyncApi.class.getName());

    @Inject
    private DeviceSyncService deviceSyncService;

    @Inject
    private ProfileService profileService;

    @Inject
    private Controllers.DeviceSyncController deviceSyncController;

    /**
     * Generate a new QR code for device sync
     */
    @POST
    @Path("/{profileId}/generate-qr")
    @Transactional
    public Response generateQrCode(@PathParam("profileId") Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Profile not found"))
                        .build();
            }

            // Generate QR code data using controller
            DeviceSyncQrCodeDTO qrData = deviceSyncController.createDeviceSyncSession(profileId);
            
            // Generate QR code image as base64
            String qrImageBase64 = deviceSyncService.generateQrCodeImage(qrData);

            Map<String, Object> response = new HashMap<>();
            response.put("qrData", qrData);
            response.put("qrImage", "data:image/png;base64," + qrImageBase64);
            response.put("connectionUrl", qrData.getFullConnectionUrl());

            return Response.ok(ApiResponse.success(response)).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to generate QR code: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to generate QR code: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get all active device sync sessions for a profile
     */
    @GET
    @Path("/{profileId}/sessions")
    @Transactional
    public Response getActiveSessions(@PathParam("profileId") Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Profile not found"))
                        .build();
            }

            List<DeviceSyncSession> sessions = deviceSyncController.getActiveSessions(profileId);

            return Response.ok(ApiResponse.success(sessions)).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to get active sessions: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get active sessions: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Revoke/remove a device sync session
     */
    @DELETE
    @Path("/{profileId}/sessions/{sessionId}")
    @Transactional
    public Response revokeSession(@PathParam("profileId") Long profileId, 
                               @PathParam("sessionId") Long sessionId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Profile not found"))
                        .build();
            }

            DeviceSyncSession session = DeviceSyncSession.findById(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Session not found"))
                        .build();
            }

            // Verify session belongs to the specified profile
            if (!session.getProfile().id.equals(profileId)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(ApiResponse.error("Session does not belong to this profile"))
                        .build();
            }

            deviceSyncController.revokeSession(profileId, sessionId);

            return Response.ok(ApiResponse.success("Session revoked successfully")).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to revoke session: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to revoke session: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Clean up expired sessions
     */
    @POST
    @Path("/{profileId}/cleanup-expired")
    @Transactional
    public Response cleanupExpiredSessions(@PathParam("profileId") Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Profile not found"))
                        .build();
            }

            // Clean up expired sessions
            int updatedCount = deviceSyncController.cleanupExpiredSessions(profileId);

            Map<String, Object> response = new HashMap<>();
            response.put("cleanedUpCount", updatedCount);

            return Response.ok(ApiResponse.success(response)).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to cleanup expired sessions: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to cleanup expired sessions: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get server connection info (for external devices)
     */
    @GET
    @Path("/server-info")
    public Response getServerInfo() {
        try {
            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("address", deviceSyncService.getServerAddress());
            serverInfo.put("port", deviceSyncService.getServerPort());
            serverInfo.put("protocol", "http");
            serverInfo.put("version", "1.0");
            serverInfo.put("timestamp", System.currentTimeMillis());

            return Response.ok(ApiResponse.success(serverInfo)).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to get server info: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get server info: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Device connection endpoint (called by external devices)
     */
    @POST
    @Path("/connect")
    @Transactional
    public Response connectDevice(@QueryParam("key") String securityKey,
                                @HeaderParam("User-Agent") String userAgent,
                                @HeaderParam("X-Forwarded-For") String forwardedFor,
                                @HeaderParam("X-Real-IP") String realIp) {
        try {
            if (!deviceSyncService.isValidSecurityKey(securityKey)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("Invalid security key"))
                        .build();
            }

            // Get client IP address
            String clientIp = getClientIp(forwardedFor, realIp);

            // Complete device connection using controller
            DeviceSyncSession session = deviceSyncController.completeDeviceConnection(
                securityKey, null, null, clientIp, userAgent);

            if (session == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("Invalid or expired session"))
                        .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", session.id);
            response.put("deviceName", session.getDeviceName());
            response.put("syncMusic", session.isSyncMusic());
            response.put("syncVideos", session.isSyncVideos());
            response.put("syncPlaylists", session.isSyncPlaylists());

            return Response.ok(ApiResponse.success(response)).build();

        } catch (Exception e) {
            LOGGER.severe("Failed to connect device: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to connect device: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get client IP address from headers
     */
    private String getClientIp(String forwardedFor, String realIp) {
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return forwardedFor.split(",")[0].trim();
        }
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return "unknown";
    }
}