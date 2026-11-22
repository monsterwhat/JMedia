package Services;

import Models.Settings.Profile;
import Models.Settings.Session;
import Models.Settings.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    @Inject
    SessionService sessionService;
    
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<User> listAll() {
        return User.listAll();
    }
    
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public User findById(Long id) {
        return User.findById(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public User findByUsername(String username) {
        return User.find("username", username).firstResult();
    }
    
    @Transactional
    public User create(String username, String password, String groupName) {
        User existing = User.find("username", username).firstResult();
        if (existing != null) {
            throw new RuntimeException("Username already exists");
        }
        
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setGroupName(groupName);
        user.persist();
        
        Profile mainProfile = new Profile();
        mainProfile.name = username;
        mainProfile.isMainProfile = true;
        mainProfile.userId = user.id;
        mainProfile.persist();
        
        return user;
    }
    
    @Transactional
    public User update(Long id, String username, String password, String groupName) {
        User user = User.findById(id);
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        
        if (username != null && !username.isEmpty()) {
            User existing = User.find("username", username).firstResult();
            if (existing != null && !existing.id.equals(id)) {
                throw new RuntimeException("Username already exists");
            }
            user.setUsername(username);
        }
        
        if (password != null && !password.isEmpty()) {
            user.setPassword(password);
            Session.delete("userId = ?1", String.valueOf(id));
        }
        
        if (groupName != null && !groupName.isEmpty()) {
            user.setGroupName(groupName);
        }
        
        return user;
    }
    
    @Transactional
    public void delete(Long id) {        User user = User.findById(id);
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        
        if ("admin".equals(user.getGroupName())) {
            throw new RuntimeException("Cannot delete admin users");
        }
        
        Profile.delete("userId = ?1", id);
        Session.delete("userId = ?1", String.valueOf(id));
        user.delete();
    }

    /**
     * True when the account behind this session must still replace its
     * bootstrap password. Runs in its own transaction because the caller is a
     * request filter, not a resource method.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean requiresPasswordChange(String sessionId) {
        Session session = Session.findBySessionId(sessionId);
        if (session == null || session.username == null) {
            return false;
        }
        User user = User.find("username", session.username).firstResult();
        return user != null && user.isPasswordChangeRequired();
    }

    /**
     * Replace a user's password with one they chose themselves and clear the
     * bootstrap flag that was blocking the rest of the application.
     * <p>
     * Sessions are intentionally not dropped: the caller is this same user, and
     * their cookie carries the account, not the password.
     *
     * @return true when the password was stored, false when the account is gone
     */
    @Transactional
    public boolean changePassword(String username, String newPassword) {
        User user = User.find("username", username).firstResult();
        if (user == null) {
            LOG.warn("Cannot change password: user '{}' no longer exists", username);
            return false;
        }

        user.setPassword(newPassword);
        user.setMustChangePassword(false);
        LOG.info("Password changed for user '{}' (bootstrap flag cleared).", username);
        return true;
    }
}
