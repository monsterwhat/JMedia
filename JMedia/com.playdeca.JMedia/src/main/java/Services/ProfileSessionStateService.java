package Services;

import Models.Settings.Profile;
import Models.Video.ProfileSessionState;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProfileSessionStateService {

    @Inject
    SettingsService settingsService;

    @Inject
    @PersistenceUnit("video")
    EntityManager em;

    @Inject
    ProfileService profileService;

    @Transactional
    public ProfileSessionState getOrCreate() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return null;
        }

        ProfileSessionState state = ProfileSessionState.find("profileId", activeProfile.id).firstResult();
        if (state == null) {
            state = new ProfileSessionState();
            state.profileId = activeProfile.id;
            state.persist();
        }
        return state;
    }

    @Transactional
    public ProfileSessionState save(ProfileSessionState state) {
        if (state != null) {
            return em.merge(state);
        }
        return null;
    }

    // Resolve the session-state row for a profile WITHOUT enlisting both
    // persistence units in one transaction: the Profile existence check runs
    // in its own REQUIRES_NEW tx (default PU), the row lookup in this tx
    // (video PU). Returns the existing row, a fresh unsaved placeholder when
    // the profile exists but has no row yet, or null when the profile is gone.
    @Transactional
    public ProfileSessionState findByProfileId(Long profileId) {
        if (profileId == null) return null;
        Profile profile = profileService.findById(profileId);
        if (profile == null) return null;
        ProfileSessionState state = ProfileSessionState.find("profileId", profile.id).firstResult();
        if (state != null) return state;
        ProfileSessionState fresh = new ProfileSessionState();
        fresh.profileId = profile.id;
        return fresh;
    }
}
