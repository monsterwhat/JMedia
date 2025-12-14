package Services;

import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ProfileService {

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        // Create the main profile if it doesn't exist
        if (Profile.count() == 0) {
            Profile mainProfile = new Profile();
            mainProfile.name = "Main";
            mainProfile.isMainProfile = true;
            mainProfile.persist();
        }
    }

    @Transactional
    public Profile createProfile(String name) {
        if (Profile.findByName(name) != null) {
            throw new IllegalArgumentException("Profile with name " + name + " already exists.");
        }
        Profile profile = new Profile();
        profile.name = name;
        profile.isMainProfile = false;
        profile.persist();
        return profile;
    }

    public Profile findByName(String name) {
        return Profile.findByName(name);
    }

    public Profile findById(Long id) {
        return Profile.findById(id);
    }

    public Profile getMainProfile() {
        return Profile.findMainProfile();
    }
}
