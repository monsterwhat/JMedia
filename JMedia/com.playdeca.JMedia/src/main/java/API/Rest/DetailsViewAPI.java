package API.Rest;

import Services.VideoService;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.function.Function;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
public class DetailsViewAPI {

    @Inject
    private VideoService videoService;

    // Qute Templates
    @Inject @io.quarkus.qute.Location("detailsFragment.html")
    Template detailsFragment;

    // Helper functions for Qute templates
    private String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int remainingSeconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    @GET
    @Path("/details/{videoId}")
    @Blocking
    public Response getDetailsView(@PathParam("videoId") Long videoId) {
        Models.Video item = videoService.find(videoId);
        
        if (item == null) {
            // Return error page or 404
            String errorHtml = "<div class='notification is-danger'>Video not found</div>";
            return Response.status(Response.Status.NOT_FOUND).entity(errorHtml).build();
        }
        
        String html = detailsFragment
                .data("item", item)
                .data("formatDuration", (Function<Integer, String>) this::formatDurationForTemplate)
                .render();
                
        return Response.ok(html).build();
    }

    @GET
    @Path("/details-fragment/{videoId}")
    @Blocking
    public Response getDetailsFragment(@PathParam("videoId") Long videoId) {
        // Same as view method for consistency
        return getDetailsView(videoId);
    }

    private String formatDurationForTemplate(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int remainingSeconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }
}