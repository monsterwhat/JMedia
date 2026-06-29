package Services;

import Models.Video;
import Models.MediaFile;
import Services.SmartNamingService;
import Models.Genre;
import Models.VideoGenre;
import Models.SubtitleTrack;
import Models.Profile;
import Models.UserSubtitlePreferences;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VideoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoService.class);

    @PersistenceContext
    private EntityManager em;

    @Inject
    UserInteractionService userInteractionService;

    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;

    @Inject
    SubtitleDiscoveryQueueProcessor subtitleDiscoveryProcessor;

    @Inject
    SettingsService settingsService;

    // ========== CORE VIDEO OPERATIONS ==========
    
    @Transactional
    public List<Video> findAll() {
        return Video.listAll();
    }

    @Transactional
    public List<Video> findActive() {
        return Video.list("isActive", true);
    }

    @Transactional
    public long countActive() {
        return Video.count("isActive", true);
    }

    @Transactional
    public Video findById(Long id) {
        return Video.findById(id);
    }

    @Transactional
    public List<Video> findBySeries(String seriesTitle) {
        return Video.list("seriesTitle = ?1 and type = ?2", seriesTitle, "episode");
    }

    @Transactional
    public List<Video> findBySeriesAndSeason(String seriesTitle, Integer seasonNumber) {
        return Video.list("seriesTitle = ?1 and seasonNumber = ?2 and type = ?3", seriesTitle, seasonNumber, "episode");
    }

    @Transactional
    public List<Video> findBySeriesAndSeasonAndEpisode(String seriesTitle, Integer seasonNumber, Integer episodeNumber) {
        return Video.list("seriesTitle = ?1 and seasonNumber = ?2 and episodeNumber = ?3 and type = ?4", 
            seriesTitle, seasonNumber, episodeNumber, "episode");
    }

    @Transactional
    public void updateAudioTrackPreference(Long videoId, Long trackId, String language) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.defaultAudioTrackId = trackId;
            if (language != null) {
                video.preferredAudioLanguage = language;
            }
            video.persist();
        }
    }

    @Transactional
    public void persistVideo(Video video) {
        video.persist();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> findAllVideoIds() {
        return em.createQuery("SELECT v.id FROM Video v ORDER BY v.id", Long.class)
                .getResultList();
    }

    // ========== AI SUBTITLE QUERIES ==========

    @Transactional
    public List<Video> findVideosWithAiSubtitles(int page, int limit) {
        return Video.find("SELECT DISTINCT v FROM Video v JOIN v.subtitleTracks st WHERE st.isAiGenerated = true ORDER BY v.dateAdded DESC")
                .page(Page.of(page, limit))
                .list();
    }

    @Transactional
    public long countVideosWithAiSubtitles() {
        return Video.count("SELECT COUNT(DISTINCT v) FROM Video v JOIN v.subtitleTracks st WHERE st.isAiGenerated = true");
    }

    @Transactional
    public List<Video> findAllPaginated(int page, int limit, String search, String filter) {
        StringBuilder query = new StringBuilder("SELECT v FROM Video v");
        java.util.List<String> conditions = new java.util.ArrayList<>();
        java.util.Map<String, Object> params = new java.util.HashMap<>();

        if (filter != null) {
            switch (filter) {
                case "no-ai":
                    query.append(" WHERE v.id NOT IN (SELECT DISTINCT st.video.id FROM SubtitleTrack st WHERE st.isAiGenerated = true)");
                    break;
                case "no-subs":
                    query.append(" WHERE v.hasSubtitles = false OR v.hasSubtitles IS NULL");
                    break;
            }
        }

        if (search != null && !search.trim().isEmpty()) {
            String hasWhere = query.toString().toUpperCase().contains("WHERE") ? " AND" : " WHERE";
            query.append(hasWhere).append(" (LOWER(v.title) LIKE :search OR LOWER(v.filename) LIKE :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }

        query.append(" ORDER BY v.dateAdded DESC");

        jakarta.persistence.Query q = em.createQuery(query.toString(), Video.class);
        for (java.util.Map.Entry<String, Object> entry : params.entrySet()) {
            q.setParameter(entry.getKey(), entry.getValue());
        }
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Transactional
    public long countAllPaginated(String search, String filter) {
        StringBuilder query = new StringBuilder("SELECT COUNT(v) FROM Video v");
        if (filter != null) {
            switch (filter) {
                case "no-ai":
                    query.append(" WHERE v.id NOT IN (SELECT DISTINCT st.video.id FROM SubtitleTrack st WHERE st.isAiGenerated = true)");
                    break;
                case "no-subs":
                    query.append(" WHERE v.hasSubtitles = false OR v.hasSubtitles IS NULL");
                    break;
            }
        }
        if (search != null && !search.trim().isEmpty()) {
            String hasWhere = query.toString().toUpperCase().contains("WHERE") ? " AND" : " WHERE";
            query.append(hasWhere).append(" (LOWER(v.title) LIKE :search OR LOWER(v.filename) LIKE :search)");
            jakarta.persistence.Query q = em.createQuery(query.toString());
            q.setParameter("search", "%" + search.toLowerCase() + "%");
            return (long) q.getSingleResult();
        }
        return (long) em.createQuery(query.toString()).getSingleResult();
    }

    // ========== SHOW/SERIES QUERIES FOR AI SUBTITLES ==========

    @Transactional
    public List<Object[]> findAllShowsWithAiStats(String search, String filter) {
        // Returns [seriesTitle, totalEpisodes, episodesWithAiSubtitles, episodesWithAnySubtitles]
        StringBuilder query = new StringBuilder(
            "SELECT v.seriesTitle, COUNT(v), " +
            "(SELECT COUNT(DISTINCT st2.video.id) FROM SubtitleTrack st2 WHERE st2.video.seriesTitle = v.seriesTitle AND st2.isAiGenerated = true), " +
            "SUM(CASE WHEN v.hasSubtitles = true THEN 1 ELSE 0 END) " +
            "FROM Video v WHERE v.type = 'episode' AND v.seriesTitle IS NOT NULL");

        java.util.Map<String, Object> params = new java.util.HashMap<>();

        if (search != null && !search.trim().isEmpty()) {
            query.append(" AND LOWER(v.seriesTitle) LIKE :search");
            params.put("search", "%" + search.toLowerCase() + "%");
        }

        if ("no-ai".equals(filter)) {
            query.append(" AND v.id NOT IN (SELECT DISTINCT st.video.id FROM SubtitleTrack st WHERE st.isAiGenerated = true)");
        } else if ("no-subs".equals(filter)) {
            query.append(" AND (v.hasSubtitles = false OR v.hasSubtitles IS NULL)");
        }

        query.append(" GROUP BY v.seriesTitle ORDER BY v.seriesTitle");

        jakarta.persistence.Query q = em.createQuery(query.toString());
        for (java.util.Map.Entry<String, Object> entry : params.entrySet()) {
            q.setParameter(entry.getKey(), entry.getValue());
        }
        return q.getResultList();
    }

    @Transactional
    public List<Video> findEpisodesForShow(String seriesTitle, int page, int limit, String search, String filter) {
        StringBuilder query = new StringBuilder("SELECT v FROM Video v WHERE v.type = 'episode' AND v.seriesTitle = :seriesTitle");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("seriesTitle", seriesTitle);

        if (search != null && !search.trim().isEmpty()) {
            query.append(" AND (LOWER(v.title) LIKE :search OR LOWER(v.episodeTitle) LIKE :search OR LOWER(v.filename) LIKE :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }

        if ("no-ai".equals(filter)) {
            query.append(" AND v.id NOT IN (SELECT DISTINCT st.video.id FROM SubtitleTrack st WHERE st.isAiGenerated = true)");
        } else if ("no-subs".equals(filter)) {
            query.append(" AND (v.hasSubtitles = false OR v.hasSubtitles IS NULL)");
        }

        query.append(" ORDER BY v.seasonNumber, v.episodeNumber");

        jakarta.persistence.Query q = em.createQuery(query.toString(), Video.class);
        for (java.util.Map.Entry<String, Object> entry : params.entrySet()) {
            q.setParameter(entry.getKey(), entry.getValue());
        }
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Transactional
    public long countEpisodesForShow(String seriesTitle, String search, String filter) {
        StringBuilder query = new StringBuilder("SELECT COUNT(v) FROM Video v WHERE v.type = 'episode' AND v.seriesTitle = :seriesTitle");

        if (search != null && !search.trim().isEmpty()) {
            query.append(" AND (LOWER(v.title) LIKE :search OR LOWER(v.episodeTitle) LIKE :search OR LOWER(v.filename) LIKE :search)");
        }

        if ("no-ai".equals(filter)) {
            query.append(" AND v.id NOT IN (SELECT DISTINCT st.video.id FROM SubtitleTrack st WHERE st.isAiGenerated = true)");
        } else if ("no-subs".equals(filter)) {
            query.append(" AND (v.hasSubtitles = false OR v.hasSubtitles IS NULL)");
        }

        jakarta.persistence.Query q = em.createQuery(query.toString());
        q.setParameter("seriesTitle", seriesTitle);
        return (long) q.getSingleResult();
    }

    @Transactional
    public Video find(Long id) {
        Video video = Video.findById(id);
        
        if (video != null) {
            // Initialize ALL lazy collections used in templates
            org.hibernate.Hibernate.initialize(video.genres);
            org.hibernate.Hibernate.initialize(video.directors);
            org.hibernate.Hibernate.initialize(video.writers);
            org.hibernate.Hibernate.initialize(video.cast);
            org.hibernate.Hibernate.initialize(video.productionCompanies);
            org.hibernate.Hibernate.initialize(video.networks);
            org.hibernate.Hibernate.initialize(video.akas);
            org.hibernate.Hibernate.initialize(video.keywords);
            org.hibernate.Hibernate.initialize(video.audioTracks);
            org.hibernate.Hibernate.initialize(video.subtitleTracks);
        }
        
        if (video != null && (video.duration == null || video.duration <= 0)) {
            probeVideoDuration(video);
        }
        return video;
    }

    @Inject
    MediaAnalysisService mediaAnalysisService;

    @Transactional
    public void probeVideoMetadata(Video video) {
        if (video == null || video.id == null || video.path == null) return;
        
        // Re-fetch to avoid "Detached Entity" error and ensure we have the latest data
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        // Skip if already probed by another thread
        if (managedVideo.videoCodec != null && managedVideo.duration > 0) return;

        mediaAnalysisService.analyze(managedVideo);
        
        managedVideo.persist();
        
        // Update the passed object as well for immediate use
        video.videoCodec = managedVideo.videoCodec;
        video.audioCodec = managedVideo.audioCodec;
        video.duration = managedVideo.duration;
        video.resolution = managedVideo.resolution;
        video.displayResolution = managedVideo.displayResolution;
    }

    @Transactional
    public void probeVideoDuration(Video video) {
        probeVideoMetadata(video);
    }


    @Transactional
    public List<Video> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Video> videos = new ArrayList<>();
        for (Long id : ids) {
            Video video = Video.findById(id);
            if (video != null) {
                videos.add(video);
            }
        }
        return videos;
    }

    @Transactional
    public Video persist(Video video) {
        if (video.dateAdded == null) {
            video.dateAdded = LocalDateTime.now();
        }
        video.dateModified = LocalDateTime.now();
        video.persist();
        return video;
    }

    @Transactional
    public void delete(Video video) {
        if (video != null) {
            video.delete();
        }
    }

    @Transactional
    public void deleteSeries(String seriesTitle) {
        if (seriesTitle == null || seriesTitle.isBlank()) return;
        List<Video> episodes = findEpisodesForSeries(seriesTitle);
        for (Video v : episodes) {
            MediaFile mf = MediaFile.find("path", v.path).firstResult();
            if (mf != null) {
                mf.delete();
            }
            v.delete();
        }
        LOGGER.info("Deleted all episodes and media files for series: {}", seriesTitle);
    }

    // ========== TYPE-SPECIFIC QUERIES ==========

    @Transactional
    public List<Video> findMovies() {
        return findByType("movie");
    }

    @Transactional
    public List<Video> findEpisodes() {
        return findByType("episode");
    }

    @Transactional
    public List<Video> findDocumentaries() {
        return findByType("documentary");
    }

    @Transactional
    public List<Video> findShorts() {
        return findByType("short");
    }

    private List<Video> findByType(String type) {
        return Video.list("type = ?1 and isActive = ?2", Sort.by("releaseYear", Sort.Direction.Descending), type, true);
    }

    // ========== SERIES/SPECIFIC QUERIES ==========

    @Transactional
    public List<String> findAllSeriesTitles() {
        return em.createQuery("SELECT DISTINCT v.seriesTitle FROM Video v WHERE v.type = 'episode' AND v.seriesTitle IS NOT NULL", String.class)
                .getResultList()
                .stream()
                .sorted()
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        List<Integer> seasons = Video.<Video>list("type = ?1 and seriesTitle = ?2 and isActive = ?3", "episode", seriesTitle, true)
                .stream()
                .map(v -> v.seasonNumber != null ? v.seasonNumber : 1)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
                
        if (seasons.isEmpty()) {
            // Check if there are any episodes at all for this series
            long count = Video.count("type = ?1 and seriesTitle = ?2 and isActive = ?3", "episode", seriesTitle, true);
            if (count > 0) {
                return Collections.singletonList(1);
            }
        }
        return seasons;
    }

    @Transactional
    public List<Video> findEpisodesForSeason(String seriesTitle, Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber == 1) {
            return Video.list("type = ?1 and seriesTitle = ?2 and (seasonNumber = ?3 or seasonNumber is null) and (folder is null or folder = '') and isActive = ?4",
                             Sort.by("episodeNumber", Sort.Direction.Ascending),
                             "episode", seriesTitle, 1, true);
        }
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and (folder is null or folder = '') and isActive = ?4",
                         Sort.by("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, seasonNumber, true);
    }

    @Transactional
    public List<String> findSubFoldersForSeason(String seriesTitle, Integer seasonNumber) {
        String query = "SELECT DISTINCT v.folder FROM Video v WHERE v.type = 'episode' AND v.seriesTitle = ?1 AND v.seasonNumber = ?2 AND v.folder is not null AND v.folder <> '' AND v.isActive = ?3 ORDER BY v.folder";
        return em.createQuery(query, String.class)
                .setParameter(1, seriesTitle)
                .setParameter(2, seasonNumber)
                .setParameter(3, true)
                .getResultList();
    }

    @Transactional
    public List<Video> findEpisodesForSeasonAndFolder(String seriesTitle, Integer seasonNumber, String folder) {
        if (folder == null || folder.isEmpty()) {
            return findEpisodesForSeason(seriesTitle, seasonNumber);
        }
        if (seasonNumber == null || seasonNumber == 1) {
            return Video.list("type = ?1 and seriesTitle = ?2 and (seasonNumber = ?3 or seasonNumber is null) and folder = ?4 and isActive = ?5",
                             Sort.by("episodeNumber", Sort.Direction.Ascending),
                             "episode", seriesTitle, 1, folder, true);
        }
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and folder = ?4 and isActive = ?5",
                         Sort.by("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, seasonNumber, folder, true);
    }

    @Transactional
    public long countEpisodesInFolder(String seriesTitle, Integer seasonNumber, String folder) {
        if (folder == null || folder.isEmpty()) return 0;
        if (seasonNumber == null || seasonNumber == 1) {
            return Video.count("type = ?1 and seriesTitle = ?2 and (seasonNumber = ?3 or seasonNumber is null) and folder = ?4 and isActive = ?5",
                              "episode", seriesTitle, 1, folder, true);
        }
        return Video.count("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and folder = ?4 and isActive = ?5",
                          "episode", seriesTitle, seasonNumber, folder, true);
    }

    @Transactional
    public List<Video> findEpisodesForSeries(String seriesTitle) {
        return Video.list("type = ?1 and seriesTitle = ?2 and isActive = ?3",
                         Sort.by("seasonNumber", Sort.Direction.Ascending)
                         .and("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, true);
    }

    @Transactional
    public Path getSeriesFolderPath(String seriesTitle) {
        Video episode = Video.find("seriesTitle = ?1 and isActive = true", seriesTitle)
            .firstResult();
        if (episode == null || episode.path == null) {
            return null;
        }
        
        Path episodePath = Paths.get(episode.path);
        Path parent = episodePath.getParent();
        if (parent == null) {
            return null;
        }
        return parent.getParent();
    }

    @Transactional
    public Path getSeasonFolderPath(String seriesTitle, Integer seasonNumber) {
        Video episode = Video.find("seriesTitle = ?1 and seasonNumber = ?2 and isActive = true",
            seriesTitle, seasonNumber).firstResult();
        if (episode == null || episode.path == null) {
            return null;
        }
        
        Path episodePath = Paths.get(episode.path);
        Path parent = episodePath.getParent();
        return parent;
    }

    @Transactional
    public Path getSeasonFolderPathFallback(String seriesTitle, Integer seasonNumber) {
        Video episode = Video.find("seriesTitle = ?1 and isActive = true", seriesTitle)
            .firstResult();
        if (episode == null || episode.path == null) {
            return null;
        }
        
        Path episodePath = Paths.get(episode.path);
        Path seriesFolder = episodePath.getParent().getParent();
        String seasonFolderName = "Season " + seasonNumber;
        return seriesFolder.resolve(seasonFolderName);
    }


    // ========== GENRE-BASED QUERIES ==========

    @Transactional
    public List<Video> findByGenre(String genreSlug, int page, int limit) {
        // Find genre by slug
        Genre genre = Genre.find("slug", genreSlug).firstResult();
        if (genre == null) {
            return Collections.emptyList();
        }

        // Find videos associated with this genre
        int offset = (page - 1) * limit;
        
        String query = "SELECT DISTINCT v FROM Video v JOIN VideoGenre vg ON v.id = vg.video.id WHERE vg.genre.id = :genreId AND v.isActive = :isActive ORDER BY vg.relevance DESC, v.popularityScore DESC";
        
        return em.createQuery(query, Video.class)
                .setParameter("genreId", genre.id)
                .setParameter("isActive", true)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultStream()
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findByMultipleGenres(List<String> genreSlugs, int page, int limit) {
        if (genreSlugs == null || genreSlugs.isEmpty()) {
            return Collections.emptyList();
        }

        // Build query for multiple genres
        StringBuilder whereClause = new StringBuilder("WHERE v.isActive = ?1 AND (");
        List<Object> params = new ArrayList<>();
        
        for (int i = 0; i < genreSlugs.size(); i++) {
            if (i > 0) {
                whereClause.append(" OR ");
            }
            whereClause.append("EXISTS (SELECT 1 FROM VideoGenre vg JOIN Genre g ON vg.genre.id = g.id WHERE vg.video.id = v.id AND g.slug = ?").append(i + 2);
            params.add(genreSlugs.get(i));
        }
        whereClause.append(")");

        String query = "SELECT DISTINCT v FROM Video v JOIN VideoGenre vg ON v.id = vg.video.id " + whereClause +
                        " ORDER BY v.popularityScore DESC, v.releaseYear DESC";

        TypedQuery<Video> typedQuery = em.createQuery(query, Video.class);
        typedQuery.setParameter(1, true);  // isActive = ?1
        
        // Add genre slug parameters
        for (int i = 0; i < params.size(); i++) {
            typedQuery.setParameter(i + 2, params.get(i));
        }
        
        return typedQuery
                .setFirstResult((page - 1) * limit)
                .setMaxResults(limit)
                .getResultStream()
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, List<Video>> getAllGenreCarousels(Long userId, int itemsPerGenre) {
        Map<String, List<Video>> carousels = new HashMap<>();
        List<Genre> activeGenres = Genre.list("isActive = ?1", Sort.by("sortOrder", Sort.Direction.Ascending), true);

        for (Genre genre : activeGenres) {
            List<Video> genreVideos = findByGenre(genre.slug, 1, itemsPerGenre);
            if (!genreVideos.isEmpty()) {
                carousels.put(genre.name, genreVideos);
            }
        }

        return carousels;
    }

    // ========== DISCOVERY AND BROWSING ==========

    @Transactional
    public List<Video> findTrending(int limit) {
        return Video.<Video>list("isActive = ?1", Sort.by("popularityScore", Sort.Direction.Descending), true)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findNewlyAdded(int days, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        return Video.<Video>list("dateAdded >= ?1 AND isActive = ?2", Sort.by("dateAdded", Sort.Direction.Descending), cutoff, true)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findHighlyRated(double minRating, int limit) {
        return Video.<Video>list("isActive = ?1 AND imdbRating >= ?2", Sort.by("imdbRating", Sort.Direction.Descending), true, minRating)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public Video findNextEpisode(Video current) {
        if (current == null || current.seriesTitle == null || !"episode".equalsIgnoreCase(current.type)) {
            return null;
        }

        // Try to find next episode in same season
        Video next = Video.<Video>find("seriesTitle = ?1 AND seasonNumber = ?2 AND episodeNumber > ?3 AND (folder is null or folder = '') AND isActive = true",
                Sort.by("episodeNumber", Sort.Direction.Ascending),
                current.seriesTitle, current.seasonNumber, current.episodeNumber).firstResult();

        if (next != null) return next;

        // If no more episodes in current season, try first episode of next season
        next = Video.<Video>find("seriesTitle = ?1 AND seasonNumber > ?2 AND (folder is null or folder = '') AND isActive = true",
                Sort.by("seasonNumber", Sort.Direction.Ascending).and("episodeNumber", Sort.Direction.Ascending),
                current.seriesTitle, current.seasonNumber).firstResult();

        return next;
    }

    @Transactional
    public Video findPreviousEpisode(Video current) {
        if (current == null || current.seriesTitle == null || !"episode".equalsIgnoreCase(current.type)) {
            return null;
        }

        // Try to find previous episode in same season
        Video prev = Video.<Video>find("seriesTitle = ?1 AND seasonNumber = ?2 AND episodeNumber < ?3 AND (folder is null or folder = '') AND isActive = true",
                Sort.by("episodeNumber", Sort.Direction.Descending),
                current.seriesTitle, current.seasonNumber, current.episodeNumber).firstResult();

        if (prev != null) return prev;

        // If no more episodes in current season, try last episode of previous season
        prev = Video.<Video>find("seriesTitle = ?1 AND seasonNumber < ?2 AND (folder is null or folder = '') AND isActive = true",
                Sort.by("seasonNumber", Sort.Direction.Descending).and("episodeNumber", Sort.Direction.Descending),
                current.seriesTitle, current.seasonNumber).firstResult();

        return prev;
    }
    @Transactional
    public List<Video> findPopular(int limit) {
        return Video.<Video>list("isActive = ?1 AND popularityScore > ?2", Sort.by("popularityScore", Sort.Direction.Descending), true, 0.0)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findRecommendedByGenre(String genreSlug, Long userId) {
        List<Video> genreVideos = findByGenre(genreSlug, 1, 20);
        
        // Apply personalization based on user preferences
        List<Video> personalizedVideos = personalizeVideoRecommendations(genreVideos, userId);
        
        return personalizedVideos.stream()
                .limit(10)
                .collect(Collectors.toList());
    }

    // ========== SEARCH AND FILTERING ==========

    @Transactional
    public List<Video> searchVideos(String query, List<String> filters, int page, int limit) {
        StringBuilder whereClause = new StringBuilder("WHERE v.isActive = ?1 AND (");
        List<Object> params = new ArrayList<>();
        params.add(true);

        if (query != null && !query.trim().isEmpty()) {
            whereClause.append("(LOWER(v.title) LIKE LOWER(?1) OR LOWER(v.description) LIKE LOWER(?1) OR LOWER(v.overview) LIKE LOWER(?1))");
            params.add("%" + query.toLowerCase() + "%");
        }

        if (filters != null && !filters.isEmpty()) {
            for (String filter : filters) {
                whereClause.append(" AND ?").append(filters.indexOf(filter) + 2);
                params.add(filter);
            }
        }

        whereClause.append(")");
        String fullQuery = "SELECT DISTINCT v FROM Video v JOIN VideoGenre vg ON v.id = vg.video.id JOIN Genre g ON vg.genre.id = g.id " +
                            whereClause + " ORDER BY v.popularityScore DESC, v.releaseYear DESC";

        TypedQuery<Video> typedQuery = em.createQuery(fullQuery, Video.class);
        for (int i = 0; i < params.size(); i++) {
            typedQuery.setParameter(i + 1, params.get(i));
        }
        return typedQuery
                .setFirstResult((page - 1) * limit)
                .setMaxResults(limit)
                .getResultStream()
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> filterByQuality(String quality, int page, int limit) {
        return Video.<Video>list("quality = ?1 AND isActive = ?2", Sort.by("releaseYear", Sort.Direction.Descending), quality, true)
                .stream()
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> filterByYearRange(int startYear, int endYear, int page, int limit) {
        return Video.<Video>list("releaseYear >= ?1 AND releaseYear <= ?2 AND isActive = ?3",
                         Sort.by("popularityScore", Sort.Direction.Descending),
                         startYear, endYear, true)
                .stream()
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
    }
    // ========== SUBTITLE OPERATIONS ==========

    @Transactional
    public List<SubtitleTrack> getSubtitleTracks(Long videoId) {
        Video video = Video.findById(videoId);
        if (video != null && video.subtitleTracks != null) {
            return video.subtitleTracks.stream()
                    .filter(track -> track.isActive)
                    .sorted((a, b) -> Long.compare(b.id, a.id))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Transactional
    public void updateSubtitleTracks(Long videoId, List<SubtitleTrack> tracks) {
        Video video = Video.findById(videoId);
        if (video != null) {
            // Find existing manual tracks we want to preserve
            List<SubtitleTrack> manualTracks = (video.subtitleTracks != null) ? 
                    video.subtitleTracks.stream().filter(t -> t.isManual).collect(Collectors.toList()) : 
                    new ArrayList<>();
            
            // Clear existing tracks
            if (video.subtitleTracks != null) {
                video.subtitleTracks.clear();
            } else {
                video.subtitleTracks = new ArrayList<>();
            }
            
            // Re-add manual tracks
            video.subtitleTracks.addAll(manualTracks);
            
            // Add new tracks and set bidirectional relationship
            for (SubtitleTrack track : tracks) {
                // Skip if this path is already covered by a manual track
                if (manualTracks.stream().anyMatch(m -> m.fullPath.equals(track.fullPath))) {
                    continue;
                }
                track.video = video;
                video.subtitleTracks.add(track);
            }
            
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }

    @Transactional
    public void updateAudioTracks(Long videoId, List<Models.AudioTrack> tracks) {
        Video video = Video.findById(videoId);
        if (video != null) {
            // Clear existing audio tracks
            if (video.audioTracks != null) {
                video.audioTracks.clear();
            } else {
                video.audioTracks = new ArrayList<>();
            }

            // Add new tracks
            if (tracks != null) {
                for (Models.AudioTrack track : tracks) {
                    track.video = video;
                    video.audioTracks.add(track);
                }
            }
            
            // Mark if video has multiple audio tracks
            video.hasMultipleAudioTrack = (tracks != null && tracks.size() > 1);
            
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }
    
    /**
     * Discover subtitle tracks for all videos that don't have any subtitle tracks.
     * Now delegates to SubtitleDiscoveryQueueProcessor for background processing.
     */
    public void discoverSubtitleTracksForAllVideos() {
        LOGGER.info("Delegating subtitle track discovery to background processor...");
        subtitleDiscoveryProcessor.queueAllVideos();
    }

    // ========== IMPORT AND CREATION ==========

    @Transactional
    public Video createVideoFromMediaFile(Models.MediaFile mediaFile) {
        Video video = new Video();
        
        // Core identification
        video.path = mediaFile.path;
        video.filename = extractFilenameFromPath(mediaFile.path);
        video.type = detectVideoType(mediaFile);
        
        // Technical metadata
        video.resolution = mediaFile.width + "x" + mediaFile.height;
        video.displayResolution = calculateDisplayResolution(video.resolution);
        video.videoCodec = mediaFile.videoCodec;
        video.audioCodec = mediaFile.audioCodec;
        video.duration = mediaFile.durationSeconds * 1000L; // Convert to milliseconds
        video.size = mediaFile.size;
        video.lastModified = mediaFile.lastModified;
        video.quality = detectQuality(mediaFile);
        video.hasSubtitles = mediaFile.hasEmbeddedSubtitles;
        video.releaseGroup = mediaFile.releaseGroup;
        video.source = mediaFile.source;
        
        // Discover and associate subtitle tracks
        List<SubtitleTrack> subtitleTracks = subtitleMatcher.discoverSubtitleTracks(
                java.nio.file.Path.of(mediaFile.path), video);
        updateSubtitleTracks(video.id, subtitleTracks);
        
        // Set defaults
        if (video.dateAdded == null) {
            video.dateAdded = LocalDateTime.now();
        }
        video.dateModified = LocalDateTime.now();
        
        video.persist();
        return video;
    }

    @Transactional
    public void updateTitle(Long id, String title) {
        Video video = Video.findById(id);
        if (video != null) {
            video.title = title;
            video.titleManuallyEdited = true; // Mark as manually edited
            video.dateModified = LocalDateTime.now();
            video.persist();
            LOGGER.info("Updated title for video ID {}: '{}'", id, title);
        }
    }

    @Transactional
    public void updateMetadata(Long id, String title, String seriesTitle, String episodeTitle, Integer seasonNumber, Integer episodeNumber, String type, String showImdbId, String imdbId) {
        Video video = Video.findById(id);
        if (video != null) {
            // Mark as manually edited when user explicitly sets these values
            if (title != null) {
                video.title = title;
                video.titleManuallyEdited = true;
            }
            if (seriesTitle != null) {
                video.seriesTitle = seriesTitle;
                video.seriesTitleManuallyEdited = true;
            }
            video.episodeTitle = episodeTitle;
            video.seasonNumber = seasonNumber;
            video.episodeNumber = episodeNumber;
            video.type = type;
            if (showImdbId != null && !showImdbId.isBlank()) {
                video.showImdbId = showImdbId;
            }
            if (imdbId != null && !imdbId.isBlank()) {
                video.imdbId = imdbId;
            }
            video.dateModified = LocalDateTime.now();
            video.persist();
            LOGGER.info("Updated metadata for video ID {}: title='{}', series='{}', imdbId='{}', showImdbId='{}', type='{}'", id, title, seriesTitle, imdbId, showImdbId, type);
        }
    }

    @Transactional
    public void moveEpisodes(String oldSeriesTitle, String newSeriesTitle) {
        if (oldSeriesTitle == null || newSeriesTitle == null) return;
        
        List<Video> episodes = findEpisodesForSeries(oldSeriesTitle);
        for (Video ep : episodes) {
            ep.seriesTitle = newSeriesTitle;
            ep.seriesTitleManuallyEdited = true; // Mark as manually edited
            ep.dateModified = LocalDateTime.now();
            ep.persist();
        }
        LOGGER.info("Moved {} episodes from series '{}' to '{}'", episodes.size(), oldSeriesTitle, newSeriesTitle);
    }

    @Transactional
    public void updateSeriesTitle(String oldTitle, String newTitle) {
        if (oldTitle == null || newTitle == null) return;
        List<Video> videos = Video.list("seriesTitle = ?1", oldTitle);
        for (Video v : videos) {
            v.seriesTitle = newTitle;
            v.seriesTitleManuallyEdited = true; // Mark as manually edited
            v.persist();
        }
        LOGGER.info("Updated series title from '{}' to '{}' for {} videos", oldTitle, newTitle, videos.size());
    }

    @Transactional
    public void updateSeriesMetadata(String seriesTitle, String posterPath, String backdropPath, String showImdbId) {
        if (seriesTitle == null) return;
        List<Video> videos = findEpisodesForSeries(seriesTitle);
        for (Video v : videos) {
            if (posterPath != null && !posterPath.isBlank()) v.posterPath = posterPath;
            if (backdropPath != null && !backdropPath.isBlank()) v.backdropPath = backdropPath;
            if (showImdbId != null && !showImdbId.isBlank()) v.showImdbId = showImdbId;
            v.dateModified = LocalDateTime.now();
            v.persist();
        }
        LOGGER.info("Updated series metadata for '{}' ({} videos)", seriesTitle, videos.size());
    }

    @Transactional
    public void updateSeriesMetadata(String seriesTitle, String posterPath, String backdropPath) {
        updateSeriesMetadata(seriesTitle, posterPath, backdropPath, null);
    }

    @Transactional
    public void forceReload(String seriesTitle) {
        if (seriesTitle == null || seriesTitle.isBlank()) return;
        List<Video> episodes = findEpisodesForSeries(seriesTitle);
        for (Video v : episodes) {
            // Delete dependent records before deleting video to avoid FK violations
            Models.VideoHistory.delete("mediaFile.path = ?1", v.path);
            MediaFile mf = MediaFile.find("path", v.path).firstResult();
            if (mf != null) {
                mf.delete();
            }
            Models.CollectionEntry.delete("video.id = ?1", v.id);
            Models.VideoState.delete("video.id = ?1", v.id);
            Models.VideoGenre.delete("video.id = ?1", v.id);
            v.delete();
        }
        LOGGER.info("Force reloaded all episodes and media files for series: {}", seriesTitle);
    }

    /**
     * Clears manual override flags for a video, allowing future scans to update those fields
     */
    @Transactional
    public void clearManualOverrideFlags(Long videoId, boolean clearSeriesTitle, boolean clearTitle) {
        Video video = Video.findById(videoId);
        if (video != null) {
            if (clearSeriesTitle) {
                video.seriesTitleManuallyEdited = false;
            }
            if (clearTitle) {
                video.titleManuallyEdited = false;
            }
            video.persist();
            LOGGER.info("Cleared override flags for video {}: seriesTitle={}, title={}", 
                       videoId, clearSeriesTitle, clearTitle);
        }
    }
    
    /**
     * Clears manual override flags for all episodes of a series
     */
    @Transactional
    public void clearSeriesManualOverrideFlags(String seriesTitle, boolean clearSeriesTitle, boolean clearTitle) {
        List<Video> videos = Video.list("seriesTitle = ?1", seriesTitle);
        for (Video v : videos) {
            if (clearSeriesTitle) v.seriesTitleManuallyEdited = false;
            if (clearTitle) v.titleManuallyEdited = false;
            v.persist();
        }
        LOGGER.info("Cleared override flags for series '{}' ({} videos)", seriesTitle, videos.size());
    }

    // ========== UTILITY METHODS ==========

    private String detectVideoType(Models.MediaFile mediaFile) {
        // Simple type detection based on naming and metadata
        String filename = extractFilenameFromPath(mediaFile.path);
        String pathLower = mediaFile.path.toLowerCase();
        
        // Priority 1: Strong folder hints
        if (pathLower.contains("tv shows") || pathLower.contains("tvseries") || 
            pathLower.contains("/tv/") || pathLower.contains("\\tv\\") ||
            pathLower.contains("season") || pathLower.contains("series")) {
            return "episode";
        }
        
        // Priority 2: Naming hints in filename
        if (filename.toLowerCase().contains("movie") || 
            pathLower.contains("movies") ||
            isTypicalMovieDuration(mediaFile.durationSeconds)) {
            
            // Re-check for episode patterns in filename even if "movie" is in path
            if (filename.matches(".*[sS]\\d+[eE]\\d+.*") || filename.matches(".*\\d+x\\d+.*")) {
                return "episode";
            }
            return "movie";
        } else if (isTypicalEpisodeDuration(mediaFile.durationSeconds)) {
            return "episode";
        }
        
        return "movie"; // Default fallback
    }

    private String extractFilenameFromPath(String path) {
        if (path == null) return null;
        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);
        return lastSeparator >= 0 ? path.substring(lastSeparator + 1) : path;
    }

    private boolean isTypicalMovieDuration(int durationSeconds) {
        return durationSeconds >= 40 * 60 && durationSeconds <= 300 * 60; // 40-300 minutes
    }

    private boolean isTypicalEpisodeDuration(int durationSeconds) {
        return durationSeconds >= 5 * 60 && durationSeconds <= 120 * 60; // 5-120 minutes
    }

    private String calculateDisplayResolution(String resolution) {
        if (resolution == null) return null;
        
        String[] parts = resolution.split("x");
        if (parts.length != 2) return resolution;
        
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            
            if (height >= 2160) return "4K";
            if (height >= 1440) return "2K";
            if (height >= 1080) return "Full HD";
            if (height >= 720) return "HD";
            return "SD";
        } catch (NumberFormatException e) {
            return resolution;
        }
    }

    private String detectQuality(Models.MediaFile mediaFile) {
        if (mediaFile.width == 0 || mediaFile.height == 0) return "Unknown";
        
        String resolution = calculateDisplayResolution(mediaFile.width + "x" + mediaFile.height);
        if (resolution.contains("4K")) return "4K";
        if (resolution.contains("Full HD")) return "Full HD";
        if (resolution.contains("HD")) return "HD";
        return "SD";
    }

    public List<Video> personalizeVideoRecommendations(List<Video> videos, Long userId) {
        // In a real implementation, this would use:
        // - User's watch history
        // - User's favorite genres
        // - User's ratings
        // - Machine learning recommendations
        
        // For now, just return the original videos
        return videos;
    }
    
    public long countByGenre(String genreSlug) {
        Genre genre = Genre.find("slug", genreSlug).firstResult();
        if (genre == null) {
            return 0;
        }
        
        // Use a simple approach - this is a placeholder for proper counting
        try {
            return findByGenre(genreSlug, 1, Integer.MAX_VALUE).size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    public long countByMultipleGenres(List<String> genreSlugs) {
        if (genreSlugs == null || genreSlugs.isEmpty()) {
            return 0;
        }
        
        // Use a simple approach - this is a placeholder for proper counting
        try {
            return findByMultipleGenres(genreSlugs, 1, Integer.MAX_VALUE).size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== PAGINATION METHODS ==========
    
    @Transactional
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit, String sortBy, String sortDirection, String search) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.Descending : Sort.Direction.Ascending;
        String sortField = sortBy != null ? sortBy : "dateAdded";
        
        if (search == null || search.trim().isEmpty()) {
            List<Video> videos = Video.<Video>find("type = ?1 AND isActive = true",
                    Sort.by(sortField, direction), mediaType)
                    .page(Page.of(page - 1, limit))
                    .list();
            
            long totalCount = Video.count("type = ?1 AND isActive = true", mediaType);
            return new PaginatedVideos(videos, totalCount);
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.type = :type AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s OR " +
                         "EXISTS (SELECT 1 FROM v.cast c WHERE LOWER(c) LIKE :s) OR " +
                         "EXISTS (SELECT 1 FROM v.directors d WHERE LOWER(d) LIKE :s) OR " +
                         "EXISTS (SELECT 1 FROM v.writers w WHERE LOWER(w) LIKE :s))";
            
            List<Video> videos = em.createQuery("SELECT v " + hql + " ORDER BY v." + sortField + " " + (sortDirection.equalsIgnoreCase("desc") ? "DESC" : "ASC"), Video.class)
                    .setParameter("type", mediaType)
                    .setParameter("s", s)
                    .setFirstResult((page - 1) * limit)
                    .setMaxResults(limit)
                    .getResultList();
            
            long totalCount = em.createQuery("SELECT COUNT(v) " + hql, Long.class)
                    .setParameter("type", mediaType)
                    .setParameter("s", s)
                    .getSingleResult();
            
            return new PaginatedVideos(videos, totalCount);
        }
    }

    @Transactional
    public PaginatedSeries findPaginatedSeriesTitles(int page, int limit, String sortBy, String sortDirection, String search) {
        String baseHql = "SELECT v.seriesTitle, v.dateAdded, v.lastWatched FROM Video v WHERE v.type = 'episode' AND v.seriesTitle IS NOT NULL AND v.isActive = true";
        TypedQuery<Object[]> query;
        
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.toLowerCase() + "%";
            String searchHql = " AND (LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s OR " +
                               "EXISTS (SELECT 1 FROM v.cast c WHERE LOWER(c) LIKE :s) OR " +
                               "EXISTS (SELECT 1 FROM v.directors d WHERE LOWER(d) LIKE :s) OR " +
                               "EXISTS (SELECT 1 FROM v.writers w WHERE LOWER(w) LIKE :s))";
            query = em.createQuery(baseHql + searchHql, Object[].class).setParameter("s", s);
        } else {
            query = em.createQuery(baseHql, Object[].class);
        }

        List<Object[]> episodesData = query.getResultList();

        // Group by normalized seriesTitle using SmartNamingService
        Map<String, SeriesSortData> seriesMap = new LinkedHashMap<>();
        Map<String, String> normalizedToOriginalMap = new HashMap<>();

        for (Object[] row : episodesData) {
            String seriesTitle = (String) row[0];
            LocalDateTime dateAdded = (LocalDateTime) row[1];
            LocalDateTime lastWatched = (LocalDateTime) row[2];
            
            // Use SmartNamingService to get a normalized key for grouping
            String normalizedKey = SmartNamingService.cleanShowName(seriesTitle).toLowerCase().trim();
            if (normalizedKey.isEmpty()) normalizedKey = seriesTitle.toLowerCase().trim();

            SeriesSortData existing = seriesMap.get(normalizedKey);
            if (existing == null) {
                seriesMap.put(normalizedKey, new SeriesSortData(dateAdded, lastWatched));
                normalizedToOriginalMap.put(normalizedKey, seriesTitle);
            } else {
                // Update based on sort field
                if ("dateAdded".equals(sortBy)) {
                    if (dateAdded != null && (existing.dateAdded == null || dateAdded.isAfter(existing.dateAdded))) {
                        existing.dateAdded = dateAdded;
                        normalizedToOriginalMap.put(normalizedKey, seriesTitle);
                    }
                } else if ("lastWatched".equals(sortBy)) {
                    if (lastWatched != null && (existing.lastWatched == null || lastWatched.isAfter(existing.lastWatched))) {
                        existing.lastWatched = lastWatched;
                        normalizedToOriginalMap.put(normalizedKey, seriesTitle);
                    }
                }
            }
        }

        List<String> groupKeys = new ArrayList<>(seriesMap.keySet());
        boolean desc = "desc".equalsIgnoreCase(sortDirection);

        Comparator<String> comparator;
        if ("dateAdded".equals(sortBy)) {
            comparator = Comparator.comparing(key -> seriesMap.get(key).dateAdded, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("lastWatched".equals(sortBy)) {
            comparator = Comparator.comparing(key -> seriesMap.get(key).lastWatched, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else {
            comparator = Comparator.comparing(key -> normalizedToOriginalMap.get(key), String.CASE_INSENSITIVE_ORDER);
        }

        if (desc) comparator = comparator.reversed();
        groupKeys.sort(comparator);

        long totalCount = groupKeys.size();
        List<String> pagedTitles = groupKeys.stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .map(normalizedToOriginalMap::get)
                .collect(Collectors.toList());

        return new PaginatedSeries(pagedTitles, totalCount);
    }

    @Transactional
    public List<Video> findHistory(String search, int limit) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();

        String hql = "SELECT h FROM VideoHistory h JOIN h.mediaFile mf JOIN Video v ON v.path = mf.path WHERE v.isActive = true AND h.profile = :profile";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY h.playedAt DESC";

        TypedQuery<Models.VideoHistory> query = em.createQuery(hql, Models.VideoHistory.class);
        query.setParameter("profile", activeProfile);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }

        List<Models.VideoHistory> history = query.getResultList();

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        List<Models.Video> videos = new ArrayList<>();

        for (Models.VideoHistory h : history) {
            if (h.mediaFile != null && seenPaths.add(h.mediaFile.path)) {
                Models.Video v = Video.find("path", h.mediaFile.path).firstResult();
                if (v != null) videos.add(v);
            }
            if (videos.size() >= limit) break;
        }
        return videos;
    }

    @Transactional
    public List<Video> findWatchlist(String search) {
        if (search == null || search.trim().isEmpty()) {
            return Video.list("favorite = true AND isActive = true");
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.favorite = true AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s)";
            return em.createQuery("SELECT v " + hql + " ORDER BY v.favoritedAt DESC", Video.class)
                    .setParameter("s", s)
                    .getResultList();
        }
    }
    
    public record VideoHistoryEntry(Video video, Models.VideoHistory history, Models.Profile profile) {}
    
    @Transactional
    public List<VideoHistoryEntry> findAllHistory(String search, int limit) {
        String hql = "SELECT vh FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY vh.playedAt DESC";
        
        TypedQuery<Models.VideoHistory> query = em.createQuery(hql, Models.VideoHistory.class);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        query.setMaxResults(limit);
        
        List<Models.VideoHistory> historyList = query.getResultList();
        List<VideoHistoryEntry> entries = new ArrayList<>();
        
        for (Models.VideoHistory vh : historyList) {
            if (vh.mediaFile != null) {
                Video video = Video.find("path", vh.mediaFile.path).firstResult();
                if (video != null) {
                    entries.add(new VideoHistoryEntry(video, vh, vh.profile));
                }
            }
        }
        return entries;
    }

    @Transactional
    public PaginatedVideos findHistoryPaginated(String search, int page, int limit) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return new PaginatedVideos(List.of(), 0);

        String hql = "SELECT h FROM VideoHistory h JOIN h.mediaFile mf JOIN Video v ON v.path = mf.path WHERE v.isActive = true AND h.profile = :profile";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY h.playedAt DESC";

        TypedQuery<Models.VideoHistory> query = em.createQuery(hql, Models.VideoHistory.class);
        query.setParameter("profile", activeProfile);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }

        List<Models.VideoHistory> allHistory = query.getResultList();

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        List<Video> allVideos = new ArrayList<>();
        for (Models.VideoHistory h : allHistory) {
            if (h.mediaFile != null && seenPaths.add(h.mediaFile.path)) {
                Video v = Video.find("path", h.mediaFile.path).firstResult();
                if (v != null) allVideos.add(v);
            }
        }

        long totalCount = allVideos.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, allVideos.size());
        List<Video> pageVideos = fromIndex >= allVideos.size() ? List.of() : allVideos.subList(fromIndex, toIndex);
        return new PaginatedVideos(pageVideos, totalCount);
    }

    @Transactional
    public PaginatedVideos findWatchlistPaginated(String search, int page, int limit) {
        List<Video> all;
        if (search == null || search.trim().isEmpty()) {
            all = Video.list("favorite = true AND isActive = true");
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.favorite = true AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s)";
            all = em.createQuery("SELECT v " + hql + " ORDER BY v.favoritedAt DESC", Video.class)
                    .setParameter("s", s)
                    .getResultList();
        }

        long totalCount = all.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, all.size());
        List<Video> pageVideos = fromIndex >= all.size() ? List.of() : all.subList(fromIndex, toIndex);
        return new PaginatedVideos(pageVideos, totalCount);
    }

    public static class PaginatedHistoryEntries {
        public final List<VideoHistoryEntry> entries;
        public final long totalCount;
        public PaginatedHistoryEntries(List<VideoHistoryEntry> entries, long totalCount) {
            this.entries = entries;
            this.totalCount = totalCount;
        }
    }

    @Transactional
    public PaginatedHistoryEntries findAllHistoryPaginated(String search, int page, int limit) {
        String countHql = "SELECT COUNT(vh) FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        String hql = "SELECT vh FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        if (search != null && !search.trim().isEmpty()) {
            String searchClause = " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
            countHql += searchClause;
            hql += searchClause;
        }
        hql += " ORDER BY vh.playedAt DESC";

        TypedQuery<Long> countQ = em.createQuery(countHql, Long.class);
        if (search != null && !search.trim().isEmpty()) {
            countQ.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        long totalCount = countQ.getSingleResult();

        TypedQuery<Models.VideoHistory> query = em.createQuery(hql, Models.VideoHistory.class);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        query.setFirstResult((page - 1) * limit);
        query.setMaxResults(limit);

        List<Models.VideoHistory> historyList = query.getResultList();
        List<VideoHistoryEntry> entries = new ArrayList<>();
        for (Models.VideoHistory vh : historyList) {
            if (vh.mediaFile != null) {
                Video video = Video.find("path", vh.mediaFile.path).firstResult();
                if (video != null) {
                    entries.add(new VideoHistoryEntry(video, vh, vh.profile));
                }
            }
        }
        return new PaginatedHistoryEntries(entries, totalCount);
    }

    /**
     * Find videos that need manual attention from the metadata enrichment worker.
     * Returns videos with missing external IDs, missing season/episode numbers,
     * or that likely failed enrichment (active for >1 hour with no IDs).
     */
    @Transactional
    public List<Video> findVideosNeedingAttention(int maxResults) {
        String jpql = "SELECT v FROM Video v WHERE v.isActive = true AND v.titleManuallyEdited = false AND (" +
                "v.imdbId IS NULL OR v.tmdbId IS NULL OR " +
                "(v.type = 'episode' AND v.showImdbId IS NULL) OR " +
                "(v.type = 'episode' AND (v.seasonNumber IS NULL OR v.episodeNumber IS NULL))" +
                ") ORDER BY v.dateAdded DESC";
        return em.createQuery(jpql, Video.class)
                .setMaxResults(maxResults)
                .getResultList();
    }

    /**
     * Find videos that have been enriched (have external IDs) for verification.
     * These are candidates where re-running enrichment might produce different
     * values worth human review — as opposed to findVideosNeedingAttention which
     * finds videos with missing data.
     */
    @Transactional
    public List<Video> findVideosForVerification(int maxResults) {
        String jpql = "SELECT v FROM Video v WHERE v.isActive = true AND v.titleManuallyEdited = false AND (" +
                "(v.type = 'movie' AND v.imdbId IS NOT NULL AND v.imdbId != '' AND v.tmdbId IS NOT NULL AND v.tmdbId != '')" +
                " OR " +
                "(v.type = 'episode' AND v.imdbId IS NOT NULL AND v.imdbId != '' AND " +
                " v.tmdbId IS NOT NULL AND v.tmdbId != '' AND " +
                " v.showImdbId IS NOT NULL AND v.showImdbId != '' AND " +
                " v.seasonNumber IS NOT NULL AND v.episodeNumber IS NOT NULL)" +
                ") ORDER BY v.dateModified DESC";
        return em.createQuery(jpql, Video.class)
                .setMaxResults(maxResults)
                .getResultList();
    }

    private static class SeriesSortData {
        public LocalDateTime dateAdded;
        public LocalDateTime lastWatched;
        public SeriesSortData(LocalDateTime dateAdded, LocalDateTime lastWatched) {
            this.dateAdded = dateAdded;
            this.lastWatched = lastWatched;
        }
    }
    // ========== PAGINATION HELPER ==========

    public static class PaginatedVideos {
        public final List<Video> videos;
        public final long totalCount;

        public PaginatedVideos(List<Video> videos, long totalCount) {
            this.videos = videos;
            this.totalCount = totalCount;
        }
    }

    public static class PaginatedSeries {
        public final List<String> titles;
        public final long totalCount;

        public PaginatedSeries(List<String> titles, long totalCount) {
            this.titles = titles;
            this.totalCount = totalCount;
        }
    }

        // Legacy methods for Episode/Show conversion removed - using unified Video entity

        // Legacy converter methods removed - using unified Video entity
    }
