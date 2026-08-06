package Services;

import Models.Series;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class SeriesService {

    private static final Logger LOG = LoggerFactory.getLogger(SeriesService.class);

    @Inject
    VideoMetadataService videoMetadataService;

    @Transactional
    public Series create(Series input) {
        Series series = new Series();
        series.title = input.title.trim();
        series.description = input.description;
        series.tagline = input.tagline;
        series.overview = input.overview;
        series.genres = input.genres;
        series.directors = input.directors;
        series.writers = input.writers;
        series.cast = input.cast;
        series.productionCompanies = input.productionCompanies;
        series.networks = input.networks;
        series.imdbRating = input.imdbRating;
        series.tmdbRating = input.tmdbRating;
        series.metacriticRating = input.metacriticRating;
        series.voteCount = input.voteCount;
        series.popularityScore = input.popularityScore;
        series.releaseYear = input.releaseYear;
        series.runtimeMins = input.runtimeMins;
        series.mpaaRating = input.mpaaRating;
        series.status = input.status;
        series.originalLanguage = input.originalLanguage;
        series.productionCountries = input.productionCountries;
        series.releaseDate = input.releaseDate;
        series.trailerUrl = input.trailerUrl;
        series.parentsGuide = input.parentsGuide;
        series.imdbId = input.imdbId;
        series.tmdbId = input.tmdbId;
        series.tvdbId = input.tvdbId;
        series.budget = input.budget;
        series.revenue = input.revenue;
        series.collectionName = input.collectionName;
        series.franchiseName = input.franchiseName;
        series.logoPath = input.logoPath;
        series.posterPath = input.posterPath;
        series.backdropPath = input.backdropPath;
        series.heroPath = input.heroPath;
        series.fanartPath = input.fanartPath;
        series.stillPath = input.stillPath;
        series.akas = input.akas;
        series.keywords = input.keywords;

        series.persist();
        initializeCollections(series);
        LOG.info("Created series: {} (id={})", series.title, series.id);
        return series;
    }

    @Transactional
    public Series update(Long id, Series input) {
        Series series = Series.findById(id);
        if (series == null) {
            return null;
        }

        if (input.title != null && !input.title.trim().isEmpty()) {
            series.title = input.title.trim();
        }
        if (input.description != null) series.description = input.description;
        if (input.tagline != null) series.tagline = input.tagline;
        if (input.overview != null) series.overview = input.overview;
        if (input.genres != null) series.genres = input.genres;
        if (input.directors != null) series.directors = input.directors;
        if (input.writers != null) series.writers = input.writers;
        if (input.cast != null) series.cast = input.cast;
        if (input.productionCompanies != null) series.productionCompanies = input.productionCompanies;
        if (input.networks != null) series.networks = input.networks;
        if (input.imdbRating != null) series.imdbRating = input.imdbRating;
        if (input.tmdbRating != null) series.tmdbRating = input.tmdbRating;
        if (input.metacriticRating != null) series.metacriticRating = input.metacriticRating;
        if (input.voteCount != null) series.voteCount = input.voteCount;
        if (input.popularityScore != null) series.popularityScore = input.popularityScore;
        if (input.releaseYear != null) series.releaseYear = input.releaseYear;
        if (input.runtimeMins != null) series.runtimeMins = input.runtimeMins;
        if (input.mpaaRating != null) series.mpaaRating = input.mpaaRating;
        if (input.status != null) series.status = input.status;
        if (input.originalLanguage != null) series.originalLanguage = input.originalLanguage;
        if (input.productionCountries != null) series.productionCountries = input.productionCountries;
        if (input.releaseDate != null) series.releaseDate = input.releaseDate;
        if (input.trailerUrl != null) series.trailerUrl = input.trailerUrl;
        if (input.parentsGuide != null) series.parentsGuide = input.parentsGuide;
        if (input.imdbId != null) series.imdbId = input.imdbId;
        if (input.tmdbId != null) series.tmdbId = input.tmdbId;
        if (input.tvdbId != null) series.tvdbId = input.tvdbId;
        if (input.budget != null) series.budget = input.budget;
        if (input.revenue != null) series.revenue = input.revenue;
        if (input.collectionName != null) series.collectionName = input.collectionName;
        if (input.franchiseName != null) series.franchiseName = input.franchiseName;
        if (input.logoPath != null) series.logoPath = input.logoPath;
        if (input.posterPath != null) series.posterPath = input.posterPath;
        if (input.backdropPath != null) series.backdropPath = input.backdropPath;
        if (input.heroPath != null) series.heroPath = input.heroPath;
        if (input.fanartPath != null) series.fanartPath = input.fanartPath;
        if (input.stillPath != null) series.stillPath = input.stillPath;
        if (input.akas != null) series.akas = input.akas;
        if (input.keywords != null) series.keywords = input.keywords;

        initializeCollections(series);
        LOG.info("Updated series: {} (id={})", series.title, series.id);
        return series;
    }

    @Transactional
    public boolean delete(Long id) {
        Series series = Series.findById(id);
        if (series == null) {
            return false;
        }

        // Unlink episodes — set series_id=null, don't delete episodes
        Video.update("series = null WHERE series = ?1", series);

        series.delete();
        LOG.info("Deleted series: {} (id={}) — episodes unlinked", series.title, id);
        return true;
    }

    @Transactional
    public Series find(Long id) {
        try {
            videoMetadataService.ensureSeriesTextMetadata(id);
        } catch (Exception e) {
            LOG.debug("Could not enrich series text metadata for {}: {}", id, e.getMessage());
        }
        Series series = Series.findById(id);
        if (series != null) {
            initializeCollections(series);
        }
        return series;
    }

    @Transactional
    public SeriesWithEpisodes findEpisodes(Long id) {
        SeriesWithEpisodes result = new SeriesWithEpisodes();
        result.series = Series.findById(id);
        if (result.series == null) {
            return result;
        }

        result.episodes = Video.find("series = ?1 AND (contentType IS NULL OR contentType = 'episode') ORDER BY seasonNumber ASC, episodeNumber ASC", result.series)
                .list();
        return result;
    }

    /**
     * Result holder for findEpisodes: the series (null when not found) plus its
     * episodes. Series is loaded and returned here so the API layer never touches
     * the entity manager directly.
     */
    public static class SeriesWithEpisodes {
        public Series series;
        public List<Video> episodes;
    }

    /**
     * Initializes the 8 lazy @ElementCollection fields before the entity leaves the
     * transaction, so the API receives a detached entity whose collections are safe
     * to serialize. The videos @OneToMany is intentionally NOT initialized (it is
     * @JsonIgnore and lazy-loading every episode here would be wasteful).
     */
    private void initializeCollections(Series series) {
        if (series.genres != null) Hibernate.initialize(series.genres);
        if (series.directors != null) Hibernate.initialize(series.directors);
        if (series.writers != null) Hibernate.initialize(series.writers);
        if (series.cast != null) Hibernate.initialize(series.cast);
        if (series.productionCompanies != null) Hibernate.initialize(series.productionCompanies);
        if (series.networks != null) Hibernate.initialize(series.networks);
        if (series.akas != null) Hibernate.initialize(series.akas);
        if (series.keywords != null) Hibernate.initialize(series.keywords);
    }
}
