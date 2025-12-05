package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "seasons")
public class Season extends PanacheEntity {

    public int seasonNumber;

    @ManyToOne
    public Show show;

    @OneToMany(mappedBy = "season", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<Episode> episodes;
}
