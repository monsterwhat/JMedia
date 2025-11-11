package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
public class Playlist extends PanacheEntity {

    // 'id' is already inherited from PanacheEntity
    private String name;
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "playlist_song",
            joinColumns = @JoinColumn(name = "playlist_id"),
            inverseJoinColumns = @JoinColumn(name = "song_id")
    )
    private List<Song> songs = new ArrayList<>();

    @Transient // Not to be persisted
    private boolean containsSong;

    // Getter for containsSong (Lombok will generate for @Data, but explicit for clarity/control)
    public boolean getContainsSong() {
        return containsSong;
    }

    // Setter for containsSong
    public void setContainsSong(boolean containsSong) {
        this.containsSong = containsSong;
    }
}
