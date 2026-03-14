package Services;

import Models.*;
import Models.PendingMedia.ProcessingStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UnifiedVideoEntityCreationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(UnifiedVideoEntityCreationService.class);

    @Inject
    VideoService videoService;
    
    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;
    
    @Inject
    UserInteractionService userInteractionService;
    
    @Inject
    ThumbnailService thumbnailService;
    
    @Inject
    VideoMetadataService videoMetadataService;
    
    // ========== UNIFIED VIDEO CREATION ==========
    
    /**
     * Finalizes processing by creating/updating a Video entity from analyzed PendingMedia
     */
    @Transactional
    public Video createVideoFromPendingMedia(Long pendingId) {
        PendingMedia pending = PendingMedia.findById(pendingId);
        if (pending == null || pending.mediaFile == null) return null;
        
        // Check if video already exists
        Video video = Video.find("path", pending.mediaFile.path).firstResult();
        if (video == null) {
            video = new Video();
            video.path = pending.mediaFile.path;
            video.dateAdded = java.time.LocalDateTime.now();
        }
        
        // Apply discovered metadata
        video.filename = pending.originalFilename;
        video.type = pending.getFinalMediaType();
        video.title = pending.getFinalTitle();
        video.seriesTitle = pending.getFinalShowName();
        video.seasonNumber = pending.getFinalSeason();
        video.episodeNumber = pending.getFinalEpisode();
        video.releaseYear = pending.getFinalYear();
        
        // Fallback for episode title
        if ("episode".equalsIgnoreCase(video.type) && video.title == null) {
            video.title = video.seriesTitle + " - S" + video.seasonNumber + "E" + video.episodeNumber;
        }
        
        // Technical metadata from MediaFile
        video.resolution = pending.mediaFile.getResolutionString();
        video.displayResolution = calculateDisplayResolution(pending.mediaFile.getResolutionString());
        video.videoCodec = pending.mediaFile.videoCodec;
        video.audioCodec = pending.mediaFile.audioCodec;
        video.duration = pending.mediaFile.durationSeconds * 1000L;
        video.size = pending.mediaFile.size;
        video.fileSize = pending.mediaFile.size; // Set legacy field
        video.lastModified = pending.mediaFile.lastModified;
        video.quality = pending.mediaFile.getQualityIndicator();
        video.container = extractContainer(video.filename);
        video.hasSubtitles = pending.mediaFile.hasEmbeddedSubtitles;
        video.releaseGroup = pending.mediaFile.releaseGroup;
        video.source = pending.mediaFile.source;
        video.confidenceScore = pending.confidenceScore;
        
        // Discover and associate subtitle tracks if not already present
        if (video.subtitleTracks == null || video.subtitleTracks.isEmpty()) {
            List<SubtitleTrack> subtitleTracks = subtitleMatcher.discoverSubtitleTracks(
                    java.nio.file.Path.of(video.path), video);
            if (!subtitleTracks.isEmpty()) {
                video.subtitleTracks = subtitleTracks;
            }
        }
        
        // Set default audio language
        if (pending.mediaFile.audioLanguage != null && !pending.mediaFile.audioLanguage.isEmpty()) {
            video.primaryAudioLanguage = pending.mediaFile.audioLanguage;
        }
        
        video.autoSelectSubtitles = true;
        
        Video persisted = videoService.persist(video);
        
        // Removed automatic IntroDB enrichment on creation to avoid rate limits on initial scan.
        // IntroDB data will now be fetched on-demand during playback or via manual "Deep Reload".
        
        // Generate thumbnail
        try {
            thumbnailService.generateThumbnail(persisted.id, persisted.path);
        } catch (Exception e) {
            LOG.error("Failed to generate thumbnail for finalized video: " + e.getMessage());
        }
        
        return persisted;
    }
    
    @jakarta.enterprise.context.control.ActivateRequestContext
    public void importExistingVideos() {
        LOG.info("Starting Phase 3: Finalizing video entities from pending media...");
        
        // --- DIAGNOSTIC LOGGING ---
        try {
            long total = PendingMedia.count();
            long pending = PendingMedia.count("status", PendingMedia.ProcessingStatus.PENDING);
            long processing = PendingMedia.count("status", PendingMedia.ProcessingStatus.PROCESSING);
            long completed = PendingMedia.count("status", PendingMedia.ProcessingStatus.COMPLETED);
            long approved = PendingMedia.count("status", PendingMedia.ProcessingStatus.USER_APPROVED);
            long failed = PendingMedia.count("status", PendingMedia.ProcessingStatus.FAILED);
            LOG.info("PendingMedia Statistics: TOTAL: {}, PENDING: {}, PROCESSING: {}, COMPLETED: {}, APPROVED: {}, FAILED: {}", 
                    total, pending, processing, completed, approved, failed);
        } catch (Exception e) {
            LOG.warn("Failed to generate diagnostic statistics: " + e.getMessage());
        }
        // ---------------------------

        // Find all COMPLETED or USER_APPROVED pending media
        // IMPORTANT: We do NOT use @Transactional on this method anymore.
        // Each loop iteration will handle its own transaction.
        List<PendingMedia> readyToFinalize = PendingMedia.find("status = :status1 OR status = :status2", 
                io.quarkus.panache.common.Parameters.with("status1", PendingMedia.ProcessingStatus.COMPLETED)
                                         .and("status2", PendingMedia.ProcessingStatus.USER_APPROVED)).list();
        
        LOG.info("Found {} pending media items ready to finalize", readyToFinalize.size());
        
        int count = 0;
        for (PendingMedia pending : readyToFinalize) {
            try {
                // This method is @Transactional and will handle its own commit
                Video v = createVideoFromPendingMedia(pending.id);
                if (v != null) {
                    count++;
                    if (count % 50 == 0) {
                        LOG.info("Progress: Finalized {} video entities...", count);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error finalizing video for " + pending.originalFilename + ": " + e.getMessage());
            }
        }
        
        LOG.info("Phase 3 completed. Finalized {} video entities.", count);
    }

    /**
     * Legacy method, kept for compatibility but should use createVideoFromPendingMedia
     */
    @Transactional
    public Video createVideoFromMediaFile(Models.MediaFile mediaFile) {
        Video video = new Video();
        video.path = mediaFile.path;
        video.filename = extractFilenameFromPath(mediaFile.path);
        video.type = detectVideoType(mediaFile);
        video.dateAdded = java.time.LocalDateTime.now();
        
        video.resolution = mediaFile.getResolutionString();
        video.displayResolution = calculateDisplayResolution(mediaFile.getResolutionString());
        video.videoCodec = mediaFile.videoCodec;
        video.audioCodec = mediaFile.audioCodec;
        video.duration = mediaFile.durationSeconds * 1000L;
        video.size = mediaFile.size;
        video.lastModified = mediaFile.lastModified;
        video.quality = mediaFile.getQualityIndicator();
        video.container = extractContainer(video.filename);
        video.hasSubtitles = mediaFile.hasEmbeddedSubtitles;
        video.releaseGroup = mediaFile.releaseGroup;
        video.source = mediaFile.source;
        
        return videoService.persist(video);
    }
    
    // ========== UTILITY METHODS ==========
    
    private String detectVideoType(Models.MediaFile mediaFile) {
        String filename = extractFilenameFromPath(mediaFile.path);
        String pathLower = mediaFile.path.toLowerCase().replace('\\', '/');
        
        // Priority 1: Strong folder hints
        if (pathLower.contains("/tv shows/") || pathLower.contains("/tvseries/") || 
            pathLower.contains("/tv/") || pathLower.contains("/season") || 
            pathLower.contains("/series") || pathLower.contains("/libro") ||
            pathLower.contains("/book") || pathLower.contains("/temporada")) {
            return "episode";
        }
        
        if (filename.toLowerCase().contains("movie") || 
            pathLower.contains("/movies/") ||
            mediaFile.isTypicalMovieDuration()) {
            
            // Re-check for episode patterns in filename (stronger than generic "movie" path)
            if (filename.matches(".*[sS]\\d+[eE]\\d+.*") || filename.matches(".*\\d+[x×]\\d+.*")) {
                return "episode";
            }
            return "movie";
        } else if (mediaFile.isTypicalEpisodeDuration()) {
            return "episode";
        }
        return "movie";
    }
    
    private String extractFilenameFromPath(String path) {
        if (path == null) return null;
        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);
        return lastSeparator >= 0 ? path.substring(lastSeparator + 1) : path;
    }
    
    private String calculateDisplayResolution(String resolution) {
        if (resolution == null) return null;
        String[] parts = resolution.split("x");
        if (parts.length != 2) return resolution;
        
        try {
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
    
    private String extractContainer(String filename) {
        if (filename == null) return "mp4";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "mp4";
    }
}
