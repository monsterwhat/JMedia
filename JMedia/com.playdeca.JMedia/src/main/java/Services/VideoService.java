package Services;

import Models.Video;
import Models.Genre;
import Models.VideoGenre;
import Models.SubtitleTrack;
import Models.UserSubtitlePreferences;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoService {

    @PersistenceContext
    private EntityManager em;

    @Inject
    UserInteractionService userInteractionService;

    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;

    // ========== CORE VIDEO OPERATIONS ==========
    
    @Transactional
    public List<Video> findAll() {
        return Video.listAll();
    }

    @Transactional
    public Video find(Long id) {
        return Video.findById(id);
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
        return Video.list("type", type, "isActive", true, Sort.by("releaseYear", Sort.Direction.Descending));
    }

    // ========== SERIES/SPECIFIC QUERIES ==========

    @Transactional
    public List<String> findAllSeriesTitles() {
        return Video.<Video>list("type", "episode")
                .stream()
                .map(v -> v.seriesTitle)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        return Video.<Video>list("type = ?1 and seriesTitle = ?2 and isActive = ?3", "episode", seriesTitle)
                .stream()
                .map(v -> v.seasonNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findEpisodesForSeason(String seriesTitle, Integer seasonNumber) {
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and isActive = ?4", 
                         "episode", seriesTitle, seasonNumber,
                         Sort.by("episodeNumber", Sort.Direction.Ascending));
    }

    @Transactional
    public List<Video> findEpisodesForSeries(String seriesTitle) {
        return Video.list("type = ?1 and seriesTitle = ?2 and isActive = ?3", 
                         "episode", seriesTitle,
                         Sort.by("seasonNumber", Sort.Direction.Ascending)
                         .and("episodeNumber", Sort.Direction.Ascending));
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
        List<Genre> activeGenres = Genre.list("isActive", true, Sort.by("sortOrder", Sort.Direction.Ascending));

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
        return Video.<Video>list("isActive = ?1", true,
                         Sort.by("popularityScore", Sort.Direction.Descending))
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findNewlyAdded(int days, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        return Video.<Video>list("dateAdded >= ?1 AND isActive = ?2", cutoff, true,
                         Sort.by("dateAdded", Sort.Direction.Descending))
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findHighlyRated(double minRating, int limit) {
        return Video.<Video>list("isActive = ?1 AND imdbRating >= ?2", true, minRating,
                         Sort.by("imdbRating", Sort.Direction.Descending))
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findPopular(int limit) {
        return Video.<Video>list("isActive = ?1 AND popularityScore > ?2", true, 0.0,
                         Sort.by("popularityScore", Sort.Direction.Descending))
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
        return Video.<Video>list("quality = ?1 AND isActive = ?2", quality, true,
                         Sort.by("releaseYear", Sort.Direction.Descending))
                .stream()
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> filterByYearRange(int startYear, int endYear, int page, int limit) {
        return Video.<Video>list("releaseYear >= ?1 AND releaseYear <= ?2 AND isActive = ?3", 
                         startYear, endYear, true,
                         Sort.by("popularityScore", Sort.Direction.Descending))
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
            // Clear existing tracks
            if (video.subtitleTracks != null) {
                video.subtitleTracks.clear();
            } else {
                video.subtitleTracks = new ArrayList<>();
            }
            
            // Add new tracks
            video.subtitleTracks.addAll(tracks);
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
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

    // ========== UTILITY METHODS ==========

    private String detectVideoType(Models.MediaFile mediaFile) {
        // Simple type detection based on naming and metadata
        String filename = extractFilenameFromPath(mediaFile.path);
        if (filename.toLowerCase().contains("movie") || 
            mediaFile.path.toLowerCase().contains("movies") ||
            isTypicalMovieDuration(mediaFile.durationSeconds)) {
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
        return durationSeconds >= 5400 && durationSeconds <= 14400; // 1.5 - 4 hours
    }

    private boolean isTypicalEpisodeDuration(int durationSeconds) {
        return durationSeconds >= 1200 && durationSeconds <= 5400; // 20 - 90 minutes
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
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit) {
        List<Video> videos = Video.<Video>list("type", mediaType,
                Sort.by("dateAdded", Sort.Direction.Descending))
                .stream()
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
        
        // Get total count
        long totalCount = Video.count("type", mediaType);
        
        return new PaginatedVideos(videos, totalCount);
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

        // Legacy methods for Episode/Show conversion removed - using unified Video entity

        // Legacy converter methods removed - using unified Video entity
    }
