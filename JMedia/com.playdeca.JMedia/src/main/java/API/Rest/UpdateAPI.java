package API.Rest;

import API.ApiResponse;
import Services.UpdateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/update")
@Produces(MediaType.APPLICATION_JSON)
public class UpdateAPI {
    
    @Inject
    UpdateService updateService;
    
    @GET
    @Path("/check")
    public ApiResponse<UpdateService.UpdateInfo> checkForUpdates() {
        try {
            UpdateService.UpdateInfo updateInfo = updateService.checkForUpdates();
            return ApiResponse.<UpdateService.UpdateInfo>success(updateInfo);
        } catch (Exception e) {
            return ApiResponse.<UpdateService.UpdateInfo>error("Error checking for updates: " + e.getMessage());
        }
    }
    
    @GET
    @Path("/latest")
    public ApiResponse<UpdateService.UpdateInfo> getLatestInfo() {
        try {
            UpdateService.UpdateInfo updateInfo = updateService.checkForUpdates();
            return ApiResponse.<UpdateService.UpdateInfo>success(updateInfo);
        } catch (Exception e) {
            return ApiResponse.<UpdateService.UpdateInfo>error("Error fetching latest release: " + e.getMessage());
        }
    }
}