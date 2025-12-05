package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "shows")
public class Show extends PanacheEntity {

    public String name;

    @OneToMany(mappedBy = "show", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<Season> seasons;
}
