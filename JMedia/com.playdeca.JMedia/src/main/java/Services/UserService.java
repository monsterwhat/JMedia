package Services;

import Models.Profile;
import Models.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class UserService {
    
    public List<User> listAll() {
        return User.listAll();
    }
    
    public User findById(Long id) {
        return User.findById(id);
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
        }
        
        if (groupName != null && !groupName.isEmpty()) {
            user.setGroupName(groupName);
        }
        
        return user;
    }
    
    @Transactional
    public void delete(Long id) {
        User user = User.findById(id);
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        
        if ("admin".equals(user.getGroupName())) {
            throw new RuntimeException("Cannot delete admin users");
        }
        
        user.delete();
    }
}
