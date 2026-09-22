package API.Rest;

import Models.Video.Video;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Extension-style artwork URLs for IPTV clients whose native image loaders
 * key off the URL path ending (e.g. Smarters on Apple TV ignores
 * query-string artwork URLs like player_api.php?action=get_thumbnail&...).
 * Serves the same JPEG bytes as the player_api.php actions: direct 200 when
 * artwork exists, bundled placeholder bytes when it does not (never redirects,
 * since some IPTV image loaders cannot follow them). Query-string Xtream
 * credentials required (no session cookie).
 */
@Path("/art")
public class ArtworkAPI {

    private static final Logger log = Logger.getLogger(ArtworkAPI.class);

    @Inject
    AuthService authService;

    @Inject
    Services.ThumbnailService thumbnailService;

    @GET
    @Path("/movie/{videoId}.jpg")
    @Produces("image/jpeg")
    public Response movieArtwork(@PathParam("videoId") Long videoId,
                                 @QueryParam("username") String username,
                                 @QueryParam("password") String password) {
        if (!isValidArtCredentials(username, password)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (videoId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Video v = Video.findById(videoId);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();

        if (thumbnailService.hasThumbnail(videoId)) {
            byte[] img = thumbnailService.getThumbnailBytes(videoId);
            if (img != null && img.length > 0) {
                return serveJpeg(img);
            }
            log.warnf("art/movie: cached thumbnail unreadable for videoId=%d, trying poster fallback", videoId);
        }
        for (String posterName : new String[]{videoId + "_poster.webp", videoId + "_poster.png"}) {
            try {
                java.nio.file.Path posterPath = thumbnailService.getThumbnailDirectory().resolve(posterName);
                if (java.nio.file.Files.exists(posterPath) && java.nio.file.Files.isRegularFile(posterPath)) {
                    byte[] img = java.nio.file.Files.readAllBytes(posterPath);
                    if (img.length > 0) {
                        return serveJpeg(img);
                    }
                }
            } catch (Exception e) {
                log.warnf("art/movie: poster fallback %s unreadable for videoId=%d: %s", posterName, videoId, e.getMessage());
            }
        }
        log.warnf("art/movie: no artwork for videoId=%d, serving bundled placeholder", videoId);
        byte[] placeholder = thumbnailService.getPlaceholderImageBytes();
        if (placeholder != null && placeholder.length > 0) {
            return serveJpeg(placeholder);
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @GET
    @Path("/series/{seriesId}.jpg")
    @Produces("image/jpeg")
    public Response seriesArtwork(@PathParam("seriesId") String seriesId,
                                  @QueryParam("username") String username,
                                  @QueryParam("password") String password) {
        if (!isValidArtCredentials(username, password)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (seriesId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Models.Video.Series matched = null;
        try {
            matched = Models.Video.Series.findById(Long.parseLong(seriesId));
        } catch (NumberFormatException e) {
            log.debugf("art/series: seriesId=%s is not numeric, trying title hash match", seriesId);
        }
        if (matched == null) {
            for (Models.Video.Series sv : Models.Video.Series.<Models.Video.Series>listAll()) {
                if (hashId(sv.title).equals(seriesId)) {
                    matched = sv;
                    break;
                }
            }
        }
        if (matched == null) {
            log.warnf("art/series: no series match for seriesId=%s", seriesId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        byte[] raw = null;
        try {
            if (matched.backdropPath != null && !matched.backdropPath.isBlank()) {
                java.nio.file.Path p = java.nio.file.Path.of(matched.backdropPath);
                if (java.nio.file.Files.isRegularFile(p)) {
                    raw = java.nio.file.Files.readAllBytes(p);
                } else {
                    log.warnf("art/series: backdrop file missing for seriesId=%s at %s", seriesId, matched.backdropPath);
                }
            }
            if ((raw == null || raw.length == 0) && matched.id != null) {
                String posterFile = thumbnailService.findSeriesImageFile(matched.id, "poster");
                if (posterFile != null) {
                    try {
                        raw = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(posterFile));
                    } catch (Exception e) {
                        log.warnf("art/series: poster file unreadable for seriesId=%s at %s: %s", seriesId, posterFile, e.getMessage());
                    }
                }
            }
            if (raw != null && raw.length > 0) {
                return serveJpeg(raw);
            }
        } catch (Exception e) {
            log.errorf("art/series: failure for seriesId=%s: %s", seriesId, e.getMessage());
        }
        log.warnf("art/series: no artwork for seriesId=%s, serving bundled placeholder", seriesId);
        byte[] placeholder = thumbnailService.getPlaceholderImageBytes();
        if (placeholder != null && placeholder.length > 0) {
            return serveJpeg(placeholder);
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    private Response serveJpeg(byte[] img) {
        Services.ThumbnailService.ServedImage served = thumbnailService.toJpegForServing(img);
        if (served.bytes() == null || served.bytes().length == 0) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(served.bytes())
                .type(served.contentType())
                .header("Cache-Control", "public, no-cache")
                .build();
    }

    private boolean isValidArtCredentials(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warnf("art: rejected request with missing credentials");
            return false;
        }
        boolean ok = authService.authenticate(username, password).isPresent();
        if (!ok) {
            log.warnf("art: auth failed for username=%s", username);
        }
        return ok;
    }

    private static String hashId(String value) {
        if (value == null) return "";
        return java.util.UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
