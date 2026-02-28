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

    @Inject
    private Controllers.VideoController videoController;

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
        
        // Use provided videoId or current state from VideoController's memory
        if (videoId != null && videoId > 0) {
            item = videoService.find(videoId);
        } else {
            // Check VideoController's memory state first as it's the most up-to-date
            var currentState = videoController.getState();
            if (currentState != null && currentState.getCurrentVideoId() != null) {
                item = videoService.find(currentState.getCurrentVideoId());
            } else {
                // Fallback to database state via VideoStateService
                var dbState = videoStateService.getOrCreateState();
                if (dbState != null && dbState.getCurrentVideoId() != null) {
                    item = videoService.find(dbState.getCurrentVideoId());
                }
            }
        }
        
        if (item == null) {
            String errorHtml = "<div class='notification is-warning'>No video available for playback</div>";
            return Response.status(Response.Status.OK).entity(errorHtml).build(); // Return 200 with error message
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
        // Get current video from VideoController's memory
        var currentState = videoController.getState();
        if (currentState == null || currentState.getCurrentVideoId() == null) {
            // Fallback to database
            var dbState = videoStateService.getOrCreateState();
            if (dbState == null || dbState.getCurrentVideoId() == null) {
                // No current video, return to carousels
                String redirectHtml = "<script>window.location.href = '/video#carousels';</script>";
                return Response.ok(redirectHtml).build();
            }
            currentState = dbState;
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