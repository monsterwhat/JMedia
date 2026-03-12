package Services;

import Models.Profile;
import Models.User;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AdminUserService {
    
    private static final Logger LOG = LoggerFactory.getLogger(AdminUserService.class);
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "changeme1234";
    private static final String SECOND_ADMIN_USERNAME = "admin2";
    private static final String SECOND_ADMIN_PASSWORD = "custom password1234";
    
    void onStart(@Observes StartupEvent ev) {
        createDefaultAdminUsers();
    }
    
    @Transactional
    public void createDefaultAdminUsers() {
        createAdminIfMissing(DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        createAdminIfMissing(SECOND_ADMIN_USERNAME, SECOND_ADMIN_PASSWORD);
    }

    private void createAdminIfMissing(String username, String password) {
        User existing = User.find("username", username).firstResult();
        if (existing == null) {
            LOG.info("Creating admin user '{}'...", username);
            
            User adminUser = new User();
            adminUser.setUsername(username);
            adminUser.setPassword(password);
            adminUser.setGroupName("admin");
            
            adminUser.persist();
            
            Profile mainProfile = new Profile();
            mainProfile.name = username;
            mainProfile.isMainProfile = true;
            mainProfile.userId = adminUser.id;
            mainProfile.persist();
            
            LOG.info("Admin user '{}' created successfully.", username);
            if (DEFAULT_ADMIN_USERNAME.equals(username)) {
                LOG.warn("Please change the default password for '{}' after first login!", username);
            }
        } else {
            LOG.debug("Admin user '{}' already exists. Skipping creation.", username);
        }
    }
}