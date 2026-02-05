package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity; 
import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity
@Data
@EqualsAndHashCode(callSuper =false) 
public class Profile extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String name;

    @Column(nullable = false)
    public boolean isMainProfile;

    public static Profile findMainProfile() {
        return find("isMainProfile", true).firstResult();
    }

    public static Profile findByName(String name) {
        return find("name", name).firstResult();
    }
}
