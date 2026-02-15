package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

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
}
