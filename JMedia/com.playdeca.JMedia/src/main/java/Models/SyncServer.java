package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

@Entity
public class SyncServer extends PanacheEntity {

    @Column(nullable = false, length = 255)
    public String name;

    @Column(nullable = false, length = 500)
    public String url;

    @Column(nullable = false, length = 255)
    public String apiKey;

    public boolean enabled = true;

    public LocalDateTime lastSyncAt;

    public String lastSyncStatus;

    @Column(length = 1000)
    public String lastSyncError;

}
