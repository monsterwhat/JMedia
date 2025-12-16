package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
public class DeviceSyncSession extends PanacheEntity {

    private String deviceName;
    private String deviceType; // "phone", "tablet", "computer", etc.
    private String currentIpAddress; // Current IP address (can change)
    private String userAgent;
    private String securityKey; // Unique security key for this session
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime expiresAt;
    private boolean isActive;
    
    // Sync preferences
    private boolean syncMusic = true;
    private boolean syncVideos = false;
    private boolean syncPlaylists = true;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private Profile profile;
    
    // Default constructor
    public DeviceSyncSession() {
        this.createdAt = LocalDateTime.now();
        this.lastAccessedAt = LocalDateTime.now();
        this.isActive = true;
        // Sessions expire after 24 hours by default
        this.expiresAt = LocalDateTime.now().plusHours(24);
    }
    
    // Constructor with required fields
    public DeviceSyncSession(String deviceName, String deviceType, String ipAddress, String userAgent, String securityKey, Profile profile) {
        this();
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.currentIpAddress = ipAddress;
        this.userAgent = userAgent;
        this.securityKey = securityKey;
        this.profile = profile;
    }
    
    // Check if session is expired
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    // Update last accessed time and IP address
    public void updateLastAccessed(String newIpAddress) {
        this.lastAccessedAt = LocalDateTime.now();
        if (newIpAddress != null && !newIpAddress.equals(this.currentIpAddress)) {
            this.currentIpAddress = newIpAddress;
        }
    }
    
    // Update last accessed time (overload for backward compatibility)
    public void updateLastAccessed() {
        this.lastAccessedAt = LocalDateTime.now();
    }
    
    // Deactivate session
    public void deactivate() {
        this.isActive = false;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DeviceSyncSession session = (DeviceSyncSession) o;
        return id != null && id.equals(session.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}