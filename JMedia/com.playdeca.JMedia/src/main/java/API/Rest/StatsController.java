package API.Rest;

import API.ApiResponse;
import Services.AuthService;
import Services.TranscodingService;
import Services.FFmpegDiscoveryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
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

    @Inject
    AuthService authService;

    @GET
    @Path("/transcoding")
    public Response getTranscodingStats(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers))
            return Response.status(Response.Status.FORBIDDEN).build();
        Map<String, Long> stats = transcodingService.getStats();
        Set<String> invalidatedEncoders = ffmpegDiscoveryService.getInvalidatedEncoders();
        Map<String, Object> response = new HashMap<>(stats);
        response.put("invalidatedEncoders", invalidatedEncoders);
        return Response.ok(ApiResponse.success(response)).build();
    }
}
