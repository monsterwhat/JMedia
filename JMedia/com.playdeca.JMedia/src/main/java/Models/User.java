package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
    
    @Transient
    private String password;

    // BCrypt hash generation
    public void setPassword(String password) {
        this.password = password;
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
}
