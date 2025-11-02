package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false) 
@Entity
public class Song extends PanacheEntity {

    private String title;
    private String artist;
    private String album;
    private int durationSeconds; // store as integer seconds
    private String path; // local file path or URL
    private String coverImagePath;
    private String genre;
    @Column(length = Integer.MAX_VALUE) // Set to max value to hint for CLOB/TEXT type
    private String artworkBase64;
    private java.time.LocalDateTime dateAdded;

    @Transient
    private byte[] artwork;

    // Playlist foreign key is managed by @JoinColumn in Playlist
}
