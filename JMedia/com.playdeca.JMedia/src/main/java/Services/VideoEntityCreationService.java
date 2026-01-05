package Services;

import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import Models.PendingMedia;
import Models.Season;
import Models.Settings;
import Models.Show;
import Detectors.SubtitleMatcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoEntityCreationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoEntityCreationService.class);

    @Inject
    SettingsService settingsService;

    /**
     * Converts completed PendingMedia records into actual Movie and Episode entities
     */
    @Transactional
    public void processCompletedPendingMedia() {
        List<PendingMedia> completedItems = PendingMedia.list("status = ?1 OR (status = ?2 AND confidenceScore >= ?3)", 
            PendingMedia.ProcessingStatus.COMPLETED, 
            PendingMedia.ProcessingStatus.USER_APPROVED, 
            0.5); // Changed from 0.6 to 0.5 confidence threshold
        
        LOGGER.info("Processing {} completed pending media items (50% confidence threshold)", completedItems.size());
        
        int processedCount = 0;
        int movieCount = 0;
        int episodeCount = 0;
        int retryCount = 0;
        int skipCount = 0;
        
        for (PendingMedia pending : completedItems) {
            try {
                // Check if entities already exist for this media file
                if (entitiesExistForMediaFile(pending.mediaFile)) {
                    LOGGER.debug("Entities already exist for media file: {}", pending.originalFilename);
                    continue;
                }
                
                // Process item with retry logic
                boolean success = processPendingMediaItemWithRetry(pending);
                if (success) {
                    processedCount++;
                    String finalMediaType = pending.getFinalMediaType();
                    if ("movie".equalsIgnoreCase(finalMediaType)) {
                        movieCount++;
                    } else if ("episode".equalsIgnoreCase(finalMediaType)) {
                        episodeCount++;
                    }
                } else {
                    skipCount++;
                }
                
                // Log progress every 50 items
                if ((processedCount + skipCount) % 50 == 0) {
                    LOGGER.info("Entity creation progress: {}/{} items processed ({} movies, {} episodes, {} skipped)", 
                               processedCount + skipCount, completedItems.size(), movieCount, episodeCount, skipCount);
                }
                
            } catch (Exception e) {
                LOGGER.error("Critical error processing pending media {} (ID: {}): {}", 
                            pending.originalFilename, pending.id, e.getMessage(), e);
                skipCount++;
            }
        }
        
        LOGGER.info("Entity creation completed: {} total processed ({} movies, {} episodes, {} skipped, {} retries attempted)", 
                   processedCount, movieCount, episodeCount, skipCount, retryCount);
    }
    
    /**
     * Process a single PendingMedia item with retry logic (max 2 retries, skip on 3rd failure)
     */
    private boolean processPendingMediaItemWithRetry(PendingMedia pending) {
        int retryCount = 0;
        boolean success = false;
        
        while (retryCount <= 2 && !success) {
            try {
                String finalMediaType = pending.getFinalMediaType();
                
                if ("movie".equalsIgnoreCase(finalMediaType)) {
                    createMovieFromPending(pending);
                    LOGGER.info("Created Movie entity from: {} (confidence: {})", 
                               pending.mediaFile.path, pending.confidenceScore);
                } else if ("episode".equalsIgnoreCase(finalMediaType)) {
                    createEpisodeFromPending(pending);
                    LOGGER.info("Created Episode entity from: {} S{:02d}E{:02d} (confidence: {})", 
                               pending.mediaFile.path, pending.getFinalSeason(), pending.getFinalEpisode(), pending.confidenceScore);
                } else {
                    LOGGER.warn("Unknown media type: {} for: {}", finalMediaType, pending.originalFilename);
                    return false;
                }
                
                success = true;
                
            } catch (Exception e) {
                retryCount++;
                if (retryCount <= 2) {
                    LOGGER.warn("Retry {}/3 for entity creation: {} (full exception: {})", 
                               retryCount + 1, pending.originalFilename, e.toString(), e);
                } else {
                    LOGGER.error("Skipping entity creation after 3 failures: {} (final exception: {})", 
                               pending.originalFilename, e.toString(), e);
                }
            }
        }
        
        return success;
    }

    /**
     * Checks if Movie or Episode entities already exist for a given MediaFile
     */
    private boolean entitiesExistForMediaFile(MediaFile mediaFile) {
        boolean movieExists = Movie.find("videoPath", mediaFile.path).firstResult() != null;
        boolean episodeExists = Episode.find("videoPath", mediaFile.path).firstResult() != null;
        return movieExists || episodeExists;
    }

    /**
     * Creates a Movie entity from PendingMedia
     */
    private void createMovieFromPending(PendingMedia pending) {
        Movie movie = new Movie();
        movie.videoPath = pending.mediaFile.path;
        movie.title = pending.getFinalTitle();
        movie.releaseYear = pending.getFinalYear();
        
        // Find subtitle files - get video library path from settings
        Settings settings = settingsService.getSettingsOrNull();
        String videoLibraryPath = settings != null ? settings.getVideoLibraryPath() : System.getProperty("user.home") + "/Videos";
        Path videoPath = Paths.get(videoLibraryPath, pending.mediaFile.path);
        List<Path> subtitlePaths = SubtitleMatcher.findExternalSubtitlesForVideo(videoPath);
        movie.subtitlePaths = subtitlePaths.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
        
        movie.persist();
        LOGGER.info("Created movie: " + movie.title + " (" + movie.releaseYear + ")");
    }

    /**
     * Creates an Episode entity from PendingMedia
     */
    private void createEpisodeFromPending(PendingMedia pending) {
        String showName = pending.getFinalShowName();
        Integer seasonNumber = pending.getFinalSeason();
        Integer episodeNumber = pending.getFinalEpisode();
        
        if (showName == null || showName.trim().isEmpty()) {
            LOGGER.warn("Cannot create episode without show name for: " + pending.originalFilename);
            return;
        }
        
        if (seasonNumber == null || episodeNumber == null) {
            LOGGER.warn("Cannot create episode without season/episode numbers for: " + pending.originalFilename);
            return;
        }
        
        // Create or get Show
        Show show = getOrCreateShow(showName);
        
        // Create or get Season
        Season season = getOrCreateSeason(show, seasonNumber);
        
        // Create Episode
        Episode episode = new Episode();
        episode.videoPath = pending.mediaFile.path;
        episode.title = pending.getFinalTitle();
        episode.seasonNumber = seasonNumber;
        episode.episodeNumber = episodeNumber;
        episode.season = season;
        
        // Find subtitle files - get video library path from settings
        Settings settings = settingsService.getSettingsOrNull();
        String videoLibraryPath = settings != null ? settings.getVideoLibraryPath() : System.getProperty("user.home") + "/Videos";
        Path videoPath = Paths.get(videoLibraryPath, pending.mediaFile.path);
        List<Path> subtitlePaths = SubtitleMatcher.findExternalSubtitlesForVideo(videoPath);
        episode.subtitlePaths = subtitlePaths.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
        
        episode.persist();
        LOGGER.info("Created episode: " + showName + " S" + seasonNumber + "E" + episodeNumber + " - " + episode.title);
    }

    /**
     * Gets or creates a Show entity
     */
    private Show getOrCreateShow(String name) {
        Show show = Show.find("name", name).firstResult();
        if (show == null) {
            show = new Show();
            show.name = name;
            show.persist();
            LOGGER.info("Created show: " + name);
        }
        return show;
    }

    /**
     * Gets or creates a Season entity
     */
    private Season getOrCreateSeason(Show show, int seasonNumber) {
        Season season = Season.find("show = ?1 and seasonNumber = ?2", show, seasonNumber).firstResult();
        if (season == null) {
            season = new Season();
            season.show = show;
            season.seasonNumber = seasonNumber;
            season.persist();
            LOGGER.info("Created season: " + show.name + " Season " + seasonNumber);
        }
        return season;
    }

    /**
     * Gets statistics about entity creation
     */
    public EntityCreationStats getStats() {
        EntityCreationStats stats = new EntityCreationStats();
        
        stats.totalMediaFiles = MediaFile.count("type", "video");
        stats.totalMovies = Movie.count();
        stats.totalEpisodes = Episode.count();
        stats.totalShows = Show.count();
        stats.totalSeasons = Season.count();
        
        stats.pendingMedia = PendingMedia.count();
        stats.pendingMediaCompleted = PendingMedia.count("status", PendingMedia.ProcessingStatus.COMPLETED);
        stats.pendingMediaUserApproved = PendingMedia.count("status", PendingMedia.ProcessingStatus.USER_APPROVED);
        stats.pendingMediaProcessing = PendingMedia.count("status", PendingMedia.ProcessingStatus.PROCESSING);
        stats.pendingMediaPending = PendingMedia.count("status", PendingMedia.ProcessingStatus.PENDING);
        
        long eligibleForCreation = PendingMedia.<PendingMedia>list("status = ?1 OR (status = ?2 AND confidenceScore >= ?3)", 
            PendingMedia.ProcessingStatus.COMPLETED, 
            PendingMedia.ProcessingStatus.USER_APPROVED, 
            0.5).stream() // Changed from 0.6 to 0.5 confidence threshold
            .filter(p -> !entitiesExistForMediaFile(p.mediaFile))
            .count();
        stats.eligibleForCreation = eligibleForCreation;
        
        return stats;
    }

    /**
     * Statistics class for entity creation
     */
    public static class EntityCreationStats {
        public long totalMediaFiles;
        public long totalMovies;
        public long totalEpisodes;
        public long totalShows;
        public long totalSeasons;
        
        public long pendingMedia;
        public long pendingMediaCompleted;
        public long pendingMediaUserApproved;
        public long pendingMediaProcessing;
        public long pendingMediaPending;
        public long eligibleForCreation;
        
        @Override
        public String toString() {
            return String.format(
                "Entity Stats - MediaFiles: %d, Movies: %d, Episodes: %d, Shows: %d, Seasons: %d\n" +
                "Pending Media - Total: %d, Completed: %d, User Approved: %d, Processing: %d, Pending: %d\n" +
                "Eligible for entity creation: %d",
                totalMediaFiles, totalMovies, totalEpisodes, totalShows, totalSeasons,
                pendingMedia, pendingMediaCompleted, pendingMediaUserApproved, pendingMediaProcessing, pendingMediaPending,
                eligibleForCreation
            );
        }
    }
}