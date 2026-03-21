package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "session")
public class Session extends PanacheEntity {

    public String sessionId;
    public String userId;
    public String username;
    public String ipAddress;
    public Instant createdAt;
    public Instant lastActivity;
    public boolean active;

    public static Session findBySessionId(String sessionId) {
        return find("sessionId", sessionId).firstResult();
    }
    
    public Map<String, Object> toInfoMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", this.id);
        map.put("sessionId", this.sessionId);
        map.put("userId", this.userId);
        map.put("username", this.username);
        map.put("ipAddress", this.ipAddress);
        map.put("createdAt", this.createdAt != null ? this.createdAt.toString() : null);
        map.put("lastActivity", this.lastActivity != null ? this.lastActivity.toString() : null);
        map.put("active", this.active);
        return map;
    }
}
