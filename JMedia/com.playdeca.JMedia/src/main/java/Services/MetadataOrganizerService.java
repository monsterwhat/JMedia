package Services;

import Models.Show;
import Models.Season;
import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class MetadataOrganizerService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataOrganizerService.class);
    
    @Inject
    VideoImportService videoImportService;
    
    public record OrganizedPath(String virtualPath, String displayName) {}
    
    /**
     * Creates a virtual organization structure based on metadata, not physical folders
     */
    @Transactional
    public void organizeVirtualStructure() {
        LOGGER.info("Starting virtual organization based on metadata...");
        
        // Get all shows from database
        List<Show> shows = Show.listAll();
        
        for (Show show : shows) {
            organizeShow(show);
        }
        
        LOGGER.info("Virtual organization completed for {} shows", shows.size());
    }
    
    private void organizeShow(Show show) {
        LOGGER.info("Organizing show: {}", show.name);
        
        // Get all seasons for this show
        List<Season> seasons = Season.list("show", show);
        
        for (Season season : seasons) {
            organizeSeason(show, season);
        }
    }
    
    private void organizeSeason(Show show, Season season) {
        LOGGER.info("Organizing season {} for show {}", season.seasonNumber, show.name);
        
        // Get all episodes for this season
        List<Episode> episodes = Episode.list("season", season);
        
        for (Episode episode : episodes) {
            organizeEpisode(show, season, episode);
        }
    }
    
    private void organizeEpisode(Show show, Season season, Episode episode) {
        // Create virtual path structure
        String virtualPath = String.format("/shows/%s/season/%d/episode/%d", 
            normalizeShowName(show.name), 
            season.seasonNumber, 
            episode.episodeNumber);
        
        String displayName = String.format("S%02dE%02d - %s", 
            season.seasonNumber, 
            episode.episodeNumber, 
            episode.title != null ? episode.title : "Episode " + episode.episodeNumber);
        
        LOGGER.debug("Virtual path: {} -> {}", virtualPath, displayName);
    }
    
    /**
     * Creates a smart, clean show name for display and organization
     */
    public static String normalizeShowName(String originalName) {
        if (originalName == null || originalName.trim().isEmpty()) {
            return "Unknown Show";
        }
        
        // Use smart detection to clean the name
        Optional<String> detected = SmartShowNameDetector.detectFromFolder(originalName);
        
        if (detected.isPresent()) {
            return detected.get();
        }
        
        // Fallback to basic cleaning
        return SmartShowNameDetector.cleanShowNameStatic(originalName);
    }
    
    /**
     * Gets a clean display name for shows list
     */
    public static String getDisplayName(String showName) {
        String normalized = normalizeShowName(showName);
        
        // Capitalize properly (Title Case)
        return toTitleCase(normalized);
    }
    
    /**
     * Creates a virtual folder structure for UI navigation
     */
    public List<OrganizedPath> getVirtualShowStructure() {
        LOGGER.info("DEBUG: getVirtualShowStructure called");
        
        // Debug: Check database connection and basic query
        try {
            long showCount = Show.count();
            LOGGER.info("DEBUG: Total show count in database: {}", showCount);
            
            if (showCount == 0) {
                LOGGER.warn("DEBUG: No shows found in database! Check if video import has been run.");
                // Let's also check if there are any video files at all
                LOGGER.info("DEBUG: Checking if there are any MediaFile records...");
                long mediaFileCount = MediaFile.count();
                LOGGER.info("DEBUG: Total MediaFile count: {}", mediaFileCount);
                
                if (mediaFileCount > 0) {
                    LOGGER.info("DEBUG: MediaFiles exist but no Shows - this suggests import may not have created show metadata properly");
                }
            }
        } catch (Exception e) {
            LOGGER.error("DEBUG: Error accessing database: {}", e.getMessage(), e);
        }
        
        List<OrganizedPath> structure = new ArrayList<>();
        
        List<Show> shows = Show.listAll();
        LOGGER.info("DEBUG: Found {} shows in database", shows.size());
        
        for (Show show : shows) {
            LOGGER.info("DEBUG: Processing show - ID: {}, Name: '{}'", show.id, show.name);
            String displayName = getDisplayName(show.name);
            String virtualPath = "/shows/" + normalizeShowName(show.name);
            OrganizedPath organizedPath = new OrganizedPath(virtualPath, displayName);
            structure.add(organizedPath);
            LOGGER.info("DEBUG: Added organized path - VirtualPath: '{}', DisplayName: '{}'", virtualPath, displayName);
        }
        
        LOGGER.info("DEBUG: Returning {} organized show paths", structure.size());
        return structure;
    }
    
    public List<OrganizedPath> getVirtualSeasonStructure(String showName) {
        LOGGER.info("DEBUG: getVirtualSeasonStructure called with showName: '{}'", showName);
        List<OrganizedPath> structure = new ArrayList<>();
        
        Show show = Show.find("name", showName).firstResult();
        LOGGER.info("DEBUG: Found show with exact name: {}", show != null ? "YES (ID: " + show.id + ")" : "NO");
        
        if (show == null) {
            LOGGER.warn("DEBUG: Show not found with name '{}', returning empty structure", showName);
            return structure;
        }
        
        List<Season> seasons = Season.list("show", show);
        LOGGER.info("DEBUG: Found {} seasons for show ID: {}", seasons.size(), show.id);
        
        for (Season season : seasons) {
            String displayName = "Season " + season.seasonNumber;
            String virtualPath = "/shows/" + normalizeShowName(showName) + "/season/" + season.seasonNumber;
            structure.add(new OrganizedPath(virtualPath, displayName));
            LOGGER.info("DEBUG: Added season {} - DisplayName: '{}', VirtualPath: '{}'", season.seasonNumber, displayName, virtualPath);
        }
        
        LOGGER.info("DEBUG: Returning {} season structures", structure.size());
        return structure;
    }
    
    public List<OrganizedPath> getVirtualEpisodeStructure(String showName, int seasonNumber) {
        List<OrganizedPath> structure = new ArrayList<>();
        
        Show show = Show.find("name", showName).firstResult();
        if (show == null) {
            return structure;
        }
        
        Season season = Season.find("show = ?1 and seasonNumber = ?2", show, seasonNumber).firstResult();
        if (season == null) {
            return structure;
        }
        
        List<Episode> episodes = Episode.list("season", season);
        
        for (Episode episode : episodes) {
            String displayName = String.format("S%02dE%02d - %s", 
                seasonNumber, episode.episodeNumber, 
                episode.title != null ? episode.title : "Episode " + episode.episodeNumber);
            String virtualPath = "/shows/" + normalizeShowName(showName) + "/season/" + seasonNumber + "/episode/" + episode.episodeNumber;
            structure.add(new OrganizedPath(virtualPath, displayName));
        }
        
        return structure;
    }
    
    /**
     * Converts string to proper Title Case
     */
    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        
        return result.toString();
    }
}