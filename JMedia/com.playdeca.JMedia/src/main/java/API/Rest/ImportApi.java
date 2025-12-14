package API.Rest;

import API.ApiResponse;
import Controllers.ImportController;
import Controllers.SettingsController;
import Models.DTOs.ImportInstallationStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

    // Individual installation endpoints
    @POST
    @Path("/install/python/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installPython(@PathParam("profileId") Long profileId) {
        try {
            importController.installPython(profileId);
            return Response.ok(ApiResponse.success("Python installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing Python: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing Python: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/install/ffmpeg/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installFFmpeg(@PathParam("profileId") Long profileId) {
        try {
            importController.installFFmpeg(profileId);
            return Response.ok(ApiResponse.success("FFmpeg installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing FFmpeg: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing FFmpeg: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/install/spotdl/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installSpotdl(@PathParam("profileId") Long profileId) {
        try {
            importController.installSpotdl(profileId);
            return Response.ok(ApiResponse.success("SpotDL installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing SpotDL: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing SpotDL: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/install/whisper/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installWhisper(@PathParam("profileId") Long profileId) {
        try {
            importController.installWhisper(profileId);
            return Response.ok(ApiResponse.success("Whisper installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing Whisper: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing Whisper: " + e.getMessage()))
                    .build();
        }
    }

    // Uninstall endpoints
    @POST
    @Path("/uninstall/python/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallPython(@PathParam("profileId") Long profileId) {
        try {
            importController.uninstallPython(profileId);
            return Response.ok(ApiResponse.success("Python uninstallation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error uninstalling Python: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error uninstalling Python: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/uninstall/ffmpeg/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallFFmpeg(@PathParam("profileId") Long profileId) {
        try {
            importController.uninstallFFmpeg(profileId);
            return Response.ok(ApiResponse.success("FFmpeg uninstallation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error uninstalling FFmpeg: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error uninstalling FFmpeg: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/uninstall/spotdl/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallSpotdl(@PathParam("profileId") Long profileId) {
        try {
            importController.uninstallSpotdl(profileId);
            return Response.ok(ApiResponse.success("SpotDL uninstallation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error uninstalling SpotDL: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error uninstalling SpotDL: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/uninstall/whisper/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallWhisper(@PathParam("profileId") Long profileId) {
        try {
            importController.uninstallWhisper(profileId);
            return Response.ok(ApiResponse.success("Whisper uninstallation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error uninstalling Whisper: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error uninstalling Whisper: " + e.getMessage()))
                    .build();
        }
    }
}
