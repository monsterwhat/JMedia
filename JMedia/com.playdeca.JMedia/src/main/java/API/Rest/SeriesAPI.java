package API.Rest;

import API.ApiResponse;
import Models.Series;
import Models.Video;
import Services.SeriesService;
import Services.ThumbnailService;
import io.quarkus.panache.common.Page;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/series")
@Produces(MediaType.APPLICATION_JSON)
public class SeriesAPI {

    private static final Logger LOG = LoggerFactory.getLogger(SeriesAPI.class);

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    SeriesService seriesService;

    private boolean checkAdmin(HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        if (sessionId == null) return false;
        Models.Session session = Models.Session.findBySessionId(sessionId);
        if (session == null || !session.active) return false;
        Models.User user = Models.User.find("username", session.username).firstResult();
        return user != null && "admin".equals(user.getGroupName());
    }

    @GET
    @Blocking
    public Response listSeries(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        long total = Series.count();
        List<Series> series = Series.findAll()
                .page(Page.of(page, size))
                .list();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("series", series);
        result.put("page", page);
        result.put("size", size);
        result.put("totalItems", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));

        return Response.ok(ApiResponse.success(result)).build();
    }

    @GET
    @Path("/{id}")
    @Blocking
    public Response getSeries(@PathParam("id") Long id) {
        Series series = seriesService.find(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }
        return Response.ok(ApiResponse.success(series)).build();
    }

    @GET
    @Path("/{id}/episodes")
    @Blocking
    public Response getEpisodesBySeason(@PathParam("id") Long id) {
        SeriesService.SeriesWithEpisodes result = seriesService.findEpisodes(id);
        if (result.series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }

        List<Video> episodes = result.episodes;

        Map<Integer, List<Video>> grouped = episodes.stream()
                .filter(v -> v.seasonNumber != null)
                .collect(Collectors.groupingBy(
                        v -> v.seasonNumber,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("seriesId", result.series.id);
        resultMap.put("seriesTitle", result.series.title);
        resultMap.put("seasons", grouped);

        return Response.ok(ApiResponse.success(resultMap)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSeries(
            @Context HttpHeaders headers,
            Series input) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        if (input == null || input.title == null || input.title.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Title is required"))
                    .build();
        }

        Series series = seriesService.create(input);
        return Response.ok(ApiResponse.success(series)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateSeries(
            @Context HttpHeaders headers,
            @PathParam("id") Long id,
            Series input) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        Series series = seriesService.update(id, input);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }
        return Response.ok(ApiResponse.success(series)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteSeries(
            @Context HttpHeaders headers,
            @PathParam("id") Long id) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        if (!seriesService.delete(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }
        return Response.ok(ApiResponse.success("Series deleted")).build();
    }

    private Response serveSeriesImage(Long seriesId, String imageType) {
        try {
            Series series = seriesService.find(seriesId);
            if (series == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            // Always ensure images are downloaded from TMDB
            thumbnailService.ensureSeriesMediaImages(seriesId);

            // Re-read to get updated paths
            series = seriesService.find(seriesId);
            if (series == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            String imagePath = switch (imageType) {
                case "poster"   -> series.posterPath;
                case "logo"     -> series.logoPath;
                case "backdrop" -> series.backdropPath;
                case "hero"     -> series.heroPath;
                default -> null;
            };

            if (imagePath != null && !imagePath.isBlank()) {
                File imageFile = new File(imagePath);
                if (imageFile.exists() && imageFile.isFile()) {
                    String contentType = detectImageContentType(imagePath);
                    return Response.ok(imageFile)
                            .header("Content-Type", contentType)
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }
            }

            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (Exception e) {
            LOG.error("Error serving {} image for series ID: {}", imageType, seriesId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String detectImageContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "image/webp";
    }

    @GET
    @Path("/{id}/poster")
    public Response getSeriesPoster(@PathParam("id") Long id) {
        Series series = seriesService.find(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "poster");
    }

    @GET
    @Path("/{id}/backdrop")
    public Response getSeriesBackdrop(@PathParam("id") Long id) {
        Series series = seriesService.find(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "backdrop");
    }

    @GET
    @Path("/{id}/logo")
    public Response getSeriesLogo(@PathParam("id") Long id) {
        Series series = seriesService.find(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "logo");
    }

    @GET
    @Path("/{id}/hero")
    public Response getSeriesHero(@PathParam("id") Long id) {
        Series series = seriesService.find(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "hero");
    }
}
