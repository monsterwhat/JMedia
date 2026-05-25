package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "profile_id"))
public class ProfileSessionState extends PanacheEntity {

    @ManyToOne
    @JoinColumn(name = "profile_id")
    public Profile profile;

    public Long currentVideoId;
    public boolean playing;
    public float volume = 1.0f;
    public List<Long> cue = new ArrayList<>();
    public int cueIndex = 0;
    public double currentTime; // Current playback position for active video (seconds)

    @ElementCollection(fetch = FetchType.EAGER)
    public List<Long> lastVideos = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    public List<Long> originalCue = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    public RepeatMode repeatMode = RepeatMode.OFF;

    public enum RepeatMode {
        OFF,
        ONE,
        ALL
    }

    // Collection playback tracking
    public Long collectionId;

    // Audio preference fields for cross-episode memory
    public String preferredAudioLanguage;
    public Long defaultAudioTrackId;
}
