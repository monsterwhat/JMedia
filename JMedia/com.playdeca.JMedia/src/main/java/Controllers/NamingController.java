package Controllers;

import Models.Video;
import Services.SmartNamingService;
import Services.UnifiedVideoEntityCreationService;
import Services.VideoService;
import Services.VideoImportService;
import Models.MediaFile;
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
        // Fallback relative path
        String relativePath = filename; 

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

        if (correctedShowName != null) video.seriesTitle = correctedShowName;
        if (correctedTitle != null) video.title = correctedTitle;
        if (correctedSeason != null) video.seasonNumber = correctedSeason;
        if (correctedEpisode != null) video.episodeNumber = correctedEpisode;
        if (correctedYear != null) video.releaseYear = correctedYear;
        if (correctedMediaType != null) video.type = correctedMediaType;
        
        video.persist();
        
        LOGGER.info("Applied naming corrections to video {}: {} -> {} ({})", 
                   videoId, correctedShowName, correctedTitle, correctedMediaType);
        
        return true;
    }
}
