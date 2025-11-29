package Models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = false)
public class PlaybackState extends PanacheEntity {

    @OneToOne
    @JoinColumn(name = "profile_id", referencedColumnName = "id")
    private Profile profile;

    private boolean playing;
    private Long currentSongId;
    private Long currentPlaylistId;
    private String songName;
    private String artistName;
    private double currentTime;
    private double duration; // Added duration field
    private float volume;
    @Enumerated(EnumType.STRING)
    private ShuffleMode shuffleMode = ShuffleMode.OFF;
    private long lastUpdateTime; // Timestamp of the last state update on the server


    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> cue = new ArrayList<>();

    private int cueIndex;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> lastSongs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> originalCue = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private RepeatMode repeatMode = RepeatMode.OFF;

    public enum ShuffleMode {
        OFF,
        SHUFFLE,
        SMART_SHUFFLE
    }

    public enum RepeatMode {
        OFF, // no repeat
        ONE, // repeat current song
        ALL        // repeat playlist
    }

}
