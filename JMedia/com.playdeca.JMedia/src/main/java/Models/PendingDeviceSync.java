package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class PendingDeviceSync extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String securityKey;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "profile_id", nullable = false)
    public Profile profile;
    
    public LocalDateTime createdAt;
    
    public LocalDateTime expiresAt;
    
    public PendingDeviceSync() {
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(10);
    }
    
    public static PendingDeviceSync findBySecurityKey(String key) {
        return find("securityKey", key).firstResult();
    }
    
    public static PendingDeviceSync findValidBySecurityKey(String key) {
        return find("securityKey = ?1 AND expiresAt > ?2", key, LocalDateTime.now()).firstResult();
    }
    
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
