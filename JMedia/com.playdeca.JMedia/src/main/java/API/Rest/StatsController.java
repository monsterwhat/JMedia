package API.Rest;

import API.ApiResponse;
import Services.TranscodingService;
import Services.FFmpegDiscoveryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

@Path("/api/admin/stats")
@Produces(MediaType.APPLICATION_JSON)
public class StatsController {

    @Inject
    TranscodingService transcodingService;

    @Inject
    FFmpegDiscoveryService ffmpegDiscoveryService;

    @GET
    @Path("/transcoding")
    public Response getTranscodingStats() {
        Map<String, Long> stats = transcodingService.getStats();
        Set<String> invalidatedEncoders = ffmpegDiscoveryService.getInvalidatedEncoders();
        Map<String, Object> response = new HashMap<>(stats);
        response.put("invalidatedEncoders", invalidatedEncoders);
        return Response.ok(ApiResponse.success(response)).build();
    }
}
