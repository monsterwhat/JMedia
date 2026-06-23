package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class SyncLog extends PanacheEntity {

    @ManyToOne
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("apiKey")
    public SyncServer server;

    public LocalDateTime startedAt;

    public LocalDateTime completedAt;

    public String status;

    public int songsSent;

    public int songsReceived;

    public int songsUpdated;

    public int songsCreated;

    @jakarta.persistence.Column(length = 2000)
    public String errorMessage;

}
