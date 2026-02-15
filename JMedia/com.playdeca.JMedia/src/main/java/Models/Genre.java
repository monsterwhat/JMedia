package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "genre",
       indexes = {
           @Index(name = "idx_genre_name", columnList = "name"),
           @Index(name = "idx_genre_slug", columnList = "slug"),
           @Index(name = "idx_genre_parent", columnList = "parent_genre_id"),
           @Index(name = "idx_genre_active", columnList = "isActive"),
           @Index(name = "idx_genre_sort", columnList = "sortOrder")
       })
public class Genre extends PanacheEntity {

    public String name;
    public String slug; // URL-friendly: "action-adventure"
    public String description;
    public String color; // For UI theming
    public String icon; // FontAwesome or similar
    public Integer sortOrder;
    public boolean isActive = true;
    
    // Hierarchy Support
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_genre_id")
    public Genre parentGenre; // For hierarchy
    
    @OneToMany(mappedBy = "parentGenre", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    public List<Genre> subGenres;
}