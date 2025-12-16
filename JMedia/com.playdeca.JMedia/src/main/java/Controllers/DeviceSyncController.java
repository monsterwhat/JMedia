package Controllers;

import Models.DeviceSyncSession;
import Models.DTOs.DeviceSyncQrCodeDTO;
import Models.Profile;
import Services.DeviceSyncService;
import Services.ProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

import java.util.logging.Logger;

@ApplicationScoped
public class DeviceSyncController {

    private static final Logger LOGGER = Logger.getLogger(DeviceSyncController.class.getName());

    @Inject
    private DeviceSyncService deviceSyncService;

    @Inject
    private ProfileService profileService;

    /**
     * Create a new device sync session with QR code data
     */
    @Transactional
    public DeviceSyncQrCodeDTO createDeviceSyncSession(Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            // Generate QR code data
            DeviceSyncQrCodeDTO qrData = deviceSyncService.generateQrCodeData();

            // Create a placeholder session that will be completed when device connects
            // The actual session will be created when the device connects with the security key
            LOGGER.info("Created QR code for profile " + profileId + " with security key: " + qrData.getSecurityKey());

            return qrData;

        } catch (Exception e) {
            LOGGER.severe("Failed to create device sync session: " + e.getMessage());
            throw new RuntimeException("Failed to create device sync session", e);
        }
    }

    /**
     * Complete device connection when device scans QR code and connects
     */
    @Transactional
    public DeviceSyncSession completeDeviceConnection(String securityKey, String deviceName, String deviceType, 
                                                 String ipAddress, String userAgent) {
        try {
            if (!deviceSyncService.isValidSecurityKey(securityKey)) {
                throw new IllegalArgumentException("Invalid security key");
            }

            // Check if session already exists for this security key
            DeviceSyncSession existingSession = DeviceSyncSession.find("securityKey = ?1 AND isActive = true AND expiresAt > ?2", 
                                                                    securityKey, LocalDateTime.now()).firstResult();

            if (existingSession != null) {
                // Update existing session
                existingSession.updateLastAccessed(ipAddress);
                if (deviceName != null) {
                    existingSession.setDeviceName(deviceName);
                }
                if (deviceType != null) {
                    existingSession.setDeviceType(deviceType);
                }
                if (userAgent != null) {
                    existingSession.setUserAgent(userAgent);
                }
                existingSession.persist();
                LOGGER.info("Updated existing device session: " + existingSession.id);
                return existingSession;
            }

            // Create new session - for now, we'll use the default profile
            // In a real implementation, the profile should be determined from the QR code context
            Profile defaultProfile = profileService.getMainProfile();
            if (defaultProfile == null) {
                throw new IllegalStateException("No default profile found");
            }

            DeviceSyncSession session = new DeviceSyncSession(
                deviceName != null ? deviceName : "Unknown Device",
                deviceType != null ? deviceType : "unknown",
                ipAddress,
                userAgent != null ? userAgent : "Unknown",
                securityKey,
                defaultProfile
            );

            session.persist();
            LOGGER.info("Created new device session: " + session.id + " for device: " + deviceName);
            return session;

        } catch (Exception e) {
            LOGGER.severe("Failed to complete device connection: " + e.getMessage());
            throw new RuntimeException("Failed to complete device connection", e);
        }
    }

    /**
     * Get all active sessions for a profile
     */
    @Transactional
    public List<DeviceSyncSession> getActiveSessions(Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            return DeviceSyncSession.list("profile = ?1 AND isActive = true AND expiresAt > ?2", 
                                        profile, LocalDateTime.now());

        } catch (Exception e) {
            LOGGER.severe("Failed to get active sessions: " + e.getMessage());
            throw new RuntimeException("Failed to get active sessions", e);
        }
    }

    /**
     * Revoke a device session
     */
    @Transactional
    public boolean revokeSession(Long profileId, Long sessionId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            DeviceSyncSession session = DeviceSyncSession.findById(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("Session not found");
            }

            // Verify session belongs to the specified profile
            if (!session.getProfile().id.equals(profileId)) {
                throw new IllegalArgumentException("Session does not belong to this profile");
            }

            session.deactivate();
            session.persist();

            LOGGER.info("Revoked device session: " + sessionId);
            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to revoke session: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clean up expired sessions
     */
    @Transactional
    public int cleanupExpiredSessions(Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            // Deactivate expired sessions
            int updatedCount = DeviceSyncSession.update(
                "isActive = false WHERE profile = ?1 AND expiresAt <= ?2 AND isActive = true",
                profile, LocalDateTime.now()
            );

            LOGGER.info("Cleaned up " + updatedCount + " expired sessions for profile " + profileId);
            return updatedCount;

        } catch (Exception e) {
            LOGGER.severe("Failed to cleanup expired sessions: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Update session last accessed time
     */
    @Transactional
    public boolean updateSessionAccess(Long sessionId, String ipAddress) {
        try {
            DeviceSyncSession session = DeviceSyncSession.findById(sessionId);
            if (session == null || !session.isActive() || session.isExpired()) {
                return false;
            }

            session.updateLastAccessed(ipAddress);
            session.persist();

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to update session access: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get session by security key
     */
    @Transactional
    public DeviceSyncSession getSessionBySecurityKey(String securityKey) {
        try {
            if (!deviceSyncService.isValidSecurityKey(securityKey)) {
                return null;
            }

            return DeviceSyncSession.find("securityKey = ?1 AND isActive = true AND expiresAt > ?2", 
                                       securityKey, LocalDateTime.now()).firstResult();

        } catch (Exception e) {
            LOGGER.severe("Failed to get session by security key: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get device sync statistics
     */
    @Transactional
    public DeviceSyncStats getDeviceSyncStats(Long profileId) {
        try {
            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                throw new IllegalArgumentException("Profile not found");
            }

            long activeCount = DeviceSyncSession.count("profile = ?1 AND isActive = true AND expiresAt > ?2", 
                                                   profile, LocalDateTime.now());
            
            long expiredCount = DeviceSyncSession.count("profile = ?1 AND (isActive = false OR expiresAt <= ?2)", 
                                                    profile, LocalDateTime.now());

            return new DeviceSyncStats(activeCount, expiredCount);

        } catch (Exception e) {
            LOGGER.severe("Failed to get device sync stats: " + e.getMessage());
            return new DeviceSyncStats(0, 0);
        }
    }

    /**
     * Device sync statistics holder
     */
    public static class DeviceSyncStats {
        private final long activeSessions;
        private final long expiredSessions;

        public DeviceSyncStats(long activeSessions, long expiredSessions) {
            this.activeSessions = activeSessions;
            this.expiredSessions = expiredSessions;
        }

        public long getActiveSessions() {
            return activeSessions;
        }

        public long getExpiredSessions() {
            return expiredSessions;
        }
    }
}