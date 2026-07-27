package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
import java.util.Set;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "series", indexes = {
    @Index(name = "idx_series_title", columnList = "title"),
    @Index(name = "idx_series_imdb_id", columnList = "imdbId"),
    @Index(name = "idx_series_tmdb_id", columnList = "tmdbId")
})
public class Series extends PanacheEntity {

    // Images (all per-show, nullable)
    public String logoPath;
    public String posterPath;
    public String backdropPath;
    public String heroPath;
    public String fanartPath;
    public String stillPath;

    // Titles and Descriptions
    @Column(nullable = false)
    public String title;

    @Column(columnDefinition = "TEXT")
    public String description;

    public String tagline;

    @Column(columnDefinition = "TEXT")
    public String overview;

    // People / Metadata
    @ElementCollection
    @CollectionTable(name = "series_genres")
    @Column(name = "genre")
    public List<String> genres;

    @ElementCollection
    @CollectionTable(name = "series_directors")
    @Column(name = "director")
    public List<String> directors;

    @ElementCollection
    @CollectionTable(name = "series_writers")
    @Column(name = "writer")
    public List<String> writers;

    @ElementCollection
    @CollectionTable(name = "series_cast")
    @Column(name = "cast_member")
    public List<String> cast;

    @ElementCollection
    @CollectionTable(name = "series_production_companies")
    @Column(name = "company")
    public List<String> productionCompanies;

    @ElementCollection
    @CollectionTable(name = "series_networks")
    @Column(name = "network")
    public List<String> networks;

    // Ratings
    public Double imdbRating;
    public Double tmdbRating;
    public Double metacriticRating;
    public Integer voteCount;
    public Double popularityScore;

    // Metadata
    public Integer releaseYear;
    public Integer runtimeMins;
    public String mpaaRating;
    public String status; // "Continuing", "Ended", etc.
    public String originalLanguage;
    public String productionCountries;
    public String releaseDate;
    public String trailerUrl;
    @Column(length = 2000)
    public String parentsGuide;

    // External IDs
    public String imdbId;
    public Integer tmdbId;
    public Integer tvdbId;

    // Financial
    public Long budget;
    public Long revenue;

    // Collections
    public String collectionName;
    public String franchiseName;

    // ElementCollections
    @ElementCollection
    @CollectionTable(name = "series_akas")
    @Column(name = "aka")
    public Set<String> akas;

    @ElementCollection
    @CollectionTable(name = "series_keywords")
    @Column(name = "keyword")
    public Set<String> keywords;

    // Relationships
    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, orphanRemoval = false, fetch = FetchType.LAZY)
    public List<Video> videos;
}
