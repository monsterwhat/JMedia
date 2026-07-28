package API.Rest;

import API.ApiResponse;
import Models.Series;
import Models.Video;
import Services.ThumbnailService;
import Services.VideoMetadataService;
import io.quarkus.panache.common.Page;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    VideoMetadataService videoMetadataService;

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
    @jakarta.transaction.Transactional
    public Response getSeries(@PathParam("id") Long id) {
        try {
            videoMetadataService.ensureSeriesTextMetadata(id);
        } catch (Exception e) {
            LOG.debug("Could not enrich series text metadata for {}: {}", id, e.getMessage());
        }
        Series series = Series.findById(id);
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
        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }

        List<Video> episodes = Video.find("series = ?1 ORDER BY seasonNumber ASC, episodeNumber ASC", series)
                .list();

        Map<Integer, List<Video>> grouped = episodes.stream()
                .filter(v -> v.seasonNumber != null)
                .collect(Collectors.groupingBy(
                        v -> v.seasonNumber,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seriesId", series.id);
        result.put("seriesTitle", series.title);
        result.put("seasons", grouped);

        return Response.ok(ApiResponse.success(result)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
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

        Series series = new Series();
        series.title = input.title.trim();
        series.description = input.description;
        series.tagline = input.tagline;
        series.overview = input.overview;
        series.genres = input.genres;
        series.directors = input.directors;
        series.writers = input.writers;
        series.cast = input.cast;
        series.productionCompanies = input.productionCompanies;
        series.networks = input.networks;
        series.imdbRating = input.imdbRating;
        series.tmdbRating = input.tmdbRating;
        series.metacriticRating = input.metacriticRating;
        series.voteCount = input.voteCount;
        series.popularityScore = input.popularityScore;
        series.releaseYear = input.releaseYear;
        series.runtimeMins = input.runtimeMins;
        series.mpaaRating = input.mpaaRating;
        series.status = input.status;
        series.originalLanguage = input.originalLanguage;
        series.productionCountries = input.productionCountries;
        series.releaseDate = input.releaseDate;
        series.trailerUrl = input.trailerUrl;
        series.parentsGuide = input.parentsGuide;
        series.imdbId = input.imdbId;
        series.tmdbId = input.tmdbId;
        series.tvdbId = input.tvdbId;
        series.budget = input.budget;
        series.revenue = input.revenue;
        series.collectionName = input.collectionName;
        series.franchiseName = input.franchiseName;
        series.logoPath = input.logoPath;
        series.posterPath = input.posterPath;
        series.backdropPath = input.backdropPath;
        series.heroPath = input.heroPath;
        series.fanartPath = input.fanartPath;
        series.stillPath = input.stillPath;
        series.akas = input.akas;
        series.keywords = input.keywords;

        series.persist();
        LOG.info("Created series: {} (id={})", series.title, series.id);
        return Response.ok(ApiResponse.success(series)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateSeries(
            @Context HttpHeaders headers,
            @PathParam("id") Long id,
            Series input) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }

        if (input.title != null && !input.title.trim().isEmpty()) {
            series.title = input.title.trim();
        }
        if (input.description != null) series.description = input.description;
        if (input.tagline != null) series.tagline = input.tagline;
        if (input.overview != null) series.overview = input.overview;
        if (input.genres != null) series.genres = input.genres;
        if (input.directors != null) series.directors = input.directors;
        if (input.writers != null) series.writers = input.writers;
        if (input.cast != null) series.cast = input.cast;
        if (input.productionCompanies != null) series.productionCompanies = input.productionCompanies;
        if (input.networks != null) series.networks = input.networks;
        if (input.imdbRating != null) series.imdbRating = input.imdbRating;
        if (input.tmdbRating != null) series.tmdbRating = input.tmdbRating;
        if (input.metacriticRating != null) series.metacriticRating = input.metacriticRating;
        if (input.voteCount != null) series.voteCount = input.voteCount;
        if (input.popularityScore != null) series.popularityScore = input.popularityScore;
        if (input.releaseYear != null) series.releaseYear = input.releaseYear;
        if (input.runtimeMins != null) series.runtimeMins = input.runtimeMins;
        if (input.mpaaRating != null) series.mpaaRating = input.mpaaRating;
        if (input.status != null) series.status = input.status;
        if (input.originalLanguage != null) series.originalLanguage = input.originalLanguage;
        if (input.productionCountries != null) series.productionCountries = input.productionCountries;
        if (input.releaseDate != null) series.releaseDate = input.releaseDate;
        if (input.trailerUrl != null) series.trailerUrl = input.trailerUrl;
        if (input.parentsGuide != null) series.parentsGuide = input.parentsGuide;
        if (input.imdbId != null) series.imdbId = input.imdbId;
        if (input.tmdbId != null) series.tmdbId = input.tmdbId;
        if (input.tvdbId != null) series.tvdbId = input.tvdbId;
        if (input.budget != null) series.budget = input.budget;
        if (input.revenue != null) series.revenue = input.revenue;
        if (input.collectionName != null) series.collectionName = input.collectionName;
        if (input.franchiseName != null) series.franchiseName = input.franchiseName;
        if (input.logoPath != null) series.logoPath = input.logoPath;
        if (input.posterPath != null) series.posterPath = input.posterPath;
        if (input.backdropPath != null) series.backdropPath = input.backdropPath;
        if (input.heroPath != null) series.heroPath = input.heroPath;
        if (input.fanartPath != null) series.fanartPath = input.fanartPath;
        if (input.stillPath != null) series.stillPath = input.stillPath;
        if (input.akas != null) series.akas = input.akas;
        if (input.keywords != null) series.keywords = input.keywords;

        LOG.info("Updated series: {} (id={})", series.title, series.id);
        return Response.ok(ApiResponse.success(series)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteSeries(
            @Context HttpHeaders headers,
            @PathParam("id") Long id) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found"))
                    .build();
        }

        // Unlink episodes — set series_id=null, don't delete episodes
        Video.update("series = null WHERE series = ?1", series);

        series.delete();
        LOG.info("Deleted series: {} (id={}) — episodes unlinked", series.title, id);
        return Response.ok(ApiResponse.success("Series deleted")).build();
    }

    private Response serveSeriesImage(Long seriesId, String imageType) {
        try {
            Series series = Series.findById(seriesId);
            if (series == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            // Always ensure images are downloaded from TMDB
            thumbnailService.ensureSeriesMediaImages(seriesId);

            // Re-read to get updated paths
            series = Series.findById(seriesId);
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
        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "poster");
    }

    @GET
    @Path("/{id}/backdrop")
    public Response getSeriesBackdrop(@PathParam("id") Long id) {
        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "backdrop");
    }

    @GET
    @Path("/{id}/logo")
    public Response getSeriesLogo(@PathParam("id") Long id) {
        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "logo");
    }

    @GET
    @Path("/{id}/hero")
    public Response getSeriesHero(@PathParam("id") Long id) {
        Series series = Series.findById(id);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveSeriesImage(id, "hero");
    }
}
