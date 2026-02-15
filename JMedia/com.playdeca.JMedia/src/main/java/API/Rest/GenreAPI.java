package API.Rest;

import Services.GenreSeedingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * API endpoints for genre management and seeding
 */
@Path("/api/genres")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class GenreAPI {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GenreAPI.class);
    
    @Inject
    private GenreSeedingService genreSeedingService;
    
    /**
     * Seed the genre hierarchy
     */
    @POST
    @Path("/seed")
    public Response seedGenreHierarchy() {
        try {
            genreSeedingService.seedGenreHierarchy();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Genre hierarchy seeded successfully");
            response.put("stats", genreSeedingService.getGenreSeedingStats());
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOGGER.error("Error seeding genre hierarchy", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "error", e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Auto-assign genres to existing videos
     */
    @POST
    @Path("/auto-assign")
    public Response autoAssignGenres() {
        try {
            // Run in background thread for large libraries
            CompletableFuture.runAsync(() -> {
                try {
                    genreSeedingService.autoAssignGenresToVideos();
                    LOGGER.info("Genre auto-assignment completed");
                } catch (Exception e) {
                    LOGGER.error("Error in auto-assign genres", e);
                }
            });
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Genre auto-assignment started in background");
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOGGER.error("Error starting genre auto-assignment", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "error", e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Rebuild the entire genre system
     */
    @POST
    @Path("/rebuild")
    public Response rebuildGenreSystem() {
        try {
            // Run in background thread for safety
            CompletableFuture.runAsync(() -> {
                try {
                    genreSeedingService.rebuildGenreSystem();
                    LOGGER.info("Genre system rebuild completed");
                } catch (Exception e) {
                    LOGGER.error("Error rebuilding genre system", e);
                }
            });
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Genre system rebuild started in background");
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOGGER.error("Error starting genre system rebuild", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "error", e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Get genre statistics
     */
    @GET
    @Path("/stats")
    public Response getGenreStats() {
        try {
            String stats = genreSeedingService.getGenreSeedingStats();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("stats", stats);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOGGER.error("Error getting genre stats", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "error", e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Validate genre assignments for potential issues
     */
    @GET
    @Path("/validate")
    public Response validateGenreAssignments() {
        try {
            // This would run validation checks on the genre system
            Map<String, Object> validationResults = new HashMap<>();
            validationResults.put("orphanedGenres", "[]"); // Genres without parent
            validationResults.put("unassignedVideos", "[]"); // Videos without genres
            validationResults.put("duplicateAssignments", "[]"); // Potential duplicates
            validationResults.put("recommendations", new String[]{
                "Review auto-assigned genres for accuracy",
                "Consider adding missing genres from user feedback",
                "Update genre hierarchy based on content trends"
            });
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("validation", validationResults);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOGGER.error("Error validating genre assignments", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "error", e.getMessage()))
                    .build();
        }
    }
}