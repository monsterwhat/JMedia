package Services;

import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class ProfileService {

    @Transactional
    public Profile createProfile(String name, Long userId) {
        if (Profile.findByName(name) != null) {
            throw new IllegalArgumentException("Profile with name " + name + " already exists.");
        }
        Profile profile = new Profile();
        profile.name = name;
        profile.isMainProfile = false;
        profile.userId = userId;
        profile.persist();
        return profile;
    }

    @Transactional
    public Profile createProfile(String name) {
        return createProfile(name, null);
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

    public List<Profile> findByUserId(Long userId) {
        return Profile.list("userId", userId);
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
}
