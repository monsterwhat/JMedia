package Services;

import Models.Settings.Session;
import Models.Settings.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.HttpHeaders;
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

    @Transactional
    public boolean isAdmin(HttpHeaders headers) {
        User user = getCurrentUser(headers);
        return user != null && "admin".equals(user.getGroupName());
    }

    @Transactional
    public User getCurrentUser(HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        if (sessionId == null) return null;
        Session session = Session.findBySessionId(sessionId);
        if (session == null || !session.active) return null;
        return User.find("username", session.username).firstResult();
    }
}
