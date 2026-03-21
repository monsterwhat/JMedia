package Controllers;

import Models.DeviceSyncSession;
import Models.DTOs.DeviceSyncQrCodeDTO;
import Models.Profile;
import Services.DeviceSyncService;
import Services.ProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Logger;

@ApplicationScoped
public class DeviceSyncController {

    private static final Logger LOGGER = Logger.getLogger(DeviceSyncController.class.getName());

    @Inject
    private DeviceSyncService deviceSyncService;

    @Inject
    private ProfileService profileService;

    public DeviceSyncQrCodeDTO createDeviceSyncSession(Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            DeviceSyncQrCodeDTO qrData = deviceSyncService.generateQrCodeData(profileId);
            LOGGER.info("Created QR code for profile " + profileId + " with security key: " + qrData.getSecurityKey());

            return qrData;

        } catch (Exception e) {
            LOGGER.severe("Failed to create device sync session: " + e.getMessage());
            throw new RuntimeException("Failed to create device sync session", e);
        }
    }

    public DeviceSyncSession completeDeviceConnection(String securityKey, String deviceName, String deviceType, 
                                                     String ipAddress, String userAgent) {
        try {
            return deviceSyncService.completeDeviceConnection(securityKey, deviceName, deviceType, ipAddress, userAgent);
        } catch (Exception e) {
            LOGGER.severe("Failed to complete device connection: " + e.getMessage());
            throw new RuntimeException("Failed to complete device connection", e);
        }
    }

    public java.util.List<DeviceSyncSession> getActiveSessions(Long profileId) {
        try {
            return deviceSyncService.getActiveSessions(profileId);
        } catch (Exception e) {
            LOGGER.severe("Failed to get active sessions: " + e.getMessage());
            throw new RuntimeException("Failed to get active sessions", e);
        }
    }

    public boolean revokeSession(Long profileId, Long sessionId) {
        try {
            return deviceSyncService.revokeSession(profileId, sessionId);
        } catch (Exception e) {
            LOGGER.severe("Failed to revoke session: " + e.getMessage());
            return false;
        }
    }

    public int cleanupExpiredSessions(Long profileId) {
        try {
            return deviceSyncService.cleanupExpiredSessions(profileId);
        } catch (Exception e) {
            LOGGER.severe("Failed to cleanup expired sessions: " + e.getMessage());
            return 0;
        }
    }

    public boolean updateSessionAccess(Long sessionId, String ipAddress) {
        try {
            return deviceSyncService.updateSessionAccess(sessionId, ipAddress);
        } catch (Exception e) {
            LOGGER.severe("Failed to update session access: " + e.getMessage());
            return false;
        }
    }

    public DeviceSyncSession getSessionBySecurityKey(String securityKey) {
        try {
            return deviceSyncService.getSessionBySecurityKey(securityKey);
        } catch (Exception e) {
            LOGGER.severe("Failed to get session by security key: " + e.getMessage());
            return null;
        }
    }

    public DeviceSyncService.DeviceSyncStats getDeviceSyncStats(Long profileId) {
        try {
            return deviceSyncService.getDeviceSyncStats(profileId);
        } catch (Exception e) {
            LOGGER.severe("Failed to get device sync stats: " + e.getMessage());
            return new DeviceSyncService.DeviceSyncStats(0, 0);
        }
    }
}
