package Services;

import Models.Settings.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class ProfileService {

    @Transactional
    public Profile createProfile(String name, Long userId) {
        if (Profile.findByNameAndUser(name, userId) != null) {
            throw new IllegalArgumentException("Profile with name " + name + " already exists.");
        }
        Profile profile = new Profile();
        profile.name = name;
        profile.isMainProfile = false;
        profile.userId = userId;
        profile.persist();
        return profile;
    }

    // NOT_SUPPORTED on purpose: settings-DB read called from inside music/video-DB
    // transactions (e.g. PlaybackStateService.getOrCreateState). REQUIRES_NEW would
    // suspend/resume the caller's JTA transaction mid-flight and corrupt its enlisted
    // connections -> "Enlisted connection used without active transaction" (XAER_RMERR).
    // Same convention as SettingsService.getActiveProfile(). Returns a detached Profile.
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Profile findById(Long id) {
        return Profile.findById(id);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public List<Profile> findByUserId(Long userId) {
        return Profile.list("userId", userId);
    }

    @Transactional
    public List<Profile> findAll() {
        return Profile.listAll();
    }

    public Profile findMainProfileByUser(Long userId) {
        return Profile.findMainProfileByUser(userId);
    }

    @Transactional
    public void deleteByUserId(Long userId) {
        List<Profile> profiles = Profile.list("userId", userId);
        for (Profile profile : profiles) {
            profile.delete();
        }
    }

    /**
     * Deletes a single profile row. Runs in its own settings-PU transaction and
     * re-reads the row so the entity is managed: callers hold a detached profile
     * (findById is NOT_SUPPORTED), and the music-side data must already have been
     * moved by {@link ProfileMusicDataService} in a separate transaction.
     */
    @Transactional
    public boolean deleteProfile(Long profileId) {
        Profile profile = Profile.findById(profileId);
        if (profile == null) {
            return false;
        }
        profile.delete();
        return true;
    }

    /**
     * Renames a profile. Callers hold a detached profile (getActiveProfile/findById
     * are NOT_SUPPORTED), so persisting it directly would fail; this re-reads it
     * inside the transaction. Returns the updated profile, or null if it is gone.
     */
    @Transactional
    public Profile renameProfile(Long profileId, String name) {
        Profile profile = Profile.findById(profileId);
        if (profile == null) {
            return null;
        }
        profile.name = name;
        return profile;
    }

    /**
     * Adds or removes a hidden playlist for a profile. Same detached-entity reason
     * as {@link #renameProfile}: the caller's profile came from a NOT_SUPPORTED read.
     */
    @Transactional
    public boolean setPlaylistHidden(Long profileId, Long playlistId, boolean hidden) {
        Profile profile = Profile.findById(profileId);
        if (profile == null) {
            return false;
        }
        if (hidden) {
            profile.addHiddenPlaylist(playlistId);
        } else {
            profile.removeHiddenPlaylist(playlistId);
        }
        profile.persist();
        return true;
    }

    @Transactional
    public void updateSidebarPosition(Long profileId, String position) {
        Profile profile = Profile.findById(profileId);
        if (profile != null) {
            profile.sidebarPosition = position;
            profile.persist();
        }
    }

    @Transactional
    public void updateHlsStreaming(Long profileId, boolean enabled) {
        Profile profile = Profile.findById(profileId);
        if (profile != null) {
            profile.hlsStreaming = enabled;
            profile.persist();
        }
    }
}
