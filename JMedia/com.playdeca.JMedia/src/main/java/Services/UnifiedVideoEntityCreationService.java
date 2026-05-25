package Services;

import Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
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
     * @param preserveMetadata if true, keep existing metadata (description, imdb, overview, etc.) - used during full scans
     */
    @Transactional
    public Video createVideoFromNamingResult(MediaFile mediaFile, SmartNamingService.NamingResult result) {
        return createVideoFromNamingResult(mediaFile, result, false);
    }
    
    @Transactional
    public Video createVideoFromNamingResult(MediaFile mediaFile, SmartNamingService.NamingResult result, boolean preserveMetadata) {
        if (mediaFile == null || result == null) return null;
        
        // Check if video already exists
        Video video = Video.find("path", mediaFile.path).firstResult();
        if (video == null) {
            video = new Video();
            video.path = mediaFile.path;
            video.dateAdded = java.time.LocalDateTime.now();
        }
        
        // Preserve metadata fields if requested (full scan) - backup before overwriting
        String preservedDescription = preserveMetadata ? video.description : null;
        String preservedTagline = preserveMetadata ? video.tagline : null;
        String preservedOverview = preserveMetadata ? video.overview : null;
        Double preservedImdbRating = preserveMetadata ? video.imdbRating : null;
        Integer preservedMetacritic = preserveMetadata ? video.metacriticRating : null;
        String preservedMpaa = preserveMetadata ? video.mpaaRating : null;
        List<String> preservedGenres = preserveMetadata && video.genres != null ? new ArrayList<>(video.genres) : null;
        List<String> preservedCast = preserveMetadata && video.cast != null ? new ArrayList<>(video.cast) : null;
        List<String> preservedDirectors = preserveMetadata && video.directors != null ? new ArrayList<>(video.directors) : null;
        List<String> preservedWriters = preserveMetadata && video.writers != null ? new ArrayList<>(video.writers) : null;
        
        // Preserve external IDs and artwork
        String preservedImdbId = preserveMetadata ? video.imdbId : null;
        String preservedTmdbId = preserveMetadata ? video.tmdbId : null;
        Double preservedTmdbRating = preserveMetadata ? video.tmdbRating : null;
        String preservedThumbnailPath = preserveMetadata ? video.thumbnailPath : null;
        String preservedPosterPath = preserveMetadata ? video.posterPath : null;
        
        // Apply discovered metadata
        video.filename = extractFilenameFromPath(mediaFile.path);
        video.type = result.mediaType;

        // Only overwrite title/seriesTitle if NOT manually edited
        if (!video.titleManuallyEdited) {
            video.title = result.title;
        }
        if (!video.seriesTitleManuallyEdited) {
            video.seriesTitle = result.showName;
        }

        video.seasonNumber = result.season;
        video.seasonName = result.seasonName;
        video.episodeNumber = result.episode;
        video.folder = result.folder;

        // Set episodeTitle for episodes
        if ("episode".equalsIgnoreCase(result.mediaType) && result.title != null) {
            video.episodeTitle = result.title;
        }

        // FALLBACK: Extract season number from seasonName if season is null
        if (video.seasonNumber == null && video.seasonName != null && !video.seasonName.isEmpty()) {
            Integer seasonFromName = extractSeasonFromSeasonName(video.seasonName);
            if (seasonFromName != null) {
                video.seasonNumber = seasonFromName;
            }
        }

        video.releaseYear = result.year;
        
        // Fallback for episode title (only if not manually edited)
        if ("episode".equalsIgnoreCase(video.type) && video.title == null && !video.titleManuallyEdited) {
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
        
        // Restore preserved metadata (full scan mode)
        if (preserveMetadata) {
            if (preservedDescription != null) video.description = preservedDescription;
            if (preservedTagline != null) video.tagline = preservedTagline;
            if (preservedOverview != null) video.overview = preservedOverview;
            if (preservedImdbRating != null && preservedImdbRating > 0) video.imdbRating = preservedImdbRating;
            if (preservedMetacritic != null && preservedMetacritic > 0) video.metacriticRating = preservedMetacritic;
            if (preservedMpaa != null) video.mpaaRating = preservedMpaa;
            if (preservedGenres != null && !preservedGenres.isEmpty()) video.genres = preservedGenres;
            if (preservedCast != null && !preservedCast.isEmpty()) video.cast = preservedCast;
            if (preservedDirectors != null && !preservedDirectors.isEmpty()) video.directors = preservedDirectors;
            if (preservedWriters != null && !preservedWriters.isEmpty()) video.writers = preservedWriters;
            
            // Preserve external IDs and artwork
            if (preservedImdbId != null) video.imdbId = preservedImdbId;
            if (preservedTmdbId != null) video.tmdbId = preservedTmdbId;
            if (preservedTmdbRating != null && preservedTmdbRating > 0) video.tmdbRating = preservedTmdbRating;
            if (preservedThumbnailPath != null) video.thumbnailPath = preservedThumbnailPath;
            if (preservedPosterPath != null) video.posterPath = preservedPosterPath;
        }
        
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
     * Extracts season number from seasonName as fallback
     * "Libro 1 Agua" -> 1, "Season 2" -> 2, "Book 3 - Change" -> 3
     */
    private Integer extractSeasonFromSeasonName(String seasonName) {
        if (seasonName == null || seasonName.isEmpty()) return null;
        
        // Try to match patterns like "Libro 1", "Season 2", "Book 3", "S01", etc.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)(?:season|libro|temporada|book|volume|chapter|episode|arco|saga|tome|series|s)\\s*[-_. ]?(\\d{1,3})");
        java.util.regex.Matcher matcher = pattern.matcher(seasonName);
        
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // Try bare number at start (e.g., "1 - Title")
        java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("^(\\d{1,3})\\s*[-–—]");
        java.util.regex.Matcher numMatcher = numPattern.matcher(seasonName);
        if (numMatcher.find()) {
            try {
                return Integer.parseInt(numMatcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
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