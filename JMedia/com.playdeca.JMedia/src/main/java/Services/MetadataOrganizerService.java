package Services;

import Models.Video;
import Models.MediaFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class MetadataOrganizerService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataOrganizerService.class);
    
    @Inject
    VideoImportService videoImportService;
    
    public record OrganizedPath(String virtualPath, String displayName) {}
    
    /**
     * Creates a virtual organization structure based on Video entities, not physical folders
     */
    @Transactional
    public void organizeVirtualStructure() {
        LOGGER.info("Starting virtual organization based on Video entities...");
        
        // Get all unique show names from episodes
        List<String> showNames = Video.<Video>list("type", "episode")
                .stream()
                .map(v -> v.seriesTitle)
                .filter(title -> title != null && !title.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        for (String showName : showNames) {
            organizeShow(showName);
        }
        
        LOGGER.info("Virtual organization completed for {} shows", showNames.size());
    }
    
    private void organizeShow(String showName) {
        LOGGER.info("Organizing show: {}", showName);
        
        // Get all unique seasons for this show
        List<Integer> seasons = Video.<Video>list("type = ?1 and seriesTitle = ?2", "episode", showName)
                .stream()
                .map(v -> v.seasonNumber)
                .filter(season -> season != null)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        for (Integer seasonNumber : seasons) {
            organizeSeason(showName, seasonNumber);
        }
    }
    
    private void organizeSeason(String showName, Integer seasonNumber) {
        LOGGER.info("Organizing season {} for show {}", seasonNumber, showName);
        
        // Get all episodes for this season
        List<Video> episodes = Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", 
                "episode", showName, seasonNumber);
        
        for (Video episode : episodes) {
            organizeEpisode(showName, seasonNumber, episode);
        }
    }
    
    private void organizeEpisode(String showName, Integer seasonNumber, Video episode) {
        // Create virtual path structure
        String virtualPath = String.format("/shows/%s/season/%d/episode/%d", 
            normalizeShowName(showName), 
            seasonNumber, 
            episode.episodeNumber);
        
        String displayName = String.format("S%02dE%02d - %s", 
            seasonNumber, 
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
        
        // Simple cleaning of show name
        String cleaned = originalName.replaceAll("\\b(19|20)\\d{2}\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\b(720p|1080p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv)\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\b(season|s|episode|e|part)\\s*[\\d\\-]+", "");
        cleaned = cleaned.replaceAll("[._\\-\\[\\]\\(\\)]+", " ").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        
        return toTitleCase(cleaned.trim());
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
            long episodeCount = Video.count("type", "episode");
            LOGGER.info("DEBUG: Total episode count in database: {}", episodeCount);
            
            if (episodeCount == 0) {
                LOGGER.warn("DEBUG: No episodes found in database! Check if video import has been run.");
                // Let's also check if there are any video files at all
                LOGGER.info("DEBUG: Checking if there are any MediaFile records...");
                long mediaFileCount = MediaFile.count();
                LOGGER.info("DEBUG: Total MediaFile count: {}", mediaFileCount);
                
                if (mediaFileCount > 0) {
                    LOGGER.info("DEBUG: MediaFiles exist but no episodes - this suggests import may not have created video metadata properly");
                }
            }
        } catch (Exception e) {
            LOGGER.error("DEBUG: Error accessing database: {}", e.getMessage(), e);
        }
        
        List<OrganizedPath> structure = new ArrayList<>();
        
        // Get unique show names from episodes
        List<String> showNames = Video.<Video>list("type", "episode")
                .stream()
                .map(v -> v.seriesTitle)
                .filter(title -> title != null && !title.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        LOGGER.info("DEBUG: Found {} unique show names in database", showNames.size());
        
        for (String showName : showNames) {
            LOGGER.info("DEBUG: Processing show - Name: '{}'", showName);
            String displayName = getDisplayName(showName);
            String virtualPath = "/shows/" + normalizeShowName(showName);
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
        
        // Get all unique seasons for this show
        List<Integer> seasons = Video.<Video>list("type = ?1 and seriesTitle = ?2", "episode", showName)
                .stream()
                .map(v -> v.seasonNumber)
                .filter(season -> season != null)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        LOGGER.info("DEBUG: Found {} seasons for show '{}'", seasons.size(), showName);
        
        for (Integer seasonNumber : seasons) {
            String displayName = "Season " + seasonNumber;
            String virtualPath = "/shows/" + normalizeShowName(showName) + "/season/" + seasonNumber;
            structure.add(new OrganizedPath(virtualPath, displayName));
            LOGGER.info("DEBUG: Added season {} - DisplayName: '{}', VirtualPath: '{}'", seasonNumber, displayName, virtualPath);
        }
        
        LOGGER.info("DEBUG: Returning {} season structures", structure.size());
        return structure;
    }
    
    public List<OrganizedPath> getVirtualEpisodeStructure(String showName, int seasonNumber) {
        List<OrganizedPath> structure = new ArrayList<>();
        
        // Get all episodes for this show and season
        List<Video> episodes = Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", 
                "episode", showName, seasonNumber);
        
        if (episodes.isEmpty()) {
            LOGGER.info("DEBUG: No episodes found for show '{}', season {}", showName, seasonNumber);
            return structure;
        }
        
        for (Video episode : episodes) {
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