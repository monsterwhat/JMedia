package API.Rest;

import API.ApiResponse;
import Services.AlbumLinkService;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Maintenance endpoints for the Album/Artist identity rows. Admin only: they
 * rewrite library rows en masse.
 */
@Path("/api/music/admin")
public class MusicAdminApi {

    @Inject
    AlbumLinkService albumLinkService;

    @Inject
    AuthService authService;

    /** Link every song that has no album/artist id yet. Idempotent. */
    @POST
    @Path("/backfill-albums")
    public Response backfillAlbums(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok(ApiResponse.success(albumLinkService.backfill(500))).build();
    }

    /** Delete albums/artists that no longer own a song. */
    @POST
    @Path("/cleanup-albums")
    public Response cleanupAlbums(@Context HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok(ApiResponse.success(albumLinkService.cleanupEmpty())).build();
    }
}
