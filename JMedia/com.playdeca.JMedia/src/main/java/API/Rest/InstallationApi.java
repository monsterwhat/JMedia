package API.Rest;

import API.ApiResponse;
import Controllers.InstallationController;
import Models.DTOs.ImportInstallationStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/installation")
public class InstallationApi {

    @Inject
    InstallationController installationController;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        try {
            ImportInstallationStatus status = installationController.getInstallationStatus();
            return Response.ok(ApiResponse.success(status)).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error getting installation status: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error getting installation status: " + e.getMessage()))
                    .build();
        }
    }

    // Individual installation endpoints
    @POST
    @Path("/install/package-manager/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installPackageManger(@PathParam("profileId") Long profileId) {
        try {
            installationController.installPackageManger(profileId);
            return Response.ok(ApiResponse.success("Package manager installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing package manager: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing package manager: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/install/python/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installPython(@PathParam("profileId") Long profileId) {
        try {
            installationController.installPython(profileId);
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
            installationController.installFFmpeg(profileId);
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
            installationController.installSpotdl(profileId);
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
            installationController.installWhisper(profileId);
            return Response.ok(ApiResponse.success("Whisper installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing Whisper: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing Whisper: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/install/all/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response installAllRequirements(@PathParam("profileId") Long profileId) {
        try {
            installationController.installAllRequirements(profileId);
            return Response.ok(ApiResponse.success("All requirements installation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error installing all requirements: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error installing all requirements: " + e.getMessage()))
                    .build();
        }
    }

    // Uninstallation endpoints
    @POST
    @Path("/uninstall/python/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallPython(@PathParam("profileId") Long profileId) {
        try {
            installationController.uninstallPython(profileId);
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
            installationController.uninstallFFmpeg(profileId);
            return Response.ok(ApiResponse.success("FFmpeg uninstallation started")).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error uninstalling FFmpeg: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    @POST
    @Path("/uninstall/spotdl/{profileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response uninstallSpotdl(@PathParam("profileId") Long profileId) {
        try {
            installationController.uninstallSpotdl(profileId);
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
            installationController.uninstallWhisper(profileId);
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