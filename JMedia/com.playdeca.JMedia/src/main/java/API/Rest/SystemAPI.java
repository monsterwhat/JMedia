package API.Rest;

import API.ApiResponse;
import Services.GpuDetectionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/system")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemAPI {

    @Inject
    GpuDetectionService gpuDetectionService;

    @GET
    @Path("/gpu-info")
    public Response getGpuInfo() {
        try {
            return Response.ok(ApiResponse.success(gpuDetectionService.getBestGpuSelection())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error retrieving GPU info: " + e.getMessage()))
                    .build();
        }
    }
}
