package API.Rest;

import Services.VideoService;
import Services.VideoStateService;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.function.Function;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
public class PlaybackViewAPI {

    @Inject
    private VideoService videoService;

    @Inject
    private VideoStateService videoStateService;

    // Qute Templates
    @Inject @io.quarkus.qute.Location("playbackFragment.html")
    Template playbackFragment;

    // Helper functions for Qute templates
    private String formatDurationForTemplate(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int remainingSeconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    } 

    @GET
    @Path("/playback-fragment")
    @Blocking
    public Response getPlaybackFragment(@QueryParam("videoId") Long videoId) {
        Models.Video item = null;
        
        // Use provided videoId or current state
        if (videoId != null) {
            item = videoService.find(videoId);
        } else {
            var currentState = videoStateService.getOrCreateState();
            if (currentState != null && currentState.getCurrentVideoId() != null) {
                item = videoService.find(currentState.getCurrentVideoId());
            }
        }
        
        if (item == null) {
            String errorHtml = "<div class='notification is-warning'>No video available for playback</div>";
            return Response.status(Response.Status.BAD_REQUEST).entity(errorHtml).build();
        }
        
        String html = playbackFragment
                .data("item", item)
                .data("formatDuration", (Function<Integer, String>) this::formatDurationForTemplate)
                .render();
                
        return Response.ok(html).build();
    }
    
    @GET
    @Path("/playback-view")
    @Blocking
    public Response getPlaybackView() {
        // Get current video from state
        var currentState = videoStateService.getOrCreateState();
        if (currentState == null || currentState.getCurrentVideoId() == null) {
            // No current video, return to carousels
            String redirectHtml = "<script>window.location.href = '/video#carousels';</script>";
            return Response.ok(redirectHtml).build();
        }
        
        Models.Video item = videoService.find(currentState.getCurrentVideoId());
        if (item == null) {
            // Video not found, return to carousels
            String redirectHtml = "<script>window.location.href = '/video#carousels';</script>";
            return Response.ok(redirectHtml).build();
        }
        
        String html = playbackFragment
                .data("item", item)
                .data("formatDuration", (Function<Integer, String>) this::formatDurationForTemplate)
                .render();
                
        return Response.ok(html).build();
    }
}