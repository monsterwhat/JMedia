package API.Rest;

import API.ApiResponse;
import Services.UpdateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/api/update")
@Produces(MediaType.APPLICATION_JSON)
public class UpdateAPI {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateAPI.class);

    @Inject
    UpdateService updateService;

    @Inject
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    String appVersion;
    
    @GET
    @Path("/check")
    public ApiResponse<UpdateService.UpdateInfo> checkForUpdates() {
        try {
            UpdateService.UpdateInfo updateInfo = updateService.checkForUpdates();
            return ApiResponse.<UpdateService.UpdateInfo>success(updateInfo);
        } catch (Exception e) {
            LOG.error("Error checking for updates", e);
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
            LOG.error("Error fetching latest release", e);
            return ApiResponse.<UpdateService.UpdateInfo>error("Error fetching latest release: " + e.getMessage());
        }
    }

    @GET
    @Path("/version")
    public ApiResponse<Map<String, String>> getVersion() {
        try {
            return ApiResponse.success(Map.of("version", appVersion, "currentVersion", appVersion));
        } catch (Exception e) {
            LOG.error("Error fetching version", e);
            return ApiResponse.error("Error fetching version: " + e.getMessage());
        }
    }
}