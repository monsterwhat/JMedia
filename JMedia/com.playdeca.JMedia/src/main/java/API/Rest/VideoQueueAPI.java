package API.Rest;

import Controllers.VideoController; 
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/video/queue")
@Produces(MediaType.APPLICATION_JSON)
public class VideoQueueAPI {

    @Inject
    private VideoController videoController;

    @POST
    @Path("/add/{videoId}")
    @Blocking
    public Response addToQueue(@PathParam("videoId") Long videoId) {
        try {
            // Add video to queue (play next = true)
            videoController.addToQueue(java.util.List.of(videoId), true);
            return Response.ok("{\"success\":true,\"message\":\"Video added to queue\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/add")
    @Blocking
    public Response addMultipleToQueue(java.util.List<Long> videoIds) {
        try {
            if (videoIds == null || videoIds.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"success\":false,\"error\":\"No video IDs provided\"}").build();
            }
            
            // Add videos to queue (play next = false to add to end)
            videoController.addToQueue(videoIds, false);
            return Response.ok("{\"success\":true,\"message\":\"" + videoIds.size() + " videos added to queue\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/remove/{videoId}")
    @Blocking
    public Response removeFromQueue(@PathParam("videoId") Long videoId) {
        try {
            videoController.removeFromQueue(videoId);
            return Response.ok("{\"success\":true,\"message\":\"Video removed from queue\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/clear")
    @Blocking
    public Response clearQueue() {
        try {
            videoController.clearQueue();
            return Response.ok("{\"success\":true,\"message\":\"Queue cleared\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/skip-to/{index}")
    @Blocking
    public Response skipToQueueIndex(@PathParam("index") int index) {
        try {
            videoController.skipToQueueIndex(index);
            return Response.ok("{\"success\":true,\"message\":\"Skipped to queue position " + index + "\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/next")
    @Blocking
    public Response nextInQueue() {
        try {
            videoController.next();
            return Response.ok("{\"success\":true,\"message\":\"Next video in queue\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/previous")
    @Blocking
    public Response previousInQueue() {
        try {
            videoController.previous();
            return Response.ok("{\"success\":true,\"message\":\"Previous video in queue\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
}