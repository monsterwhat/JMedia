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

    @Transactional
    public void updateSidebarPosition(Long profileId, String position) {
        Profile profile = Profile.findById(profileId);
        if (profile != null) {
            profile.sidebarPosition = position;
            profile.persist();
        }
    }
}
