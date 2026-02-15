package Services;

import Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class UnifiedVideoEntityCreationService {
    
    @Inject
    VideoService videoService;
    
    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;
    
    @Inject
    UserInteractionService userInteractionService;
    
    // ========== UNIFIED VIDEO CREATION ==========
    
    @Transactional
    public Video createVideoFromMediaFile(Models.MediaFile mediaFile) {
        Video video = new Video();
        
        // Core identification
        video.path = mediaFile.path;
        video.filename = extractFilenameFromPath(mediaFile.path);
        video.type = detectVideoType(mediaFile);
        video.dateAdded = java.time.LocalDateTime.now();
        
        // Technical metadata from MediaFile
        video.resolution = mediaFile.getResolutionString();
        video.displayResolution = calculateDisplayResolution(mediaFile.getResolutionString());
        video.videoCodec = mediaFile.videoCodec;
        video.audioCodec = mediaFile.audioCodec;
        video.duration = mediaFile.durationSeconds * 1000L;
        video.size = mediaFile.size;
        video.lastModified = mediaFile.lastModified;
        video.quality = mediaFile.getQualityIndicator();
        video.container = extractContainer(extractFilenameFromPath(mediaFile.path));
        
        // Discover and associate subtitle tracks
        List<SubtitleTrack> subtitleTracks = subtitleMatcher.discoverSubtitleTracks(
                java.nio.file.Path.of(mediaFile.path), video);
        if (!subtitleTracks.isEmpty()) {
            video.subtitleTracks = subtitleTracks;
        }
        
        // Set default audio language from MediaFile
        if (mediaFile.audioLanguage != null && !mediaFile.audioLanguage.isEmpty()) {
            video.primaryAudioLanguage = mediaFile.audioLanguage;
        }
        
        // Auto-select subtitles by default
        video.autoSelectSubtitles = true;
        
        return videoService.persist(video);
    }
    
    @Transactional
    public void createVideosFromMediaFiles(List<Models.MediaFile> mediaFiles) {
        for (Models.MediaFile mediaFile : mediaFiles) {
            try {
                createVideoFromMediaFile(mediaFile);
            } catch (Exception e) {
                System.err.println("Error creating video from media file: " + e.getMessage());
            }
        }
    }
    
    @Transactional
    public void importExistingVideos() {
        // Import logic for existing media files that don't have videos yet
        // This would scan for MediaFile entities and create Video entities
        // Implementation depends on your current MediaFile structure
    }
    
    // ========== UTILITY METHODS ==========
    
    private String detectVideoType(Models.MediaFile mediaFile) {
        // Simple type detection based on naming and metadata
        String filename = extractFilenameFromPath(mediaFile.path);
        if (filename.toLowerCase().contains("movie") || 
            mediaFile.path.toLowerCase().contains("movies") ||
            mediaFile.isTypicalMovieDuration()) {
            return "movie";
        } else if (mediaFile.isTypicalEpisodeDuration()) {
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
        return mediaFile.getQualityIndicator();
    }
    
    private String extractContainer(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "mp4"; // Default fallback
    }
}