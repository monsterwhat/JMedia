package Services;

import Models.Video;
import Models.MediaFile;
import Models.DTOs.TvShowDTO;
import Services.SmartNamingService;
import Models.Genre;
import Models.VideoGenre;
import Models.SubtitleTrack;
import Models.Profile;
import Models.UserSubtitlePreferences;
import Models.AudioTrack;
import Models.ProfileSessionState;
import Models.VideoState;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.io.File;
import java.nio.file.Files;
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

    @Inject
    VideoMetadataService videoMetadataService;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    VideoStateService videoStateService;

    @Inject
    ProfileSessionStateService profileSessionStateService;

    // ========== CORE VIDEO OPERATIONS ==========
    
    @Transactional
    public List<Video> findAll() {
        return Video.listAll();
    }

    /**
     * Manage-panel TV shows: groups episodes by series title. Must run in a
     * transaction (service layer, not API) so the lazy Video.series association
     * is resolved while building the DTOs.
     */
    @Transactional
    public List<TvShowDTO> findTvShowsForManage(String search) {
        List<Video> episodes = em.createQuery(
                "SELECT v FROM Video v LEFT JOIN FETCH v.series WHERE v.type = 'episode' AND v.seriesTitle IS NOT NULL",
                Video.class).getResultList();

        if (search != null && !search.isEmpty()) {
            String lowerSearch = search.toLowerCase();
            episodes = episodes.stream()
                    .filter(v -> (v.title != null && v.title.toLowerCase().contains(lowerSearch)) ||
                                 (v.seriesTitle != null && v.seriesTitle.toLowerCase().contains(lowerSearch)) ||
                                 (v.filename != null && v.filename.toLowerCase().contains(lowerSearch)))
                    .collect(Collectors.toList());
        }

        Map<String, List<Video>> grouped = episodes.stream()
                .collect(Collectors.groupingBy(v -> v.seriesTitle));

        return grouped.entrySet().stream()
                .map(entry -> new TvShowDTO(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.seriesTitle.compareToIgnoreCase(b.seriesTitle))
                .collect(Collectors.toList());
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
        if (seasonNumber == null) {
            return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber is null and (folder is null or folder = '') and isActive = ?3 and (contentType is null or contentType = 'episode')",
                             Sort.by("episodeNumber", Sort.Direction.Ascending),
                             "episode", seriesTitle, true);
        }
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and (folder is null or folder = '') and isActive = ?4 and (contentType is null or contentType = 'episode')",
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
        if (seasonNumber == null) {
            return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber is null and folder = ?3 and isActive = ?4 and (contentType is null or contentType = 'episode')",
                             Sort.by("episodeNumber", Sort.Direction.Ascending),
                             "episode", seriesTitle, folder, true);
        }
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and folder = ?4 and isActive = ?5 and (contentType is null or contentType = 'episode')",
                         Sort.by("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, seasonNumber, folder, true);
    }

    @Transactional
    public long countEpisodesInFolder(String seriesTitle, Integer seasonNumber, String folder) {
        if (folder == null || folder.isEmpty()) return 0;
        if (seasonNumber == null) {
            return Video.count("type = ?1 and seriesTitle = ?2 and seasonNumber is null and folder = ?3 and isActive = ?4",
                              "episode", seriesTitle, folder, true);
        }
        return Video.count("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and folder = ?4 and isActive = ?5",
                          "episode", seriesTitle, seasonNumber, folder, true);
    }

    @Transactional
    public List<Video> findEpisodesForContentType(String seriesTitle, String contentType) {
        // Content type episodes may be null-season (e.g. SP01) OR season-anchored
        // (e.g. S10X01 specials that keep their season but have contentType = "special").
        // Guard: regular episodes carry contentType = "episode" — only match null-season
        // ones for that value, otherwise this would return every episode of the series.
        return Video.list("type = ?1 and seriesTitle = ?2 and contentType = ?3 and isActive = ?4 and (seasonNumber is null or contentType <> 'episode')",
                         Sort.by("seasonNumber", Sort.Direction.Ascending)
                         .and("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, contentType, true);
    }

    @Transactional
    public List<Video> findEpisodesForSeries(String seriesTitle) {
        return Video.list("type = ?1 and seriesTitle = ?2 and isActive = ?3",
                         Sort.by("seasonNumber", Sort.Direction.Ascending)
                         .and("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, true);
    }

    @Transactional
    public String getSeriesSynopsis(String seriesTitle) {
        if (seriesTitle == null || seriesTitle.isBlank()) {
            return "";
        }
        try {
            Models.Series series = Models.Series.find("title", seriesTitle).firstResult();
            if (series != null) {
                String synopsis = series.description != null ? series.description : series.overview;
                if (synopsis != null && !synopsis.isBlank()) {
                    return synopsis;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not load synopsis for series '{}': {}", seriesTitle, e.getMessage());
        }
        return "";
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

        // 1. Find movies via VideoGenre join table (existing behavior)
        int offset = (page - 1) * limit;
        
        String movieQuery = "SELECT DISTINCT v FROM Video v JOIN VideoGenre vg ON v.id = vg.video.id WHERE vg.genre.id = :genreId AND v.isActive = :isActive ORDER BY vg.relevance DESC, v.popularityScore DESC";
        
        List<Video> movieResults = em.createQuery(movieQuery, Video.class)
                .setParameter("genreId", genre.id)
                .setParameter("isActive", true)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultStream()
                .collect(Collectors.toList());

        // 2. Also find episodes whose Series entity has matching genres
        String genreName = genre.name;
        List<Video> episodeResults = new ArrayList<>();
        // Use MEMBER OF for @ElementCollection
        String seriesQuery = "SELECT s FROM Series s WHERE :genreName MEMBER OF s.genres";
        List<Models.Series> seriesWithGenre = em.createQuery(seriesQuery, Models.Series.class)
            .setParameter("genreName", genreName)
            .getResultList();

        // For each matching Series, find active episodes
        if (!seriesWithGenre.isEmpty()) {
            List<String> seriesTitles = seriesWithGenre.stream()
                .map(s -> s.title)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

            if (!seriesTitles.isEmpty()) {
                String episodeQuery = "SELECT v FROM Video v WHERE v.isActive = :isActive AND v.type = 'episode' AND v.seriesTitle IN :seriesTitles";
                List<Video> allEpisodes = em.createQuery(episodeQuery, Video.class)
                    .setParameter("isActive", true)
                    .setParameter("seriesTitles", seriesTitles)
                    .getResultList();

                // Deduplicate by seriesTitle (one show per series)
                Set<String> seenSeries = new HashSet<>();
                for (Video ep : allEpisodes) {
                    String key = ep.seriesTitle != null ? ep.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "") : String.valueOf(ep.id);
                    if (seenSeries.add(key)) {
                        episodeResults.add(ep);
                    }
                }
            }
        }

        // 3. Merge: movie results first, then episode results (deduped by video.id)
        Set<Long> seenIds = new HashSet<>();
        List<Video> merged = new ArrayList<>();
        for (Video v : movieResults) {
            if (v.id != null && seenIds.add(v.id)) {
                merged.add(v);
            }
        }
        for (Video v : episodeResults) {
            if (v.id != null && seenIds.add(v.id)) {
                merged.add(v);
            }
        }

        // Apply pagination to merged results
        return merged.stream()
            .skip(offset)
            .limit(limit)
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
    
    // ========== AUDIO TRACKS & PLAYBACK PROGRESS (migrated from VideoAPI) ==========

    @Transactional
    public List<AudioTrack> getAudioTracks(Long videoId) {
        if (Video.findById(videoId) == null) {
            return null;
        }
        List<AudioTrack> tracks = AudioTrack.list("video.id", videoId);
        return tracks == null ? new ArrayList<>() : tracks;
    }

    @Transactional
    public Boolean toggleWatched(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return null;
        }
        VideoState state = videoStateService.getOrCreate(video);
        if (state == null) {
            return null;
        }
        state.watched = !Boolean.TRUE.equals(state.watched);
        if (Boolean.TRUE.equals(state.watched)) {
            state.watchProgress = 1.0;
        } else {
            state.watchProgress = 0.0;
            state.currentTime = 0.0;
        }
        state.persist();
        return Boolean.TRUE.equals(state.watched);
    }

    @Transactional
    public Boolean removeFromContinueWatching(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return null;
        }
        videoStateService.removeFromContinueWatching(video);
        return true;
    }

    @Transactional
    public boolean reportProgress(Long videoId, double progressSeconds) {
        Video video = Video.findById(videoId);
        if (video != null) {
            videoStateService.updateProgress(video, progressSeconds);
        }

        // Also update the ephemeral ProfileSessionState for real-time UI synchronization if needed
        try {
            ProfileSessionState state = profileSessionStateService.getOrCreate();
            if (state != null && videoId.equals(state.currentVideoId)) {
                state.currentTime = progressSeconds;
                state = profileSessionStateService.save(state);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not sync ProfileSessionState for video {}: {}", videoId, e.getMessage());
        }
        return true;
    }

    // ========== CINEMA HOME (migrated from VideoUiApi.getCinemaHomeFragment) ==========

    /**
     * Owns the ENTIRE cinema-home data workflow inside ONE transaction: the direct
     * Panache queries, continue-watching items, seriesMap dedupe, series enrichment,
     * seriesCache proxy reads, v.genres / series.genres genre maps, and hero-item
     * building. Returns fully-initialized detached data (plain maps + entities whose
     * scalar columns are all loaded) so the API can do lazy-safe HTML assembly only.
     */
    @Transactional
    public CinemaHomeData getCinemaHomeData() {
        // Targeted paginated queries
        List<Video> movies = Video.find("isActive = ?1 and type = ?2 order by dateAdded desc", true, "movie")
            .range(0, 99).list();
        List<Video> episodes = Video.find("isActive = ?1 and type = ?2 and seriesTitle is not null order by dateAdded desc", true, "episode")
            .list();

        // --- Continue Watching ---
        List<Video> cwItems = new ArrayList<>();
        java.util.Set<String> seenCW = new java.util.HashSet<>();
        List<VideoState> inProgress = videoStateService.getInProgressVideos();
        for (VideoState vs : inProgress) {
            if (vs.video != null && vs.video.isActive) {
                String key = "cw:" + vs.video.id;
                if (seenCW.add(key)) {
                    // Attach progress data
                    vs.video.watchProgress = vs.watchProgress;
                    if (vs.watchProgress != null) {
                        vs.video.watchProgressPercent = (int) (vs.watchProgress * 100);
                    }
                    cwItems.add(vs.video);
                }
                if (cwItems.size() >= 10) break;
            }
        }

        // --- TV Shows (deduped by seriesTitle, one card per show) ---
        Map<String, Video> seriesMap = new LinkedHashMap<>();
        for (Video v : episodes) {
            String key = v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
            Video existing = seriesMap.get(key);
            if (existing == null || (v.dateAdded != null && existing.dateAdded != null && v.dateAdded.isAfter(existing.dateAdded))) {
                seriesMap.put(key, v);
            }
        }
        List<Video> tvShows = new ArrayList<>(seriesMap.values());
        if (tvShows.size() > 20) tvShows = tvShows.subList(0, 20);

        // --- Cache and enrich Series entities for TV shows ---
        Map<String, Models.Series> seriesCache = new HashMap<>();
        for (Video v : tvShows) {
            try {
                String seriesTitle = v.seriesTitle;
                if (seriesTitle == null || seriesTitle.isBlank()) continue;
                String cacheKey = seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (seriesCache.containsKey(cacheKey)) continue;
                Models.Series series = v.series;
                if (series == null) {
                    series = Models.Series.find("title", seriesTitle).firstResult();
                }
                if (series != null && series.id != null) {
                    videoMetadataService.enrichSeriesTextMetadataAsync(series.id);
                    seriesCache.put(cacheKey, series);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not enrich series for '{}': {}", v.seriesTitle, e.getMessage());
            }
        }

        // Bulk-load every Series once so per-card rendering never queries the DB (N+1)
        Map<String, Models.Series> allSeriesByTitle = new HashMap<>();
        for (Models.Series s : Models.Series.<Models.Series>listAll()) {
            if (s.title == null || s.title.isBlank()) continue;
            String sKey = s.title.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (!sKey.isEmpty()) allSeriesByTitle.putIfAbsent(sKey, s);
        }

        // --- Trending: dedup by seriesTitle, sorted by rating ---
        Map<String, Video> trendingMap = new LinkedHashMap<>();
        List<Video> allCombined = new ArrayList<>();
        allCombined.addAll(movies);
        allCombined.addAll(episodes);
        for (Video v : allCombined) {
            double rating = 0.0;
            if (v.imdbRating != null) rating = v.imdbRating;
            else if (v.tmdbRating != null) rating = v.tmdbRating;
            else if ("episode".equalsIgnoreCase(v.type) && v.seriesTitle != null) {
                try {
                    String trendingKey = v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                    Models.Series series = allSeriesByTitle.get(trendingKey);
                    if (series != null) {
                        rating = series.tmdbRating != null ? series.tmdbRating : (series.imdbRating != null ? series.imdbRating : 0.0);
                    }
                } catch (Exception e) {
                    // Fall through with 0.0 rating
                }
            }
            String key = (v.seriesTitle != null ? v.seriesTitle : v.title != null ? v.title : "").toLowerCase().replaceAll("[^a-z0-9]", "");
            if (key.isEmpty()) continue;
            Video existing = trendingMap.get(key);
            double existingRating = existing != null ? (existing.imdbRating != null ? existing.imdbRating : (existing.tmdbRating != null ? existing.tmdbRating : 0.0)) : 0.0;
            if (existingRating <= 0 && existing != null && "episode".equalsIgnoreCase(existing.type) && existing.seriesTitle != null) {
                try {
                    String exKey = existing.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                    Models.Series exSeries = allSeriesByTitle.get(exKey);
                    if (exSeries != null) {
                        existingRating = exSeries.tmdbRating != null ? exSeries.tmdbRating : (exSeries.imdbRating != null ? exSeries.imdbRating : 0.0);
                    }
                } catch (Exception e) {}
            }
            if (existing == null || rating > existingRating) {
                trendingMap.put(key, v);
            }
        }
        List<Video> trending = new ArrayList<>(trendingMap.values());
        trending.sort((a, b) -> {
            double ra = 0.0, rb = 0.0;
            if (a.imdbRating != null) ra = a.imdbRating;
            else if (a.tmdbRating != null) ra = a.tmdbRating;
            else if ("episode".equalsIgnoreCase(a.type) && a.seriesTitle != null) {
                String k = a.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                Models.Series s = seriesCache.get(k);
                if (s != null) ra = s.tmdbRating != null ? s.tmdbRating : (s.imdbRating != null ? s.imdbRating : 0.0);
            }
            if (b.imdbRating != null) rb = b.imdbRating;
            else if (b.tmdbRating != null) rb = b.tmdbRating;
            else if ("episode".equalsIgnoreCase(b.type) && b.seriesTitle != null) {
                String k = b.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                Models.Series s = seriesCache.get(k);
                if (s != null) rb = s.tmdbRating != null ? s.tmdbRating : (s.imdbRating != null ? s.imdbRating : 0.0);
            }
            return Double.compare(rb, ra);
        });
        if (trending.size() > 20) trending = trending.subList(0, 20);

        // --- Recently Updated: episodes sorted by dateAdded, deduped by series ---
        List<Video> recentlyUpdated = episodes.stream()
            .sorted((a, b) -> {
                if (a.dateAdded == null && b.dateAdded == null) return 0;
                if (a.dateAdded == null) return 1;
                if (b.dateAdded == null) return -1;
                return b.dateAdded.compareTo(a.dateAdded);
            })
            .collect(Collectors.toList());
        // Dedupe by seriesTitle
        Set<String> seenSeries = new HashSet<>();
        List<Video> dedupedUpdates = new ArrayList<>();
        for (Video v : recentlyUpdated) {
            String key = v.seriesTitle != null ? v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "") : String.valueOf(v.id);
            if (seenSeries.add(key)) {
                dedupedUpdates.add(v);
            }
        }
        if (dedupedUpdates.size() > 20) dedupedUpdates = dedupedUpdates.subList(0, 20);

        // --- Recently Added Movies (sorted by dateAdded) ---
        List<Video> recentlyAddedMovies = movies.stream()
            .sorted((a, b) -> {
                if (a.dateAdded == null && b.dateAdded == null) return 0;
                if (a.dateAdded == null) return 1;
                if (b.dateAdded == null) return -1;
                return b.dateAdded.compareTo(a.dateAdded);
            })
            .limit(20)
            .collect(Collectors.toList());

        // --- Build movie genre map (deduplicated across genres) ---
        Map<String, List<Video>> movieGenreMap = new LinkedHashMap<>();
        for (Video v : movies) {
            if (v.genres != null) {
                for (String g : v.genres) {
                    if (g != null && !g.equalsIgnoreCase("anime")) {
                        movieGenreMap.computeIfAbsent(g, k -> new ArrayList<>()).add(v);
                    }
                }
            }
        }
        // Filter genres with >= 2 movies, sort by count desc
        List<Map.Entry<String, List<Video>>> movieGenreEntries = new ArrayList<>(movieGenreMap.entrySet());
        movieGenreEntries.removeIf(e -> e.getValue().size() < 2);
        movieGenreEntries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        // Deduplicate: each video appears only in the first (largest) genre row it matches
        Set<Long> movieIdsInGenres = new HashSet<>();
        for (Map.Entry<String, List<Video>> entry : movieGenreEntries) {
            List<Video> deduped = new ArrayList<>();
            for (Video v : entry.getValue()) {
                if (v.id != null && movieIdsInGenres.add(v.id)) {
                    deduped.add(v);
                }
            }
            entry.setValue(deduped);
        }
        movieGenreEntries.removeIf(e -> e.getValue().size() < 2);

        // --- Recently Added TV Shows (deduped, sorted by dateAdded) ---
        List<Video> recentlyAddedShows = new ArrayList<>(seriesMap.values());
        recentlyAddedShows.sort((a, b) -> {
            if (a.dateAdded == null && b.dateAdded == null) return 0;
            if (a.dateAdded == null) return 1;
            if (b.dateAdded == null) return -1;
            return b.dateAdded.compareTo(a.dateAdded);
        });
        if (recentlyAddedShows.size() > 20) recentlyAddedShows = recentlyAddedShows.subList(0, 20);

        // --- Build TV show genre map (deduplicated across genres) ---
        Map<String, List<Video>> tvGenreMap = new LinkedHashMap<>();
        for (Video v : tvShows) {
            String cacheKey = v.seriesTitle != null ? v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "") : null;
            Models.Series series = cacheKey != null ? seriesCache.get(cacheKey) : null;
            List<String> genres = (series != null && series.genres != null && !series.genres.isEmpty())
                ? series.genres
                : ((v.series != null && v.series.genres != null && !v.series.genres.isEmpty())
                    ? v.series.genres
                    : v.genres);
            if (genres != null) {
                for (String g : genres) {
                    if (g != null && !g.equalsIgnoreCase("anime")) {
                        tvGenreMap.computeIfAbsent(g, k -> new ArrayList<>()).add(v);
                    }
                }
            }
        }
        List<Map.Entry<String, List<Video>>> tvGenreEntries = new ArrayList<>(tvGenreMap.entrySet());
        tvGenreEntries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        // Deduplicate: each show appears only in the first (largest) genre row it matches
        Set<Long> tvIdsInGenres = new HashSet<>();
        for (Map.Entry<String, List<Video>> entry : tvGenreEntries) {
            List<Video> deduped = new ArrayList<>();
            for (Video v : entry.getValue()) {
                if (v.id != null && tvIdsInGenres.add(v.id)) {
                    deduped.add(v);
                }
            }
            entry.setValue(deduped);
        }

        // --- Hero items: tiered selection ---
        List<Map<String, Object>> heroItemsList = new ArrayList<>();

        // Tier 1: Most recently watched from continue-watching
        if (!cwItems.isEmpty()) {
            for (Video v : cwItems.subList(0, Math.min(5, cwItems.size()))) {
                heroItemsList.add(buildCinemaHeroItem(v));
            }
        }

        // Tier 2: Highest-rated movie with description
        if (heroItemsList.isEmpty()) {
            movies.stream()
                .filter(v -> v.description != null && !v.description.isBlank())
                .sorted((a, b) -> {
                    double ra = a.imdbRating != null ? a.imdbRating : (a.tmdbRating != null ? a.tmdbRating : 0.0);
                    double rb = b.imdbRating != null ? b.imdbRating : (b.tmdbRating != null ? b.tmdbRating : 0.0);
                    return Double.compare(rb, ra);
                })
                .filter(v -> (v.imdbRating != null ? v.imdbRating : (v.tmdbRating != null ? v.tmdbRating : 0.0)) > 0)
                .limit(5)
                .forEach(v -> heroItemsList.add(buildCinemaHeroItem(v)));
        }

        // Tier 3: Most recently added movie
        if (heroItemsList.isEmpty()) {
            movies.stream()
                .sorted((a, b) -> {
                    if (a.dateAdded == null && b.dateAdded == null) return 0;
                    if (a.dateAdded == null) return 1;
                    if (b.dateAdded == null) return -1;
                    return b.dateAdded.compareTo(a.dateAdded);
                })
                .limit(5)
                .forEach(v -> heroItemsList.add(buildCinemaHeroItem(v)));
        }

        return new CinemaHomeData(movies, episodes, tvShows, allSeriesByTitle, trending,
            dedupedUpdates, recentlyAddedMovies, movieGenreEntries, recentlyAddedShows,
            tvGenreEntries, heroItemsList);
    }

    /**
     * Fully-initialized detached cinema-home data. Entity lists are loaded inside the
     * transaction with all scalar columns; the API must only read scalar fields and
     * plain maps — never trigger lazy loading on Video.series / Series.* / genres.
     */
    public static class CinemaHomeData {
        public final List<Video> movies;
        public final List<Video> episodes;
        public final List<Video> tvShows;
        public final Map<String, Models.Series> allSeriesByTitle;
        public final List<Video> trending;
        public final List<Video> recentlyUpdated;
        public final List<Video> recentlyAddedMovies;
        public final List<Map.Entry<String, List<Video>>> movieGenreEntries;
        public final List<Video> recentlyAddedShows;
        public final List<Map.Entry<String, List<Video>>> tvGenreEntries;
        public final List<Map<String, Object>> heroItemsList;

        public CinemaHomeData(List<Video> movies, List<Video> episodes, List<Video> tvShows,
                              Map<String, Models.Series> allSeriesByTitle, List<Video> trending,
                              List<Video> recentlyUpdated, List<Video> recentlyAddedMovies,
                              List<Map.Entry<String, List<Video>>> movieGenreEntries,
                              List<Video> recentlyAddedShows,
                              List<Map.Entry<String, List<Video>>> tvGenreEntries,
                              List<Map<String, Object>> heroItemsList) {
            this.movies = movies;
            this.episodes = episodes;
            this.tvShows = tvShows;
            this.allSeriesByTitle = allSeriesByTitle;
            this.trending = trending;
            this.recentlyUpdated = recentlyUpdated;
            this.recentlyAddedMovies = recentlyAddedMovies;
            this.movieGenreEntries = movieGenreEntries;
            this.recentlyAddedShows = recentlyAddedShows;
            this.tvGenreEntries = tvGenreEntries;
            this.heroItemsList = heroItemsList;
        }
    }

    /**
     * Builds hero item data map with pre-computed URLs (avoids lazy-loading series).
     * Must run inside getCinemaHomeData()'s transaction.
     */
    private Map<String, Object> buildCinemaHeroItem(Video v) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", v.id);
        item.put("type", v.type != null ? v.type : "movie");
        item.put("title", v.title != null ? v.title : (v.seriesTitle != null ? v.seriesTitle : ""));
        item.put("seriesTitle", v.seriesTitle);
        // Episodes show the series synopsis in the hero: per-episode recaps can be
        // very long, while the hero is a showcase for the whole title.
        String description = v.description != null ? v.description : (v.overview != null ? v.overview : "");
        if ("episode".equalsIgnoreCase(v.type) && v.seriesTitle != null) {
            String synopsis = resolveCinemaSeriesSynopsis(v);
            if (!synopsis.isBlank()) {
                description = synopsis;
            }
        }
        item.put("description", description);
        item.put("overview", v.overview);
        // Episodes carry no rating/year of their own (fields default to 0.0) —
        // fall back to the series values so TV shows still show rating and year.
        Double imdbRating = v.imdbRating != null && v.imdbRating > 0 ? v.imdbRating : null;
        Double tmdbRating = v.tmdbRating != null && v.tmdbRating > 0 ? v.tmdbRating : null;
        Integer releaseYear = v.releaseYear;
        if ("episode".equalsIgnoreCase(v.type)) {
            Models.Series series = resolveCinemaSeries(v);
            if (series != null) {
                if (imdbRating == null && series.imdbRating != null && series.imdbRating > 0) {
                    imdbRating = series.imdbRating;
                }
                if (tmdbRating == null && series.tmdbRating != null && series.tmdbRating > 0) {
                    tmdbRating = series.tmdbRating;
                }
                if (releaseYear == null && series.releaseYear != null) {
                    releaseYear = series.releaseYear;
                }
            }
        }
        item.put("imdbRating", imdbRating);
        item.put("tmdbRating", tmdbRating);
        item.put("releaseYear", releaseYear);
        item.put("duration", v.duration != null ? v.duration : 0L);
        item.put("favorite", v.favorite);
        item.put("watchProgressPercent", v.watchProgressPercent != null ? v.watchProgressPercent : 0);
        return item;
    }

    /**
     * Returns the series synopsis for an episode, or "" when unavailable.
     * Looks up the Series via the video's relationship first, then by title.
     */
    private String resolveCinemaSeriesSynopsis(Video v) {
        Models.Series series = resolveCinemaSeries(v);
        if (series != null) {
            String synopsis = series.description != null ? series.description : series.overview;
            if (synopsis != null && !synopsis.isBlank()) {
                return synopsis;
            }
        }
        return "";
    }

    /**
     * Resolves the Series entity for an episode, or null when unavailable.
     * Looks up the Series via the video's relationship first, then by title.
     */
    private Models.Series resolveCinemaSeries(Video v) {
        if (v == null || !"episode".equalsIgnoreCase(v.type) || v.seriesTitle == null || v.seriesTitle.isBlank()) {
            return null;
        }
        try {
            Models.Series series = v.series;
            if (series == null) {
                series = Models.Series.find("title", v.seriesTitle).firstResult();
            }
            return series;
        } catch (Exception e) {
            LOGGER.debug("Could not load series for '{}': {}", v.seriesTitle, e.getMessage());
            return null;
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
    public void updateSeriesMetadata(String seriesTitle, String posterPath, String backdropPath, String showImdbId, String logoPath) {
        if (seriesTitle == null) return;
        List<Video> videos = findEpisodesForSeries(seriesTitle);
        for (Video v : videos) {
            if (posterPath != null && !posterPath.isBlank()) v.posterPath = posterPath;
            if (backdropPath != null && !backdropPath.isBlank()) v.backdropPath = backdropPath;
            if (showImdbId != null && !showImdbId.isBlank()) v.showImdbId = showImdbId;
            if (logoPath != null && !logoPath.isBlank()) v.logoPath = logoPath;
            v.dateModified = LocalDateTime.now();
            v.persist();
        }
        LOGGER.info("Updated series metadata for '{}' ({} videos)", seriesTitle, videos.size());
    }

    @Transactional
    public void updateSeriesMetadata(String seriesTitle, String posterPath, String backdropPath) {
        updateSeriesMetadata(seriesTitle, posterPath, backdropPath, (String) null);
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

    // ========== API TRANSACTION-BOUNDARY HELPERS ==========

    /**
     * API transaction-boundary helper: persists the "update video" form in one
     * transaction — the metadata fields plus the optional TMDb ID and intro/outro
     * timestamps. Mirrors the old inline API flow exactly (metadata write first,
     * then the TMDb/intro/outro field writes with {@code video.persist()}).
     */
    @Transactional
    public void updateVideoFields(Long id, String title, String seriesTitle, String episodeTitle, Integer seasonNumber, Integer episodeNumber, String type, String showImdbId, String imdbId, String tmdbId, Double introStart, Double introEnd, Double outroStart, Double outroEnd) {
        updateMetadata(id, title, seriesTitle, episodeTitle, seasonNumber, episodeNumber, type, showImdbId, imdbId);

        // Also update TMDb ID and intro/outro timestamps if provided
        if (tmdbId != null || introStart != null || introEnd != null || outroStart != null || outroEnd != null) {
            Video video = find(id);
            if (video != null) {
                if (tmdbId != null && !tmdbId.isBlank()) video.tmdbId = tmdbId;
                if (introStart != null) video.introStart = introStart;
                if (introEnd != null) video.introEnd = introEnd;
                if (outroStart != null) video.outroStart = outroStart;
                if (outroEnd != null) video.outroEnd = outroEnd;
                video.dateModified = LocalDateTime.now();
                video.persist();
            }
        }
    }

    /**
     * API transaction-boundary helper: applies user search overrides then runs
     * metadata enrichment inside the same transaction. The field mutation MUST
     * happen before {@link VideoMetadataService#fetchAndEnrichMetadata} — the
     * metadata fetch re-reads the same managed entity and would otherwise miss
     * the user's corrections. Returns the enriched video (or null when the id
     * is unknown), safely materialized for use after the transaction ends.
     */
    @Transactional
    public Video applySearchEnrichOverrides(Long id, String title, String seriesTitle, Integer seasonNumber, Integer episodeNumber, String imdbId, String showImdbId) {
        Video video = find(id);
        if (video == null) {
            return null;
        }

        // Apply overrides — only update non-null, non-blank values the user explicitly set
        if (title != null && !title.isBlank()) video.title = title;
        if (seriesTitle != null && !seriesTitle.isBlank()) video.seriesTitle = seriesTitle;
        if (seasonNumber != null && seasonNumber > 0) video.seasonNumber = seasonNumber;
        if (episodeNumber != null && episodeNumber > 0) video.episodeNumber = episodeNumber;
        if (imdbId != null && !imdbId.isBlank()) video.imdbId = imdbId;
        if (showImdbId != null && !showImdbId.isBlank()) video.showImdbId = showImdbId;
        video.dateModified = LocalDateTime.now();

        videoMetadataService.fetchAndEnrichMetadata(video);
        return video;
    }

    /**
     * API transaction-boundary helper: applies verification panel selections to
     * a video inside a transaction. Returns the updated video (or null when the
     * id is unknown).
     */
    @Transactional
    public Video applyVerificationSelections(Long id, Map<String, Object> selections) {
        Video video = find(id);
        if (video == null) {
            return null;
        }
        if (selections.containsKey("title")) {
            video.title = (String) selections.get("title");
            if (selections.containsKey("_titleManual"))
                video.titleManuallyEdited = Boolean.TRUE.equals(selections.get("_titleManual"));
        }
        if (selections.containsKey("seriesTitle")) {
            video.seriesTitle = (String) selections.get("seriesTitle");
            if (selections.containsKey("_seriesTitleManual"))
                video.seriesTitleManuallyEdited = Boolean.TRUE.equals(selections.get("_seriesTitleManual"));
        }
        if (selections.containsKey("episodeTitle")) {
            video.episodeTitle = (String) selections.get("episodeTitle");
        }
        if (selections.containsKey("seasonNumber")) {
            Object val = selections.get("seasonNumber");
            video.seasonNumber = val instanceof Number ? ((Number) val).intValue() : null;
        }
        if (selections.containsKey("episodeNumber")) {
            Object val = selections.get("episodeNumber");
            video.episodeNumber = val instanceof Number ? ((Number) val).intValue() : null;
        }
        if (selections.containsKey("imdbId")) {
            video.imdbId = (String) selections.get("imdbId");
        }
        if (selections.containsKey("showImdbId")) {
            video.showImdbId = (String) selections.get("showImdbId");
        }
        if (selections.containsKey("tmdbId")) {
            video.tmdbId = (String) selections.get("tmdbId");
        }
        video.dateModified = LocalDateTime.now();
        video.persist();
        return video;
    }

    /**
     * Outcome of {@link #refetchSeriesImages}, mapped by the API back to its
     * original HTTP responses (404/400/200/500) after the migration.
     */
    public record RefetchImagesResult(RefetchStatus status, String message) {
        public enum RefetchStatus { NOT_FOUND, BAD_REQUEST, OK, ERROR }
    }

    /**
     * API transaction-boundary helper: force-refetch TMDB images for a series and
     * apply the fresh artwork to every episode. Runs in a transaction so the
     * episode mutations (including {@code video.persist()}) are committed before
     * the entities are handed back to the API layer.
     */
    @Transactional
    public RefetchImagesResult refetchSeriesImages(String seriesTitle) {
        List<Video> episodes = findEpisodesForSeries(seriesTitle);
        if (episodes.isEmpty()) {
            return new RefetchImagesResult(RefetchImagesResult.RefetchStatus.NOT_FOUND, "Series not found");
        }

        Video representative = episodes.get(0);
        String type = representative.type != null ? representative.type : "episode";
        String title = "episode".equalsIgnoreCase(type) && representative.seriesTitle != null
                ? representative.seriesTitle
                : (representative.title != null ? representative.title : representative.seriesTitle);

        if (title == null || title.isBlank()) {
            return new RefetchImagesResult(RefetchImagesResult.RefetchStatus.BAD_REQUEST, "No title available for image fetch");
        }

        try {
            VideoMetadataService.MediaImages tmdbImages = videoMetadataService.fetchMediaImages(type, title, representative.releaseYear,
                    representative.seriesTitle, representative.seasonNumber, representative.episodeNumber);
            LOGGER.info("[RefetchImages] TMDB result for '{}': poster={}, backdrop={}, logo={}, hero={}, still={}", title, tmdbImages.posterPath().isPresent(), tmdbImages.backdropPath().isPresent(), tmdbImages.logoPath().isPresent(), tmdbImages.heroPath().isPresent(), tmdbImages.stillPath().isPresent());
            ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                    tmdbImages.posterPath(), tmdbImages.logoPath(),
                    tmdbImages.backdropPath(), tmdbImages.heroPath(),
                    tmdbImages.stillPath());

            // Force refetch: delete existing image files so they get re-downloaded
            Path thumbnailsDir = thumbnailService.getThumbnailDirectory();
            for (String suffix : new String[]{"poster", "logo", "backdrop", "hero", "still"}) {
                for (String ext : new String[]{".webp", ".jpg", ".png", ".gif"}) {
                    Path existing = thumbnailsDir.resolve(representative.id + "_" + suffix + ext);
                    try { Files.deleteIfExists(existing); } catch (Exception ignored) {}
                }
            }

            ThumbnailService.MediaImagePaths paths = thumbnailService.downloadMediaImages(representative.id, localImages);
            LOGGER.info("[RefetchImages] Downloaded paths for video {}: poster={}, backdrop={}, logo={}, hero={}, still={}", representative.id, paths.posterPath(), paths.backdropPath(), paths.logoPath(), paths.heroPath(), paths.stillPath());

            LOGGER.info("[RefetchImages] Updating {} episodes for series '{}'", episodes.size(), seriesTitle);
            int updated = 0;
            for (Video v : episodes) {
                boolean changed = false;
                if (paths.posterPath() != null) { v.posterPath = paths.posterPath(); changed = true; }
                if (paths.backdropPath() != null) { v.backdropPath = paths.backdropPath(); changed = true; }
                if (paths.logoPath() != null) { v.logoPath = paths.logoPath(); changed = true; }
                if (paths.heroPath() != null) { v.heroPath = paths.heroPath(); changed = true; }
                if (paths.stillPath() != null) { v.stillPath = paths.stillPath(); changed = true; }
                if (changed) {
                    v.dateModified = LocalDateTime.now();
                    v.persist();
                    updated++;
                }
            }

            String safeTitle = seriesTitle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            LOGGER.info("Refetched images for series '{}': updated {} episodes", seriesTitle, updated);
            return new RefetchImagesResult(RefetchImagesResult.RefetchStatus.OK, "Refetched images for '" + safeTitle + "'. Updated " + updated + " episodes.");
        } catch (Exception e) {
            LOGGER.error("Failed to refetch images for series '{}': {}", seriesTitle, e.getMessage(), e);
            return new RefetchImagesResult(RefetchImagesResult.RefetchStatus.ERROR, "Failed to refetch images: " + e.getMessage());
        }
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
