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
import java.io.File;
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

    // ========== CORE VIDEO OPERATIONS ==========
    
    @Transactional
    public List<Video> findAll() {
        return Video.listAll();
    }

    @Transactional
    public Video find(Long id) {
        Video video = Video.findById(id);
        if (video != null && (video.duration == null || video.duration <= 0)) {
            probeVideoDuration(video);
        }
        return video;
    }

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Transactional
    public void probeVideoMetadata(Video video) {
        if (video == null || video.id == null || video.path == null) return;
        
        // Re-fetch to avoid "Detached Entity" error and ensure we have the latest data
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        // Skip if already probed by another thread
        if (managedVideo.videoCodec != null && managedVideo.duration > 0) return;

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) return;

        try {
            String probePath = discoveryService.findFFprobeExecutable();
            if (probePath == null) return;
            
            // Get duration
            ProcessBuilder pbDur = new ProcessBuilder(
                probePath, 
                "-v", "error", 
                "-show_entries", "format=duration", 
                "-of", "default=noprint_wrappers=1:nokey=1", 
                managedVideo.path
            );
            Process pDur = pbDur.start();
            java.util.Scanner sDur = new java.util.Scanner(pDur.getInputStream());
            if (sDur.hasNextDouble()) {
                managedVideo.duration = (long) (sDur.nextDouble() * 1000);
            }
            pDur.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            // Get video codec
            ProcessBuilder pbCodec = new ProcessBuilder(
                probePath, 
                "-v", "error", 
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name", 
                "-of", "default=noprint_wrappers=1:nokey=1", 
                managedVideo.path
            );
            Process pCodec = pbCodec.start();
            java.util.Scanner sCodec = new java.util.Scanner(pCodec.getInputStream());
            if (sCodec.hasNext()) {
                managedVideo.videoCodec = sCodec.next();
            }
            pCodec.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            // Get audio codec
            ProcessBuilder pbAudio = new ProcessBuilder(
                probePath, 
                "-v", "error", 
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name", 
                "-of", "default=noprint_wrappers=1:nokey=1", 
                managedVideo.path
            );
            Process pAudio = pbAudio.start();
            java.util.Scanner sAudio = new java.util.Scanner(pAudio.getInputStream());
            if (sAudio.hasNext()) {
                managedVideo.audioCodec = sAudio.next();
            }
            pAudio.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            managedVideo.persist();
            // Update the passed object as well for immediate use
            video.videoCodec = managedVideo.videoCodec;
            video.audioCodec = managedVideo.audioCodec;
            video.duration = managedVideo.duration;
            
            LOGGER.info("Successfully probed and saved metadata for video ID {}: {}ms, videoCodec={}, audioCodec={}", 
                       managedVideo.id, managedVideo.duration, managedVideo.videoCodec, managedVideo.audioCodec);
        } catch (Exception e) {
            LOGGER.error("Error probing metadata for video {}: {}", video.id, e.getMessage());
            probeVideoDuration(video);
        }
    }

    @Transactional
    public void probeVideoDuration(Video video) {
        if (video == null || video.id == null || video.path == null) return;
        
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) return;

        try {
            String probePath = discoveryService.findFFprobeExecutable();
            ProcessBuilder pb;
            
            if (probePath != null) {
                pb = new ProcessBuilder(
                    probePath, 
                    "-v", "error", 
                    "-show_entries", "format=duration", 
                    "-of", "default=noprint_wrappers=1:nokey=1", 
                    managedVideo.path
                );
            } else {
                pb = new ProcessBuilder(ffmpegPath, "-i", managedVideo.path);
            }

            Process process = pb.start();
            java.util.Scanner scanner = new java.util.Scanner(process.getInputStream());
            if (scanner.hasNextDouble()) {
                double durationSeconds = scanner.nextDouble();
                managedVideo.duration = (long) (durationSeconds * 1000);
                managedVideo.persist();
                video.duration = managedVideo.duration;
                LOGGER.info("Successfully probed and updated duration for video ID {}: {}ms", managedVideo.id, managedVideo.duration);
            } else {
                java.util.Scanner errScanner = new java.util.Scanner(process.getErrorStream());
                while (errScanner.hasNextLine()) {
                    String line = errScanner.nextLine();
                    if (line.contains("Duration:")) {
                        String durationStr = line.split("Duration:")[1].split(",")[0].trim();
                        String[] parts = durationStr.split(":");
                        if (parts.length == 3) {
                            double h = Double.parseDouble(parts[0]);
                            double m = Double.parseDouble(parts[1]);
                            double s = Double.parseDouble(parts[2]);
                            managedVideo.duration = (long) ((h * 3600 + m * 60 + s) * 1000);
                            managedVideo.persist();
                            video.duration = managedVideo.duration;
                            LOGGER.info("Successfully parsed duration for video ID {}: {}ms", managedVideo.id, managedVideo.duration);
                            break;
                        }
                    }
                }
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("Error probing duration for video {}: {}", video.id, e.getMessage());
        }
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
        return Video.list("type = ?1 and isActive = ?2", Sort.by("releaseYear", Sort.Direction.Descending), type, true);
    }

    // ========== SERIES/SPECIFIC QUERIES ==========

    @Transactional
    public List<String> findAllSeriesTitles() {
        return Video.<Video>list("type = ?1", "episode")
                .stream()
                .map(v -> v.seriesTitle)
                .filter(Objects::nonNull)
                .distinct()
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
            // If searching for season 1, also include episodes with null season number
            return Video.list("type = ?1 and seriesTitle = ?2 and (seasonNumber = ?3 or seasonNumber is null) and isActive = ?4",
                             Sort.by("episodeNumber", Sort.Direction.Ascending),
                             "episode", seriesTitle, 1, true);
        }
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3 and isActive = ?4",
                         Sort.by("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, seasonNumber, true);
    }

    @Transactional
    public List<Video> findEpisodesForSeries(String seriesTitle) {
        return Video.list("type = ?1 and seriesTitle = ?2 and isActive = ?3",
                         Sort.by("seasonNumber", Sort.Direction.Ascending)
                         .and("episodeNumber", Sort.Direction.Ascending),
                         "episode", seriesTitle, true);
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
        Video next = Video.<Video>find("seriesTitle = ?1 AND seasonNumber = ?2 AND episodeNumber > ?3 AND isActive = true", 
                Sort.by("episodeNumber", Sort.Direction.Ascending),
                current.seriesTitle, current.seasonNumber, current.episodeNumber).firstResult();
        
        if (next != null) return next;

        // If no more episodes in current season, try first episode of next season
        next = Video.<Video>find("seriesTitle = ?1 AND seasonNumber > ?2 AND isActive = true", 
                Sort.by("seasonNumber", Sort.Direction.Ascending).and("episodeNumber", Sort.Direction.Ascending),
                current.seriesTitle, current.seasonNumber).firstResult();
        
        return next;
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
            video.dateModified = LocalDateTime.now();
            video.persist();
            LOGGER.info("Updated title for video ID {}: '{}'", id, title);
        }
    }

    @Transactional
    public void updateMetadata(Long id, String title, String seriesTitle, String episodeTitle, Integer seasonNumber, Integer episodeNumber, String type) {
        Video video = Video.findById(id);
        if (video != null) {
            video.title = title;
            video.seriesTitle = seriesTitle;
            video.episodeTitle = episodeTitle;
            video.seasonNumber = seasonNumber;
            video.episodeNumber = episodeNumber;
            video.type = type;
            video.dateModified = LocalDateTime.now();
            video.persist();
            LOGGER.info("Updated metadata for video ID {}: title='{}', series='{}', type='{}'", id, title, seriesTitle, type);
        }
    }

    @Transactional
    public void moveEpisodes(String oldSeriesTitle, String newSeriesTitle) {
        if (oldSeriesTitle == null || newSeriesTitle == null) return;
        
        List<Video> episodes = findEpisodesForSeries(oldSeriesTitle);
        for (Video ep : episodes) {
            ep.seriesTitle = newSeriesTitle;
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
            v.persist();
        }
        LOGGER.info("Updated series title from '{}' to '{}' for {} videos", oldTitle, newTitle, videos.size());
    }

    @Transactional
    public void updateSeriesMetadata(String seriesTitle, String posterPath, String backdropPath) {
        if (seriesTitle == null) return;
        List<Video> videos = findEpisodesForSeries(seriesTitle);
        for (Video v : videos) {
            if (posterPath != null && !posterPath.isBlank()) v.posterPath = posterPath;
            if (backdropPath != null && !backdropPath.isBlank()) v.backdropPath = backdropPath;
            v.dateModified = LocalDateTime.now();
            v.persist();
        }
        LOGGER.info("Updated series metadata for '{}' ({} videos)", seriesTitle, videos.size());
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
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit, String sortBy, String sortDirection) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.Descending : Sort.Direction.Ascending;
        String sortField = sortBy != null ? sortBy : "dateAdded";
        
        List<Video> videos = Video.<Video>list("type = ?1",
                Sort.by(sortField, direction), mediaType)
                .stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
        
        // Get total count
        long totalCount = Video.count("type = ?1", mediaType);
        
        return new PaginatedVideos(videos, totalCount);
    }

    @Transactional
    public PaginatedSeries findPaginatedSeriesTitles(int page, int limit, String sortBy, String sortDirection) {
        List<Video> allEpisodes = Video.list("type = ?1", "episode");
        
        // Group by seriesTitle and find the "representative" for sorting
        Map<String, Video> seriesMap = new HashMap<>();
        for (Video v : allEpisodes) {
            if (v.seriesTitle == null) continue;
            Video existing = seriesMap.get(v.seriesTitle);
            if (existing == null) {
                seriesMap.put(v.seriesTitle, v);
            } else {
                // Update based on sort field if needed to find the "newest" or "last played" episode for the series
                if ("dateAdded".equals(sortBy)) {
                    if (v.dateAdded != null && (existing.dateAdded == null || v.dateAdded.isAfter(existing.dateAdded))) {
                        seriesMap.put(v.seriesTitle, v);
                    }
                } else if ("lastWatched".equals(sortBy)) {
                    if (v.lastWatched != null && (existing.lastWatched == null || v.lastWatched.isAfter(existing.lastWatched))) {
                        seriesMap.put(v.seriesTitle, v);
                    }
                }
            }
        }

        List<String> sortedTitles = new ArrayList<>(seriesMap.keySet());
        boolean desc = "desc".equalsIgnoreCase(sortDirection);
        
        Comparator<String> comparator;
        if ("dateAdded".equals(sortBy)) {
            comparator = Comparator.comparing(title -> seriesMap.get(title).dateAdded, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("lastWatched".equals(sortBy)) {
            comparator = Comparator.comparing(title -> seriesMap.get(title).lastWatched, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else {
            comparator = String::compareToIgnoreCase;
        }

        if (desc) comparator = comparator.reversed();
        sortedTitles.sort(comparator);

        long totalCount = sortedTitles.size();
        List<String> pagedTitles = sortedTitles.stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());

        return new PaginatedSeries(pagedTitles, totalCount);
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
