package API.Rest;

import API.ApiResponse;
import Services.AuthService;
import Services.VideoEnrichmentWorker;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Category-only scan endpoints for movies and TV shows. These trigger the
 * genre backfill in {@link VideoEnrichmentWorker} so Xtream clients see full
 * get_vod_categories / get_series_categories lists without opening the UI or
 * running a full library scan.
 */
@Path("/api/video/genres")
public class VideoCategoryApi {

    private static final Logger LOG = LoggerFactory.getLogger(VideoCategoryApi.class);

    @Inject
    VideoEnrichmentWorker videoEnrichmentWorker;

    @Inject
    AuthService authService;

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public Response scanCategories(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        if (!videoEnrichmentWorker.isEnabled()) {
            LOG.info("Category scan skipped: video enrichment is disabled in system settings");
            return Response.ok(ApiResponse.success(
                    "Category scan skipped: video enrichment is disabled in system settings.")).build();
        }
        Thread sweep = new Thread(() -> {
            int queued = videoEnrichmentWorker.queueAllMissingGenres();
            LOG.info("Category scan sweep finished, queued {} videos for genre backfill", queued);
        }, "VideoCategoryScan-sweep");
        sweep.setDaemon(true);
        sweep.start();
        LOG.info("Category scan triggered by admin: queueing videos missing genres");
        return Response.ok(ApiResponse.success("Category scan started: queueing videos missing genres.")).build();
    }

    @GET
    @Path("/scan-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getScanStatus() {
        VideoEnrichmentWorker.CategoryProgress progress = videoEnrichmentWorker.getCategoryProgress();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("pending", progress.pending());
        status.put("processed", progress.processed());
        status.put("queueSize", progress.queueSize());
        status.put("isRunning", progress.running());
        return Response.ok(ApiResponse.success(status)).build();
    }

    @POST
    @Path("/scan-series/{seriesTitle}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response scanSeriesCategories(@PathParam("seriesTitle") String seriesTitle,
                                         @Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        if (seriesTitle == null || seriesTitle.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("seriesTitle is required")).build();
        }
        if (!videoEnrichmentWorker.isEnabled()) {
            LOG.info("Series category scan skipped for '{}': video enrichment is disabled in system settings", seriesTitle);
            return Response.ok(ApiResponse.success(
                    "Category scan skipped: video enrichment is disabled in system settings.")).build();
        }
        Thread sweep = new Thread(() -> {
            int queued = videoEnrichmentWorker.queueSeriesGenres(seriesTitle);
            LOG.info("Series category scan sweep finished for '{}', queued {} episodes", seriesTitle, queued);
        }, "VideoCategoryScan-series");
        sweep.setDaemon(true);
        sweep.start();
        LOG.info("Category scan triggered for series '{}'", seriesTitle);
        return Response.ok(ApiResponse.success("Category scan started for series '" + seriesTitle + "'.")).build();
    }
}