package API.Rest;

import API.ApiResponse;
import Services.AuthService;
import Services.ImdbIdBackfillService;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Admin-only endpoints to kick off the TMDb → IMDb id backfill
 * ({@link ImdbIdBackfillService}) and poll whether it is running.
 *
 * <p>The pass can run for minutes on a large library, so it is submitted to the
 * application executor and the request returns 202 immediately. Requests are
 * plain worker-thread calls ({@code @Blocking}) because the admin check reads the
 * session from the database.
 */
@Path("/api/video/backfill")
public class ImdbBackfillResource {

    private static final Logger LOG = LoggerFactory.getLogger(ImdbBackfillResource.class);

    @Inject
    ImdbIdBackfillService imdbIdBackfillService;

    @Inject
    AuthService authService;

    @Inject
    ExecutorService executor;

    /**
     * Starts the backfill. 202 when this request owns the pass, 409 when one is
     * already in flight, 403 for callers that are not admins.
     */
    @POST
    @Path("/imdb")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response startImdbBackfill(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            LOG.warn("IMDb backfill start rejected: admin access required");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required")).build();
        }
        if (!imdbIdBackfillService.tryStart()) {
            LOG.info("IMDb backfill start ignored: a pass is already running");
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("started", false, "reason", "already running")).build();
        }
        try {
            executor.submit(() -> {
                ManagedContext requestContext = Arc.container().requestContext();
                if (!requestContext.isActive()) requestContext.activate();
                try {
                    imdbIdBackfillService.backfillAll();
                } catch (Exception e) {
                    LOG.error("IMDb backfill pass aborted unexpectedly", e);
                } finally {
                    imdbIdBackfillService.finish();
                    if (requestContext.isActive()) requestContext.deactivate();
                }
            });
        } catch (Exception e) {
            imdbIdBackfillService.finish();
            LOG.error("Could not queue the IMDb backfill task", e);
            return Response.serverError()
                    .entity(ApiResponse.error("Could not start IMDb backfill")).build();
        }
        LOG.info("IMDb backfill started by admin");
        return Response.status(Response.Status.ACCEPTED).entity(Map.of("started", true)).build();
    }

    /** Whether a backfill pass is currently running. */
    @GET
    @Path("/imdb/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response getImdbBackfillStatus(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            LOG.warn("IMDb backfill status rejected: admin access required");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required")).build();
        }
        return Response.ok(Map.of("running", imdbIdBackfillService.isRunning())).build();
    }
}
