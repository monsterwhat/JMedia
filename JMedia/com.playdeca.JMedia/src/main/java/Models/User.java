package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table; 
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.mindrot.jbcrypt.BCrypt;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "app_user")
public class User extends PanacheEntity {

    private String username;
    private String passwordHash;
    private String groupName;
    
    // Getter for username
    public String getUsername() {
        return username;
    }
    
    // Setter for username if needed
    public void setUsername(String username) {
        this.username = username;
    }
    
    // BCrypt hash generation
    public void setPassword(String password) { 
        this.passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
    }
    
    // BCrypt password verification
    public boolean checkPassword(String password) {
        if (passwordHash == null || passwordHash.isEmpty()) {
            return false;
        }
        return BCrypt.checkpw(password, this.passwordHash);
    }
    
    // Method to check if user has hashed password (for migration)
    public boolean isHashedPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }
    
    public String getGroupName() {
        return groupName;
    }
    
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
    
    public String getPasswordHash() {
        return passwordHash;
    }
}