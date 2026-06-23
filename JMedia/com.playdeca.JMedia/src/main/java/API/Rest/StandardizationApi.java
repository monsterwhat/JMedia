package API.Rest;

import API.ApiResponse;
import Services.RenameQueueProcessor;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/standardize")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandardizationApi {

    private static final Logger LOG = LoggerFactory.getLogger(StandardizationApi.class);

    @Inject
    RenameQueueProcessor renameQueueProcessor;

    @POST
    @Path("/start")
    public Response startStandardization() {
        LOG.info("Manual standardization requested");
        renameQueueProcessor.queueAllVideos();
        return Response.ok(ApiResponse.success("Standardization queued for all videos")).build();
    }

    @GET
    @Path("/status")
    public Response getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", renameQueueProcessor.getQueueSize());
        status.put("queuedCount", renameQueueProcessor.getQueuedCount());
        status.put("processedCount", renameQueueProcessor.getProcessedCount());
        status.put("isBusy", renameQueueProcessor.isBusy());
        return Response.ok(ApiResponse.success(status)).build();
    }

    @POST
    @Path("/queue/{videoId}")
    public Response queueVideo(@PathParam("videoId") Long videoId) {
        renameQueueProcessor.queueVideo(videoId);
        return Response.ok(ApiResponse.success("Video queued for standardization")).build();
    }

    @POST
    @Path("/clear")
    public Response clearQueue() {
        renameQueueProcessor.clearQueue();
        return Response.ok(ApiResponse.success("Standardization queue cleared")).build();
    }
}
