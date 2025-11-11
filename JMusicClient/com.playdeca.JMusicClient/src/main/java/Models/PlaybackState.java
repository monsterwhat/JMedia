package Models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaybackState {

    @Id
    private Long id = 1L; // singleton

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
