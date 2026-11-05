package API.Rest;

import Models.Settings.User;
import Models.Video.MediaRequest;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api/library/requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaRequestResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaRequestResource.class);
    private static final Set<String> ALLOWED_KINDS = Set.of("imdb", "tmdb", "tvdb", "db");
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_HANDLED = "HANDLED";
    private static final String POSTER_PREFIX = "https://image.tmdb.org/t/p/";

    @Inject
    AuthService authService;

    public static class RequestPayload {
        public String token;
        public String kind;
        public String title;
        public Integer year;
        public String posterUrl;
        public String mediaType;
        public Integer tmdbId;
    }

    public static class StatusPayload {
        public String status;
    }

    @POST
    @Transactional
    public Response create(@Context HttpHeaders headers, RequestPayload payload) {
        User user = authService.getCurrentUser(headers);
        if (user == null) {
            LOG.warn("Media request rejected: unauthenticated caller");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Authentication required")).build();
        }

        String validationError = validate(payload);
        if (validationError != null) {
            LOG.warn("Media request rejected for user '{}': {}", user.getUsername(), validationError);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", validationError)).build();
        }

        String token = payload.token.trim();
        try {
            long pending = MediaRequest.count("token = ?1 and status = ?2", token, STATUS_PENDING);
            if (pending > 0) {
                LOG.info("Media request already pending for token '{}' (user '{}')", token, user.getUsername());
                return Response.ok(Map.of("created", false, "existing", true)).build();
            }

            MediaRequest req = new MediaRequest();
            req.token = token;
            req.kind = payload.kind.trim();
            req.title = payload.title.trim();
            req.year = payload.year;
            req.posterUrl = payload.posterUrl;
            req.mediaType = payload.mediaType != null ? payload.mediaType.trim() : null;
            req.tmdbId = payload.tmdbId;
            req.requestedBy = user.getUsername();
            req.createdAt = Instant.now();
            req.status = STATUS_PENDING;
            req.persist();

            LOG.info("Media request created id={} token='{}' kind='{}' title='{}' by='{}'",
                    req.id, req.token, req.kind, req.title, req.requestedBy);
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of("created", true)).build();
        } catch (Exception e) {
            LOG.error("Failed to persist media request for token '{}' by '{}'", token, user.getUsername(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Could not save request")).build();
        }
    }

    @GET
    public Response list(@Context HttpHeaders headers, @QueryParam("status") String status) {
        if (!authService.isAdmin(headers)) {
            LOG.warn("Media request list rejected: admin access required");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Admin access required")).build();
        }

        List<MediaRequest> items;
        if (status != null && !status.trim().isEmpty()) {
            items = MediaRequest.find("status = ?1 order by createdAt desc", status.trim())
                    .page(0, 200).list();
        } else {
            items = MediaRequest.find("order by createdAt desc")
                    .page(0, 200).list();
        }
        return Response.ok(items).build();
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    public Response updateStatus(@Context HttpHeaders headers,
                                 @PathParam("id") Long id,
                                 StatusPayload payload) {
        if (!authService.isAdmin(headers)) {
            LOG.warn("Media request status update rejected for id={}: admin access required", id);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Admin access required")).build();
        }

        String newStatus = payload != null && payload.status != null ? payload.status.trim() : "";
        if (!STATUS_HANDLED.equals(newStatus)) {
            LOG.warn("Media request status update rejected for id={}: invalid status '{}'", id, newStatus);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "status must be HANDLED")).build();
        }

        try {
            MediaRequest req = MediaRequest.findById(id);
            if (req == null) {
                LOG.warn("Media request status update failed: id={} not found", id);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Request not found")).build();
            }
            req.status = STATUS_HANDLED;
            LOG.info("Media request id={} marked HANDLED", id);
            return Response.ok(Map.of("id", req.id, "status", req.status)).build();
        } catch (Exception e) {
            LOG.error("Failed to update media request id={}", id, e);
            return Response.serverError()
                    .entity(Map.of("error", "Could not update request")).build();
        }
    }

    private static String validate(RequestPayload payload) {
        if (payload == null) {
            return "Request body is required";
        }
        if (payload.token == null || payload.token.trim().isEmpty()) {
            return "token is required";
        }
        if (payload.token.trim().length() > 64) {
            return "token must be 64 characters or fewer";
        }
        if (payload.kind == null || !ALLOWED_KINDS.contains(payload.kind.trim())) {
            return "kind must be one of imdb, tmdb, tvdb, db";
        }
        if (payload.title == null || payload.title.trim().isEmpty()) {
            return "title is required";
        }
        if (payload.title.trim().length() > 300) {
            return "title must be 300 characters or fewer";
        }
        if (payload.posterUrl != null && !payload.posterUrl.isEmpty()
                && !payload.posterUrl.startsWith(POSTER_PREFIX)) {
            return "posterUrl must start with https://image.tmdb.org/t/p/";
        }
        return null;
    }
}
