package API.Rest;

import API.ApiResponse;
import Controllers.ImportController;
import Controllers.SettingsController;
import Models.DTOs.ImportInstallationStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Paths;

@Path("/api/import")
public class ImportApi {

    @Inject
    ImportController importController;

    @Inject
    SettingsController settingsController;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        try {
            ImportInstallationStatus status = importController.getInstallationStatus();
            return Response.ok(ApiResponse.success(status)).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error getting Import installation status: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error getting Import installation status: " + e.getMessage()))
                    .build();
        }
    }

    // New endpoint to get the default Import download path
    @GET
    @Path("/{profileId}/default-download-path")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDefaultDownloadPath(@PathParam("profileId") Long profileId) {
        try {
            String musicLibraryPath = settingsController.getMusicLibraryPath();
            if (musicLibraryPath == null || musicLibraryPath.isBlank()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Music library path not configured in settings."))
                        .build();
            }
            // Construct the full path using Paths.get for OS-specific correctness
            String defaultImportPath = Paths.get(musicLibraryPath, "import").toString();
            return Response.ok(ApiResponse.success(defaultImportPath)).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error getting default Import download path: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error getting default Import download path: " + e.getMessage()))
                    .build();
        }
    }
}
