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
public class VideoState extends PanacheEntity {

    @OneToOne
    @JoinColumn(name = "profile_id", referencedColumnName = "id")
    private Profile profile;

    private boolean playing;
    private Long currentVideoId; // Changed from currentSongId
    private Long currentPlaylistId; // Keeping this, as there might be video playlists
    private String videoTitle;     // Changed from songName
    private String seriesTitle;    // Added for TV shows
    private String episodeTitle;   // Added for TV shows
    private double currentTime;
    private double duration;
    private float volume;
    private long lastUpdateTime;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> cue = new ArrayList<>(); // Queue of video IDs

    private int cueIndex;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> lastVideos = new ArrayList<>(); // Changed from lastSongs

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> originalCue = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private RepeatMode repeatMode = RepeatMode.OFF;

    public enum RepeatMode {
        OFF, // no repeat
        ONE, // repeat current video
        ALL        // repeat playlist
    }

    // Removed ShuffleMode as it might not be applicable for sequential video playback (e.g., episodes)
    // If shuffling is desired for movies, it can be added later.
}
