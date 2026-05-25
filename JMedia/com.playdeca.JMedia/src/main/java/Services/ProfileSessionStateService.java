package Services;

import Models.Profile;
import Models.ProfileSessionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProfileSessionStateService {

    @Inject
    SettingsService settingsService;

    @Inject
    EntityManager em;

    @Transactional
    public ProfileSessionState getOrCreate() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return null;
        }

        ProfileSessionState state = ProfileSessionState.find("profile", activeProfile).firstResult();
        if (state == null) {
            state = new ProfileSessionState();
            state.profile = activeProfile;
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
}
