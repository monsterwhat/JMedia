package API.Rest;

import API.ApiResponse;
import Controllers.SetupController;
import Models.DTOs.ImportInstallationStatus;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("/api/setup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupApi {

    @Inject
    private SetupController setupController;

    // -----------------------------
    // CHECK SETUP STATUS
    // -----------------------------
    @GET
    @Path("/status")
    @Transactional
    public Response getSetupStatus() {
        boolean isFirstTime = setupController.isFirstTimeSetup();
        Map<String, Object> response = new HashMap<>();
        response.put("isFirstTimeSetup", isFirstTime);
        return Response.ok(ApiResponse.success(response)).build();
    }



    // -----------------------------
    // VALIDATE PATHS
    // -----------------------------
    @POST
    @Path("/validate-paths")
    @Transactional
    public Response validatePaths(Map<String, String> paths) {
        Map<String, Boolean> validation = new HashMap<>();
        
        String musicPath = paths.get("musicLibraryPath");
        String videoPath = paths.get("videoLibraryPath");
        
        if (musicPath != null && !musicPath.isBlank()) {
            validation.put("musicLibraryValid", setupController.validateMusicLibraryPath(musicPath));
        }
        
        if (videoPath != null && !videoPath.isBlank()) {
            validation.put("videoLibraryValid", setupController.validateVideoLibraryPath(videoPath));
        }
        
        return Response.ok(ApiResponse.success(validation)).build();
    }

    // -----------------------------
    // COMPLETE SETUP
    // -----------------------------
    @POST
    @Path("/complete")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response completeSetup(
            @FormParam("musicLibraryPath") String musicLibraryPath,
            @FormParam("videoLibraryPath") String videoLibraryPath,
            @FormParam("installImportFeatures") Boolean installImportFeatures,
            @FormParam("outputFormat") String outputFormat,
            @FormParam("downloadThreads") Integer downloadThreads,
            @FormParam("searchThreads") Integer searchThreads,
            @FormParam("runAsService") Boolean runAsService) {
        
        try {
            setupController.completeSetup(musicLibraryPath, videoLibraryPath, 
                                        installImportFeatures, outputFormat,
                                        downloadThreads, searchThreads, runAsService);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Setup completed successfully");
            
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to complete setup: " + e.getMessage()))
                    .build();
        }
    }

    // -----------------------------
    // INSTALL REQUIREMENTS
    // -----------------------------
    @POST
    @Path("/install-requirements")
    @Consumes(MediaType.WILDCARD)
    public Response installRequirements() {
        try {
            // Start installation in background thread
            new Thread(() -> {
                try {
                    setupController.getImportService().installRequirements(1L); // Use default profile
                } catch (Exception e) {
                    // Log error if needed
                }
            }).start();
            
            return Response.ok(ApiResponse.success("Installation process started")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to start installation: " + e.getMessage()))
                    .build();
        }
    }

    // -----------------------------
    // RESET SETUP
    // -----------------------------
    @POST
    @Path("/reset")
    @Transactional
    public Response resetSetup() {
        try {
            setupController.resetSetup();
            return Response.ok(ApiResponse.success("Setup reset successfully")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to reset setup: " + e.getMessage()))
                    .build();
        }
    }
}