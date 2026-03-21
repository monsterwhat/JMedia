package Services;

import Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UnifiedVideoEntityCreationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(UnifiedVideoEntityCreationService.class);
    
    @Inject
    VideoService videoService;
    
    // ========== UNIFIED VIDEO CREATION ==========

    /**
     * Finalizes processing by creating/updating a Video entity directly from NamingResult
     */
    @Transactional
    public Video createVideoFromNamingResult(MediaFile mediaFile, SmartNamingService.NamingResult result) {
        if (mediaFile == null || result == null) return null;
        
        // Check if video already exists
        Video video = Video.find("path", mediaFile.path).firstResult();
        if (video == null) {
            video = new Video();
            video.path = mediaFile.path;
            video.dateAdded = java.time.LocalDateTime.now();
        }
        
        // Apply discovered metadata
        video.filename = extractFilenameFromPath(mediaFile.path);
        video.type = result.mediaType;
        video.title = result.title;
        video.seriesTitle = result.showName;
        video.seasonNumber = result.season;
        video.episodeNumber = result.episode;
        video.releaseYear = result.year;
        
        // Fallback for episode title
        if ("episode".equalsIgnoreCase(video.type) && video.title == null) {
            video.title = (video.seriesTitle != null ? video.seriesTitle : "Unknown") + " - S" + (video.seasonNumber != null ? video.seasonNumber : 1) + "E" + (video.episodeNumber != null ? video.episodeNumber : 0);
        }
        
        // Technical metadata from MediaFile
        video.resolution = mediaFile.getResolutionString();
        video.displayResolution = calculateDisplayResolution(mediaFile.getResolutionString());
        video.videoCodec = mediaFile.videoCodec;
        video.audioCodec = mediaFile.audioCodec;
        video.duration = mediaFile.durationSeconds * 1000L;
        video.size = mediaFile.size;
        video.fileSize = mediaFile.size; 
        video.lastModified = mediaFile.lastModified;
        video.quality = mediaFile.getQualityIndicator();
        video.container = extractContainer(video.filename);
        video.hasSubtitles = mediaFile.hasEmbeddedSubtitles;
        video.mediaHash = mediaFile.mediaHash;
        video.releaseGroup = mediaFile.releaseGroup;
        video.source = mediaFile.source;
        video.confidenceScore = result.confidence;
        
        if (mediaFile.audioLanguage != null && !mediaFile.audioLanguage.isEmpty()) {
            video.primaryAudioLanguage = mediaFile.audioLanguage;
        }
        
        video.autoSelectSubtitles = true;
        
        return videoService.persist(video);
    }
    
    // ========== UTILITY METHODS ==========
    
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
    
    /**
     * Normalizes show name for merge detection
     * "Archer (2009)" -> "archer", "Archer2009" -> "archer"
     * Also handles season patterns: "Archer S01" -> "archer", "Archer Season 1" -> "archer"
     */
    private String normalizeForMerge(String name) {
        if (name == null || name.isEmpty()) return "";
        
        String cleaned = name.toLowerCase();
        
        // Remove season patterns: S01, Season 1, S1, etc.
        cleaned = cleaned.replaceAll("(?i)\\s*s\\d{1,2}\\b", "");  // S01, S1
        cleaned = cleaned.replaceAll("(?i)\\s*season\\s*\\d+", ""); // Season 1
        cleaned = cleaned.replaceAll("(?i)\\s*temporada\\s*\\d+", ""); // Temporada 1
        
        // Remove year patterns
        cleaned = cleaned.replaceAll("[^a-z0-9]", "").replaceAll("\\d{4}", "");
        
        return cleaned;
    }
}