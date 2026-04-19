package Models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Transient;
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
    private long serverTime;
    private double duration;  
    private float volume;
    @Enumerated(EnumType.STRING)
    private ShuffleMode shuffleMode = ShuffleMode.OFF; 
    private long lastUpdateTime;  
     

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> cue = new ArrayList<>();

    private int cueIndex;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> lastSongs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> originalCue = new ArrayList<>();

    // Enhanced Smart Shuffle tracking (transient - not persisted)
    @Transient
    private String lastPlayedAlbum;
    @Transient
    private String lastPlayedArtist;
    @Transient
    private int consecutiveAlbumPlays = 0;
    @Transient
    private int consecutiveArtistPlays = 0;

    @Enumerated(EnumType.STRING)
    private RepeatMode repeatMode = RepeatMode.OFF;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> secondaryCue = new ArrayList<>();

    private int secondaryCueIndex = -1;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<Long> secondaryOriginalCue = new ArrayList<>();

    private boolean usingSecondaryQueue = false;

    private Integer crossfadeDuration = 0;

    // DJ Mode state tracking
    private Boolean djModeActive = false;
    private Integer originalCrossfadeDuration = 0;

    // DJ Mode transition planning (beat-aligned cross-song transitions)
    private Long djNextSongId;           // The next song to transition into
    private Double djEntryTime;          // Where in the next song to start (seconds)
    private Double djExitTime;           // Where in the current song to start crossfade
    private Boolean djTransitionPlanned; // Whether a transition has been calculated
    private Double djTransitionConfidence; // 0.0-1.0 confidence of the match
    private String djTransitionReason;   // Human-readable explanation of the match

    public enum ShuffleMode {
        OFF,
        SHUFFLE,
        SMART_SHUFFLE
    } 
    
    public enum RepeatMode {
        OFF, // no repeat
        ONE, // repeat current song
        ALL  // repeat playlist
    }
 
}
