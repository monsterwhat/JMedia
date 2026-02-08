package Services;

import Models.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;

@ApplicationScoped
public class AuthService {
    
    @Transactional
    public Optional<User> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        
        User user = User.find("username", username).firstResult();
        if (user != null && user.checkPassword(password)) {
            return Optional.of(user);
        }
        
        return Optional.empty();
    }
}