package Services;

import Models.Genre;
import Models.Video;
import Models.VideoGenre;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Service for seeding initial genre hierarchy and content
 */
@ApplicationScoped
public class GenreSeedingService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GenreSeedingService.class);
    
    @Inject
    VideoService videoService;
    
    // Predefined genre hierarchy
    private static final List<GenreDefinition> GENRE_HIERARCHY = Arrays.asList(
        // Action & Adventure
        new GenreDefinition("action", "Action", "#ff6b6b", 1, null, 
            "Fast-paced films with physical conflict, stunts, chases, explosions, and battle sequences."),
        new GenreDefinition("action-adventure", "Action Adventure", "#ff9800", 2, "action",
            "Action films that also include elements of adventure, exploration, and discovery."),
        new GenreDefinition("adventure", "Adventure", "#4caf50", 3, "action-adventure",
            "Films involving exploration, journeys, and the unknown, often in exotic locations."),
        
        // Comedy
        new GenreDefinition("comedy", "Comedy", "#8bc34a", 4, null,
            "Films designed to make the audience laugh through humor, satire, and entertainment."),
        new GenreDefinition("romantic-comedy", "Romantic Comedy", "#e91e63", 5, "comedy",
            "Comedy films focused on romantic relationships and dating situations."),
        new GenreDefinition("action-comedy", "Action Comedy", "#ff5722", 6, "comedy",
            "Comedy films with action sequences, physical comedy, and adventure elements."),
        
        // Drama & Romance
        new GenreDefinition("drama", "Drama", "#3f51b5", 7, null,
            "Serious, realistic stories portraying emotional journeys and human conflicts."),
        new GenreDefinition("romance", "Romance", "#e91e63", 8, "drama",
            "Stories centered on romantic relationships and emotional connections."),
        new GenreDefinition("historical-drama", "Historical Drama", "#795548", 9, "drama",
            "Drama set in specific historical periods, often with authentic period details."),
        
        // Thriller & Mystery
        new GenreDefinition("thriller", "Thriller", "#9c27b0", 10, null,
            "Suspenseful films creating tension, excitement, and anticipation."),
        new GenreDefinition("mystery", "Mystery", "#673ab7", 11, "thriller",
            "Puzzle-like stories involving investigations, clues, and revelations."),
        new GenreDefinition("crime", "Crime", "#f44336", 12, "mystery",
            "Stories focused on criminal activities, investigations, and justice."),
        new GenreDefinition("film-noir", "Film Noir", "#212121", 13, "crime",
            "Dark, stylized crime dramas with cynical heroes and moral ambiguity."),
        
        // Science Fiction & Fantasy
        new GenreDefinition("science-fiction", "Science Fiction", "#2196f3", 14, null,
            "Futuristic or speculative fiction with advanced technology and space exploration."),
        new GenreDefinition("fantasy", "Fantasy", "#9c27b0", 15, null,
            "Stories with magic, mythology, imaginary creatures, and supernatural elements."),
        new GenreDefinition("superhero", "Superhero", "#f44336", 16, "science-fiction",
            "Stories about characters with extraordinary powers fighting evil forces."),
        
        // Horror & Supernatural
        new GenreDefinition("horror", "Horror", "#f44336", 17, null,
            "Films designed to frighten, shock, and disgust through supernatural elements."),
        new GenreDefinition("psychological-thriller", "Psychological Thriller", "#673ab7", 18, "thriller",
            "Thrillers focusing on mental and emotional states rather than physical action."),
        new GenreDefinition("supernatural-horror", "Supernatural Horror", "#795548", 19, "horror",
            "Horror films featuring ghosts, demons, and other supernatural entities."),
        
        // Family & Animation
        new GenreDefinition("family", "Family", "#4caf50", 20, null,
            "Suitable for all ages with positive themes and moral lessons."),
        new GenreDefinition("animation", "Animation", "#ff9800", 21, "family",
            "Films created through drawn, computer-generated, or stop-motion techniques."),
        new GenreDefinition("children", "Children", "#8bc34a", 22, "family",
            "Specifically designed for young children with educational elements."),
        
        // Documentary & Biography
        new GenreDefinition("documentary", "Documentary", "#607d8b", 23, null,
            "Non-fiction films presenting factual information about real subjects."),
        new GenreDefinition("biography", "Biography", "#795548", 24, "documentary",
            "Documentaries focusing on the life story of a specific person."),
        new GenreDefinition("nature-documentary", "Nature Documentary", "#4caf50", 25, "documentary",
            "Documentaries featuring wildlife, natural environments, and ecosystems."),
        
        // Sports & Music
        new GenreDefinition("sports", "Sports", "#ff5722", 26, null,
            "Films about athletic competition, sportsmanship, and physical achievement."),
        new GenreDefinition("musical", "Musical", "#e91e63", 27, "family",
            "Films where characters express themselves through songs and dance numbers."),
        
        // War & Western
        new GenreDefinition("war", "War", "#f44336", 28, null,
            "Films depicting armed conflict and its impact on people."),
        new GenreDefinition("western", "Western", "#795548", 29, "war",
            "Stories set in the American West during the frontier period."),
        
        // Other Categories
        new GenreDefinition("foreign", "Foreign", "#9c27b0", 30, null,
            "Films produced outside the Hollywood/English-speaking mainstream."),
        new GenreDefinition("independent", "Independent", "#607d8b", 31, null,
            "Films produced outside the major studio system with limited budgets."),
        new GenreDefinition("experimental", "Experimental", "#673ab7", 32, null,
            "Avant-garde films testing cinematic boundaries and conventions.")
    );
    
    /**
     * Seed the initial genre hierarchy into the database
     */
    @Transactional
    public void seedGenreHierarchy() {
        LOGGER.info("Starting genre hierarchy seeding");
        
        int created = 0;
        int updated = 0;
        
        for (GenreDefinition def : GENRE_HIERARCHY) {
            Genre genre = Genre.find("slug", def.slug).firstResult();
            
            if (genre == null) {
                // Create new genre
                genre = new Genre();
                genre.name = def.name;
                genre.slug = def.slug;
                genre.description = def.description;
                genre.color = def.color;
                genre.icon = def.icon;
                genre.sortOrder = def.sortOrder;
                genre.isActive = true;
                
                // Set parent if specified
                if (def.parentSlug != null) {
                    Genre parent = Genre.find("slug", def.parentSlug).firstResult();
                    genre.parentGenre = parent;
                }
                
                genre.persist();
                created++;
                LOGGER.debug("Created genre: {}", def.name);
                
            } else {
                // Update existing genre
                genre.name = def.name;
                genre.description = def.description;
                genre.color = def.color;
                genre.icon = def.icon;
                genre.sortOrder = def.sortOrder;
                genre.isActive = true;
                
                // Update parent if specified
                if (def.parentSlug != null) {
                    Genre parent = Genre.find("slug", def.parentSlug).firstResult();
                    genre.parentGenre = parent;
                }
                
                genre.persist();
                updated++;
                LOGGER.debug("Updated genre: {}", def.name);
            }
        }
        
        LOGGER.info("Genre hierarchy seeding completed. Created: {}, Updated: {}", created, updated);
    }
    
    /**
     * Auto-assign genres to existing videos based on their metadata
     */
    @Transactional
    public void autoAssignGenresToVideos() {
        LOGGER.info("Starting automatic genre assignment to videos");
        
        List<Video> videos = Video.list("isActive = true and (type = 'movie' or type = 'episode')");
        int processed = 0;
        int assigned = 0;
        
        for (Video video : videos) {
            try {
                List<String> detectedGenres = detectGenresFromMetadata(video);
                
                if (!detectedGenres.isEmpty()) {
                    LOGGER.debug("No genres detected for video: {}", video.title);
                    processed++;
                    continue;
                }
                
                // Clear existing genre assignments
                VideoGenre.delete("video.id = ?1", video.id);
                
                // Assign new genres
                for (int i = 0; i < detectedGenres.size(); i++) {
                    String genreSlug = detectedGenres.get(i);
                    Genre genre = Genre.find("slug", genreSlug).firstResult();
                    
                    if (genre != null) {
                        VideoGenre videoGenre = new VideoGenre();
                        videoGenre.video = video;
                        videoGenre.genre = genre;
                        videoGenre.relevance = 1.0 - (i * 0.1); // Primary genre gets highest relevance
                        videoGenre.orderIndex = i;
                        videoGenre.persist();
                        assigned++;
                    }
                }
                
                processed++;
                
                // Log progress every 20 videos
                if (processed % 20 == 0) {
                    LOGGER.info("Processed {} of {} videos", processed, videos.size());
                }
                
            } catch (Exception e) {
                LOGGER.error("Error assigning genres to video: {}", video.id, e);
            }
        }
        
        LOGGER.info("Genre assignment completed. Processed: {}, Assignments made: {}", 
            processed, assigned);
    }
    
    /**
     * Detect genres from video metadata using heuristics
     */
    private List<String> detectGenresFromMetadata(Video video) {
        List<String> genres = new java.util.ArrayList<>();
        
        String title = video.title != null ? video.title.toLowerCase() : "";
        String description = video.description != null ? video.description.toLowerCase() : "";
        String type = video.type != null ? video.type.toLowerCase() : "";
        
        // Check for genre indicators in title and description
        for (GenreDefinition def : GENRE_HIERARCHY) {
            if (matchesGenre(def, title, description, type)) {
                genres.add(def.slug);
            }
        }
        
        // Limit to 3 genres per video
        if (genres.size() > 3) {
            genres = genres.subList(0, 3);
        }
        
        return genres;
    }
    
    /**
     * Check if video metadata matches a genre definition
     */
    private boolean matchesGenre(GenreDefinition def, String title, String description, String type) {
        String[] keywords = getGenreKeywords(def.slug);
        
        for (String keyword : keywords) {
            if (title.contains(keyword) || description.contains(keyword)) {
                return true;
            }
        }
        
        // Special handling for certain genres
        switch (def.slug) {
            case "science-fiction":
                return title.contains("sci") || title.contains("space") || 
                       title.contains("future") || description.contains("technology");
            
            case "fantasy":
                return title.contains("magic") || title.contains("dragon") || 
                       title.contains("wizard") || title.contains("kingdom");
            
            case "romance":
                return type.equals("romantic comedy") || 
                       title.contains("love") || description.contains("relationship");
            
            case "documentary":
                return type.contains("documentary") || 
                       description.contains("documentary") || description.contains("explore");
            
            case "animation":
                return type.contains("animated") || title.contains("cartoon") ||
                       description.contains("animated");
            
            case "musical":
                return title.contains("musical") || description.contains("song") ||
                       description.contains("dance");
            
            case "western":
                return title.contains("west") || description.contains("cowboy") ||
                       description.contains("frontier");
        }
        
        return false;
    }
    
    /**
     * Get keywords associated with a genre slug
     */
    private String[] getGenreKeywords(String slug) {
        switch (slug) {
            case "action": return new String[]{"action", "fight", "battle", "war", "combat"};
            case "comedy": return new String[]{"comedy", "funny", "humor", "laugh"};
            case "drama": return new String[]{"drama", "emotional", "serious", "life"};
            case "thriller": return new String[]{"thriller", "suspense", "tension", "mystery"};
            case "horror": return new String[]{"horror", "scary", "fear", "terror"};
            case "science-fiction": return new String[]{"sci-fi", "future", "space", "alien"};
            case "fantasy": return new String[]{"fantasy", "magic", "myth", "dragon"};
            case "romance": return new String[]{"romance", "love", "relationship", "dating"};
            case "family": return new String[]{"family", "kids", "children", "all ages"};
            case "documentary": return new String[]{"documentary", "real", "true", "explore"};
            case "animation": return new String[]{"animation", "cartoon", "animated", "drawn"};
            case "sports": return new String[]{"sport", "team", "game", "athlete"};
            case "musical": return new String[]{"musical", "song", "dance", "music"};
            case "war": return new String[]{"war", "battle", "soldier", "army"};
            case "western": return new String[]{"western", "cowboy", "frontier", "west"};
            case "crime": return new String[]{"crime", "murder", "detective", "police"};
            default: return new String[]{slug};
        }
    }
    
    /**
     * Get comprehensive statistics about genre seeding
     */
    public String getGenreSeedingStats() {
        try {
            long totalGenres = Genre.count("isActive = true");
            long parentGenres = Genre.count("parentGenre is null and isActive = true");
            long childGenres = Genre.count("parentGenre is not null and isActive = true");
            long videosWithGenres = Video.count(
                "isActive = true and id in (select distinct vg.video.id from VideoGenre vg)"
            );
            long totalGenreAssignments = VideoGenre.count();
            
            return String.format(
                "Genres: %d (Parent: %d, Child: %d), Videos with genres: %d, Total assignments: %d",
                totalGenres, parentGenres, childGenres, videosWithGenres, totalGenreAssignments
            );
            
        } catch (Exception e) {
            LOGGER.error("Error getting genre stats", e);
            return "Statistics unavailable";
        }
    }
    
    /**
     * Rebuild entire genre system from scratch
     */
    @Transactional
    public void rebuildGenreSystem() {
        LOGGER.info("Starting complete genre system rebuild");
        
        try {
            // Clear existing data
            VideoGenre.deleteAll();
            Genre.deleteAll();
            
            // Re-seed hierarchy
            seedGenreHierarchy();
            
            // Re-assign genres to videos
            autoAssignGenresToVideos();
            
            LOGGER.info("Genre system rebuild completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error rebuilding genre system", e);
            throw new RuntimeException("Failed to rebuild genre system", e);
        }
    }
    
    /**
     * Definition class for genre structure
     */
    private static class GenreDefinition {
        final String slug;
        final String name;
        final String color;
        final int sortOrder;
        final String parentSlug;
        final String description;
        final String icon;
        
        GenreDefinition(String slug, String name, String color, int sortOrder, 
                     String parentSlug, String description) {
            this.slug = slug;
            this.name = name;
            this.color = color;
            this.sortOrder = sortOrder;
            this.parentSlug = parentSlug;
            this.description = description;
            this.icon = "pi pi-tag"; // Default icon
        }
    }
}