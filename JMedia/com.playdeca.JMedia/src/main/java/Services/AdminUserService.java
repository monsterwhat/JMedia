package Services;

import Models.Settings.Profile;
import Models.Settings.User;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the bootstrap administrator on a fresh install and heals installs that
 * still carry a password shipped in source by an older build.
 * <p>
 * There is exactly one bootstrap account. Older builds also created
 * {@code admin2} with a second hardcoded password; that account is removed when
 * it still uses the shipped password, and left alone when the owner has already
 * given it a real password of their own.
 */
@ApplicationScoped
public class AdminUserService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminUserService.class);

    private static final String BOOTSTRAP_ADMIN_USERNAME = "admin";
    private static final String LEGACY_SECOND_ADMIN_USERNAME = "admin2";

    /** Password shipped by older builds for {@code admin2}. */
    private static final String LEGACY_SECOND_ADMIN_PASSWORD = "custom password1234";

    /** Passwords shipped by older builds for the {@code admin} account. */
    private static final String[] LEGACY_ADMIN_PASSWORDS = {"changeme1234"};

    void onStart(@Observes StartupEvent ev) {
        bootstrapAdminUsers();
    }

    @Transactional
    public void bootstrapAdminUsers() {
        removeOrProtectLegacySecondAdmin();

        User existing = User.find("username", BOOTSTRAP_ADMIN_USERNAME).firstResult();
        if (existing == null) {
            createBootstrapAdmin();
            return;
        }

        // An install that predates this change still accepts a password that is
        // in the source tree. Replace it and require a change on next login;
        // the rest of the account (profiles, history, sessions) is untouched.
        for (String legacy : LEGACY_ADMIN_PASSWORDS) {
            if (existing.checkPassword(legacy)) {
                String replacement = BootstrapCredentials.generate();
                existing.setPassword(replacement);
                existing.setMustChangePassword(true);
                LOG.warn("Admin user '{}' still used a password shipped in source. A one-time password "
                        + "has been generated and a change is required at next login: {}",
                        BOOTSTRAP_ADMIN_USERNAME, replacement);
                return;
            }
        }

        LOG.debug("Admin user '{}' already exists. Leaving it untouched.", BOOTSTRAP_ADMIN_USERNAME);
    }

    private void createBootstrapAdmin() {
        String password = BootstrapCredentials.generate();

        User admin = new User();
        admin.setUsername(BOOTSTRAP_ADMIN_USERNAME);
        admin.setPassword(password);
        admin.setGroupName("admin");
        admin.setMustChangePassword(true);
        admin.persist();

        Profile mainProfile = new Profile();
        mainProfile.name = BOOTSTRAP_ADMIN_USERNAME;
        mainProfile.isMainProfile = true;
        mainProfile.userId = admin.id;
        mainProfile.persist();

        // The only place the generated password is ever written. It is logged
        // once, on the creation path, so it can be read from the service log.
        LOG.warn("Created bootstrap admin '{}'. One-time password (shown once): {}",
                BOOTSTRAP_ADMIN_USERNAME, password);
        LOG.warn("Sign in with it and set your own password. Every other request stays blocked until you do.");
    }

    /**
     * {@code admin2} was created by older builds with a password that is public
     * knowledge. Remove it while it still uses that password; if the owner has
     * changed it, keep the account and only log that it exists.
     */
    private void removeOrProtectLegacySecondAdmin() {
        User second = User.find("username", LEGACY_SECOND_ADMIN_USERNAME).firstResult();
        if (second == null) {
            return;
        }

        if (!second.checkPassword(LEGACY_SECOND_ADMIN_PASSWORD)) {
            LOG.info("Legacy second admin '{}' has its own password; leaving it in place.",
                    LEGACY_SECOND_ADMIN_USERNAME);
            return;
        }

        Long removedId = second.id;
        // Older builds always created a main profile alongside the account.
        // Delete it first so a profile -> user foreign key cannot block the removal.
        Profile mainProfile = Profile.findMainProfileByUser(removedId);
        long profiles = 0;
        if (mainProfile != null) {
            mainProfile.delete();
            profiles = 1;
        }
        second.delete();
        LOG.warn("Removed legacy admin '{}' (id={}), which still used a password shipped in source "
                + "and {} main profile(s) were deleted with it. Re-create it from user management if it was needed.",
                LEGACY_SECOND_ADMIN_USERNAME, removedId, profiles);
    }
}
