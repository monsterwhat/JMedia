package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
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

    @Column(length = 50)
    public String syncType = "ALL";

    public int limitCount;

    public int totalItems;
    public int itemsProcessed;

    public int songsSent;
    public int songsReceived;
    public int songsUpdated;
    public int songsCreated;

    public int videosSent;
    public int videosReceived;
    public int videosUpdated;
    public int videosCreated;

    public int collectionsSent;
    public int collectionsReceived;

    public int playlistsSent;
    public int playlistsReceived;

    public int subtitlesSent;
    public int subtitlesReceived;

    @Lob
    public String syncedItemIds;

    @Column(length = 2000)
    public String errorMessage;

}
