package Controllers;

import Models.Video.Video;
import Services.SmartNamingService;
import Services.SettingsService;
import Services.UnifiedVideoEntityCreationService;
import Services.VideoService;
import Services.VideoImportService;
import Models.Video.MediaFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class NamingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamingController.class);
    
    @Inject
    SmartNamingService smartNamingService;
    
    @Inject
    VideoService videoService;

    @Inject
    VideoImportService videoImportService;

    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    @Inject
    SettingsService settingsService;

    /**
     * Reprocesses a video with fresh smart naming detection
     */
    public boolean reprocessVideoNaming(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            LOGGER.warn("Video not found with ID: {}", videoId);
            return false;
        }

        MediaFile mediaFile = MediaFile.find("path", video.path).firstResult();
        if (mediaFile == null) {
            LOGGER.warn("MediaFile not found for video: {}", video.path);
            return false;
        }

        Path path = Paths.get(video.path);
        String filename = path.getFileName().toString();
        // Library-relative path (same relativization the scan path uses) so folder context is preserved;
        // falls back to filename-only when the file is outside any known library root
        String relativePath = toLibraryRelativePath(path, filename);

        SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(
            mediaFile, filename, relativePath, null, null, null, null, null, null);
            
        entityCreationService.createVideoFromNamingResult(mediaFile, res);
        
        LOGGER.info("Reprocessed naming for video: {}", videoId);
        return true;
    }

    /**
     * Applies manual naming corrections to a video
     */
    public boolean applyNamingCorrections(Long videoId, 
                                     String correctedShowName, String correctedTitle,
                                     Integer correctedSeason, Integer correctedEpisode,
                                     Integer correctedYear, String correctedMediaType) {
        
        Video video = Video.findById(videoId);
        if (video == null) {
            LOGGER.warn("Video not found with ID: {}", videoId);
            return false;
        }

        if (correctedShowName != null) {
            video.seriesTitle = correctedShowName;
            video.seriesTitleManuallyEdited = true;
        }
        if (correctedTitle != null) {
            video.title = correctedTitle;
            video.titleManuallyEdited = true;
        }
        if (correctedSeason != null) {
            video.seasonNumber = correctedSeason;
            video.seasonManuallyEdited = true;
        }
        if (correctedEpisode != null) {
            video.episodeNumber = correctedEpisode;
            video.episodeManuallyEdited = true;
        }
        if (correctedYear != null) video.releaseYear = correctedYear;
        if (correctedMediaType != null) video.type = correctedMediaType;
        
        video.persist();
        
        LOGGER.info("Applied naming corrections to video {}: {} -> {} ({})", 
                   videoId, correctedShowName, correctedTitle, correctedMediaType);
        
        return true;
    }
    
    /**
     * Clears manual override flags, allowing future scans to update the fields again
     */
    public boolean clearOverrideFlags(Long videoId, boolean clearSeriesTitle, boolean clearTitle) {
        return clearOverrideFlags(videoId, clearSeriesTitle, clearTitle, false, false);
    }

    /**
     * Clears manual override flags including season/episode, allowing future scans to update the fields again
     */
    public boolean clearOverrideFlags(Long videoId, boolean clearSeriesTitle, boolean clearTitle, boolean clearSeason, boolean clearEpisode) {
        Video video = Video.findById(videoId);
        if (video == null) {
            LOGGER.warn("Video not found with ID: {}", videoId);
            return false;
        }
        
        if (clearSeriesTitle) {
            video.seriesTitleManuallyEdited = false;
        }
        if (clearTitle) {
            video.titleManuallyEdited = false;
        }
        if (clearSeason) {
            video.seasonManuallyEdited = false;
        }
        if (clearEpisode) {
            video.episodeManuallyEdited = false;
        }
        
        video.persist();
        LOGGER.info("Cleared override flags for video {}: seriesTitle={}, title={}, season={}, episode={}", 
                   videoId, clearSeriesTitle, clearTitle, clearSeason, clearEpisode);
        return true;
    }

    /**
     * Relativizes an absolute file path against the configured video library root,
     * mirroring the scan path. Falls back to filename-only when the file is outside
     * any known library root.
     */
    private String toLibraryRelativePath(Path path, String filename) {
        try {
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libPathStr != null && !libPathStr.isBlank()) {
                Path rootPath = Paths.get(libPathStr);
                if (path.startsWith(rootPath)) {
                    return rootPath.relativize(path).toString();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not relativize path {} against library root, using filename fallback: {}", path, e.getMessage());
        }
        return filename;
    }
}
