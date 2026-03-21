package Services;

import Models.DeviceSyncSession;
import Models.DTOs.DeviceSyncQrCodeDTO;
import Models.PendingDeviceSync;
import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Base64;
import java.util.logging.Logger;

@ApplicationScoped
public class DeviceSyncService {

    private static final Logger LOGGER = Logger.getLogger(DeviceSyncService.class.getName());
    private static final int DEFAULT_PORT = 80; // Default port, should be configurable
    private static final int QR_CODE_SIZE = 300; // QR code image size in pixels

    @Inject
    private ProfileService profileService;

    /**
     * Generate a new QR code for device sync
     */
    public DeviceSyncQrCodeDTO generateQrCodeData(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }
        
        String serverAddress = getLocalIpAddress();
        String securityKey = generateSecurityKey();
        
        PendingDeviceSync pendingSync = new PendingDeviceSync();
        pendingSync.securityKey = securityKey;
        pendingSync.profile = profile;
        pendingSync.persist();
        
        DeviceSyncQrCodeDTO qrData = new DeviceSyncQrCodeDTO(serverAddress, DEFAULT_PORT, securityKey);
        LOGGER.info("Generated QR code for profile " + profileId + " with security key: " + securityKey);
        
        return qrData;
    }

    /**
     * Convert QR code data to base64 image
     */
    public String generateQrCodeImage(DeviceSyncQrCodeDTO qrData) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(
                qrData.getQrCodeData(), 
                BarcodeFormat.QR_CODE, 
                QR_CODE_SIZE, 
                QR_CODE_SIZE
            );

            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            
            // Convert to base64
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", outputStream);
            
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
            
        } catch (WriterException | IOException e) {
            LOGGER.severe("Failed to generate QR code image: " + e.getMessage());
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    /**
     * Get the local IP address that would be accessible on the network
     */
    private String getLocalIpAddress() {
        try {
            // Try to find a non-loopback IPv4 address
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    
                    // Prefer IPv4 addresses and skip loopback
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') == -1) {
                        String ip = address.getHostAddress();
                        // Validate that it's a reasonable local network IP
                        if (isValidLocalIp(ip)) {
                            LOGGER.info("Found local IP address: " + ip + " on interface: " + networkInterface.getName());
                            return ip;
                        }
                    }
                }
            }
            
            // Fallback to localhost
            String fallbackIp = InetAddress.getLocalHost().getHostAddress();
            LOGGER.warning("No suitable network IP found, using fallback: " + fallbackIp);
            return fallbackIp;
            
        } catch (SocketException | UnknownHostException e) {
            LOGGER.severe("Failed to get local IP address: " + e.getMessage());
            return "127.0.0.1"; // Ultimate fallback
        }
    }

    /**
     * Validate if the IP is a reasonable local network IP
     */
    private boolean isValidLocalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        // Skip 169.254.x.x (APIPA) and 127.x.x.x (loopback)
        if (ip.startsWith("169.254.") || ip.startsWith("127.")) {
            return false;
        }
        
        // Accept common private network ranges
        return ip.startsWith("192.168.") || 
               ip.startsWith("10.") || 
               ip.startsWith("172.") ||
               ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    }

    /**
     * Generate a secure random key for device authentication
     */
    private String generateSecurityKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Get the server port (could be made configurable)
     */
    public int getServerPort() {
        // This could be read from configuration in a real implementation
        return DEFAULT_PORT;
    }

    /**
     * Get local IP address (public method for API)
     */
    public String getServerAddress() {
        return getLocalIpAddress();
    }

    /**
     * Validate a security key format
     */
    public boolean isValidSecurityKey(String key) {
        return key != null && key.length() == 16 && key.matches("[a-fA-F0-9]+");
    }

    @Transactional
    public DeviceSyncSession completeDeviceConnection(String securityKey, String deviceName, String deviceType, 
                                                     String ipAddress, String userAgent) {
        if (!isValidSecurityKey(securityKey)) {
            throw new IllegalArgumentException("Invalid security key");
        }

        DeviceSyncSession existingSession = DeviceSyncSession.find("securityKey = ?1 AND isActive = true AND expiresAt > ?2", 
                                                                    securityKey, LocalDateTime.now()).firstResult();

        if (existingSession != null) {
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

        PendingDeviceSync pendingSync = PendingDeviceSync.findValidBySecurityKey(securityKey);
        if (pendingSync == null) {
            throw new IllegalStateException("No pending device sync found for this security key");
        }
        
        Profile targetProfile = pendingSync.profile;
        pendingSync.delete();

        DeviceSyncSession session = new DeviceSyncSession(
            deviceName != null ? deviceName : "Unknown Device",
            deviceType != null ? deviceType : "unknown",
            ipAddress,
            userAgent != null ? userAgent : "Unknown",
            securityKey,
            targetProfile
        );

        session.persist();
        LOGGER.info("Created new device session " + session.id + " for device: " + deviceName + " with profile: " + targetProfile.id);
        return session;
    }

    @Transactional
    public List<DeviceSyncSession> getActiveSessions(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }
        return DeviceSyncSession.list("profile = ?1 AND isActive = true AND expiresAt > ?2", 
                                    profile, LocalDateTime.now());
    }

    @Transactional
    public boolean revokeSession(Long profileId, Long sessionId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }

        DeviceSyncSession session = DeviceSyncSession.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found");
        }

        if (!session.getProfile().id.equals(profileId)) {
            throw new IllegalArgumentException("Session does not belong to this profile");
        }

        session.deactivate();
        session.persist();
        LOGGER.info("Revoked device session: " + sessionId);
        return true;
    }

    @Transactional
    public int cleanupExpiredSessions(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }

        int updatedCount = DeviceSyncSession.update(
            "isActive = false WHERE profile = ?1 AND expiresAt <= ?2 AND isActive = true",
            profile, LocalDateTime.now()
        );

        LOGGER.info("Cleaned up " + updatedCount + " expired sessions for profile " + profileId);
        return updatedCount;
    }

    @Transactional
    public boolean updateSessionAccess(Long sessionId, String ipAddress) {
        DeviceSyncSession session = DeviceSyncSession.findById(sessionId);
        if (session == null || !session.isActive() || session.isExpired()) {
            return false;
        }

        session.updateLastAccessed(ipAddress);
        session.persist();
        return true;
    }

    @Transactional
    public DeviceSyncSession getSessionBySecurityKey(String securityKey) {
        if (!isValidSecurityKey(securityKey)) {
            return null;
        }
        return DeviceSyncSession.find("securityKey = ?1 AND isActive = true AND expiresAt > ?2", 
                                   securityKey, LocalDateTime.now()).firstResult();
    }

    @Transactional
    public DeviceSyncStats getDeviceSyncStats(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }

        long activeCount = DeviceSyncSession.count("profile = ?1 AND isActive = true AND expiresAt > ?2", 
                                                   profile, LocalDateTime.now());
        
        long expiredCount = DeviceSyncSession.count("profile = ?1 AND (isActive = false OR expiresAt <= ?2)", 
                                                    profile, LocalDateTime.now());

        return new DeviceSyncStats(activeCount, expiredCount);
    }

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