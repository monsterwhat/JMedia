package API.Rest;

import Controllers.VideoController; 
import Services.VideoStateService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/video/playback")
@Produces(MediaType.APPLICATION_JSON)
public class VideoPlaybackAPI {

    @Inject
    private VideoController videoController;

    @Inject
    private VideoStateService videoStateService;

    @POST
    @Path("/toggle")
    @Blocking
    public Response togglePlay() {
        try {
            videoController.togglePlay();
            return Response.ok("{\"success\":true,\"message\":\"Playback toggled\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/play/{videoId}")
    @Blocking
    public Response playVideo(@PathParam("videoId") Long videoId) {
        try {
            videoController.selectVideo(videoId);
            videoController.togglePlay(); // Ensure playing
            return Response.ok("{\"success\":true,\"message\":\"Video playing\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/play")
    @Blocking
    public Response play() {
        try {
            var currentState = videoStateService.getOrCreateState();
            if (currentState != null && currentState.getCurrentVideoId() != null) {
                videoController.togglePlay();
                return Response.ok("{\"success\":true,\"message\":\"Resumed playback\"}").build();
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"success\":false,\"error\":\"No video selected\"}").build();
            }
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/pause")
    @Blocking
    public Response pauseVideo() {
        try {
            videoController.togglePlay(); // toggle will pause if playing
            return Response.ok("{\"success\":true,\"message\":\"Video paused\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/next")
    @Blocking
    public Response nextVideo() {
        try {
            videoController.next();
            return Response.ok("{\"success\":true,\"message\":\"Next video\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/previous")
    @Blocking
    public Response previousVideo() {
        try {
            videoController.previous();
            return Response.ok("{\"success\":true,\"message\":\"Previous video\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/seek/{seconds}")
    @Blocking
    public Response seekTo(@PathParam("seconds") double seconds) {
        try {
            videoController.setSeconds(seconds);
            return Response.ok("{\"success\":true,\"message\":\"Seeked to " + seconds + " seconds\",\"position\":" + seconds + "}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/volume/{level}")
    @Blocking
    public Response setVolume(@PathParam("level") float level) {
        try {
            // Validate volume level (0.0 to 1.0)
            if (level < 0.0f || level > 1.0f) {
                return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"success\":false,\"error\":\"Volume level must be between 0.0 and 1.0\"}").build();
            }
            
            videoController.changeVolume(level);
            return Response.ok("{\"success\":true,\"message\":\"Volume set to " + (level * 100) + "%\",\"volume\":" + level + "}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/volume")
    @Blocking
    public Response setVolumeFromQuery(@QueryParam("level") Float level) {
        if (level == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                       .entity("{\"success\":false,\"error\":\"Volume level parameter required\"}").build();
        }
        return setVolume(level.floatValue());
    }

    @GET
    @Path("/current")
    @Blocking
    public Response getCurrentVideo() {
        try {
            var currentState = videoStateService.getOrCreateState();
            if (currentState == null) {
                return Response.ok("{\"success\":true,\"video\":null,\"message\":\"No current video\"}").build();
            }
            
            Long currentVideoId = currentState.getCurrentVideoId();
            if (currentVideoId == null) {
                return Response.ok("{\"success\":true,\"video\":null,\"message\":\"No current video\"}").build();
            }
            
            StringBuilder response = new StringBuilder();
            response.append("{\"success\":true,")
                   .append("\"video\":{")
                   .append("\"id\":").append(currentVideoId).append(",")
                   .append("\"title\":\"").append(safeString(currentState.getVideoTitle())).append("\",")
                   .append("\"seriesTitle\":\"").append(safeString(currentState.getSeriesTitle())).append("\",")
                   .append("\"episodeTitle\":\"").append(safeString(currentState.getEpisodeTitle())).append("\",")
                   .append("\"currentTime\":").append(currentState.getCurrentTime()).append(",")
                   .append("\"duration\":").append(currentState.getDuration()).append(",")
                   .append("\"playing\":").append(currentState.isPlaying()).append(",")
                   .append("\"volume\":").append(currentState.getVolume())
                   .append("},")
                   .append("\"message\":\"Current video state retrieved\"")
                   .append("}");
            
            return Response.ok(response.toString()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
    
    private String safeString(String str) {
        return str != null ? str.replace("\"", "\\\"") : "";
    }
}