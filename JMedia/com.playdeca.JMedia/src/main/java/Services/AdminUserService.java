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
    
    void onStart(@Observes StartupEvent ev) {
        createDefaultAdminUser();
    }
    
    @Transactional
    public void createDefaultAdminUser() {
        long userCount = User.count();
        if (userCount == 0) {
            LOG.info("No users found. Creating default admin user...");
            
            User adminUser = new User();
            adminUser.setUsername(DEFAULT_ADMIN_USERNAME);
            adminUser.setPassword(DEFAULT_ADMIN_PASSWORD);
            adminUser.setGroupName("admin");
            
            adminUser.persist();
            
            Profile mainProfile = new Profile();
            mainProfile.name = DEFAULT_ADMIN_USERNAME;
            mainProfile.isMainProfile = true;
            mainProfile.userId = adminUser.id;
            mainProfile.persist();
            
            LOG.info("Default admin user created successfully.");
            LOG.warn("Please change the default password after first login!");
        } else {
            LOG.debug("Users already exist. Skipping admin user creation.");
        }
    }
}