package Services;

import Models.Music.PlaybackHistory;
import Models.Music.PlaybackState;
import Models.Music.Playlist;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Music-persistence-unit side of profile deletion.
 *
 * <p>Profile data lives in two databases: the profile row itself is in the settings
 * PU, while playlists, playback history and playback state are in the music PU. A
 * single JTA transaction cannot enlist both, which is why
 * {@code ProfileAPI.deleteProfile} failed with
 * "Unable to acquire JDBC Connection [Exception in association of connection to
 * existing transaction]" once the settings connection was already enlisted.
 *
 * <p>This service therefore runs as its own transaction that touches ONLY music
 * entities. The convention matches {@link PlaybackHistoryService}: a plain
 * {@code @Transactional} method whose only datasource is the music one.
 */
@ApplicationScoped
public class ProfileMusicDataService {

    private static final Logger LOG = Logger.getLogger(ProfileMusicDataService.class);

    /**
     * Moves everything a profile owns to another profile and drops its playback state.
     *
     * @return the number of playlist and history rows that were moved
     */
    @Transactional
    public int reassignProfileData(Long fromProfileId, Long toProfileId) {
        if (fromProfileId == null || toProfileId == null) {
            throw new IllegalArgumentException("Both profile ids are required to reassign music data");
        }
        if (fromProfileId.equals(toProfileId)) {
            LOG.warnf("Refusing to reassign music data from profile %d to itself", fromProfileId);
            return 0;
        }

        List<Playlist> playlists = Playlist.list("profileId", fromProfileId);
        for (Playlist playlist : playlists) {
            playlist.setProfileId(toProfileId);
            playlist.persist();
        }

        List<PlaybackHistory> history = PlaybackHistory.list("profileId", fromProfileId);
        for (PlaybackHistory entry : history) {
            entry.profileId = toProfileId;
            entry.persist();
        }

        PlaybackState state = PlaybackState.find("profileId", fromProfileId).firstResult();
        if (state != null) {
            state.delete();
        }

        int moved = playlists.size() + history.size();
        LOG.infof("Reassigned music data from profile %d to profile %d (%d playlist/history rows, playback state %s)",
                fromProfileId, toProfileId, moved, state != null ? "removed" : "absent");
        return moved;
    }
}
