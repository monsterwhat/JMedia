package Models.Settings;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "xtream_session")
public class XtreamSession extends PanacheEntity {

    public String sessionId;
    public String userId;
    public String username;
    public String type;
    public Long streamId;
    public String ext;
    public String ipAddress;
    public Instant startedAt;
    public Instant endedAt;
    public long durationSeconds;
    public String endReason;
    public boolean active;
    public Instant lastActivity;

    public static XtreamSession findBySessionId(String sessionId) {
        return find("sessionId", sessionId).firstResult();
    }

    public Map<String, Object> toInfoMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", this.id);
        map.put("sessionId", this.sessionId);
        map.put("userId", this.userId);
        map.put("username", this.username);
        map.put("type", this.type);
        map.put("streamId", this.streamId);
        map.put("ext", this.ext);
        map.put("ipAddress", this.ipAddress);
        map.put("startedAt", this.startedAt != null ? this.startedAt.toString() : null);
        map.put("endedAt", this.endedAt != null ? this.endedAt.toString() : null);
        map.put("durationSeconds", this.durationSeconds);
        map.put("endReason", this.endReason);
        map.put("active", this.active);
        map.put("lastActivity", this.lastActivity != null ? this.lastActivity.toString() : null);
        return map;
    }
}