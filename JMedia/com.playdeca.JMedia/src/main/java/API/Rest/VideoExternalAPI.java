package API.Rest;

import Models.ExistingVideo;
import Models.ExternalVideo;
import Services.ExternalVideoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/video/external")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VideoExternalAPI {

    @Inject
    ExternalVideoService externalVideoService;

    @Inject @io.quarkus.qute.Location("externalVideoFragment.html")
    io.quarkus.qute.Template externalFragment;

    private final ObjectMapper mapper = new ObjectMapper();

    @GET
    @Path("/fragment")
    @Produces(MediaType.TEXT_HTML)
    public Response getFragment() {
        return Response.ok(externalFragment.render()).build();
    }

    @GET
    @Path("/list")
    public Response list(@QueryParam("profileId") Long profileId) {
        if (profileId == null) profileId = 1L;
        List<ExternalVideo> videos = externalVideoService.findByProfile(profileId);
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(videos.stream().map(this::toJson).toList()));
        return Response.ok(root).build();
    }

    @POST
    @Path("/create")
    public Response create(JsonNode body) {
        String url = body.has("url") ? body.get("url").asText() : null;
        String title = body.has("title") ? body.get("title").asText() : null;
        Long profileId = body.has("profileId") ? body.get("profileId").asLong() : 1L;
        String alternativeUrls = body.has("alternativeUrls") ? body.get("alternativeUrls").toString() : null;
        String seriesTitle = body.has("seriesTitle") ? body.get("seriesTitle").asText() : null;
        Integer seasonNumber = body.has("seasonNumber") && !body.get("seasonNumber").isNull() ? body.get("seasonNumber").asInt() : null;
        Integer episodeNumber = body.has("episodeNumber") && !body.get("episodeNumber").isNull() ? body.get("episodeNumber").asInt() : null;
        String episodeTitle = body.has("episodeTitle") ? body.get("episodeTitle").asText() : null;
        ExistingVideo entryType = body.has("entryType") ? ExistingVideo.valueOf(body.get("entryType").asText()) : null;

        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "URL is required"))
                    .build();
        }

        ExternalVideo ev = externalVideoService.create(url, title, alternativeUrls, profileId,
                seriesTitle, seasonNumber, episodeNumber, episodeTitle, entryType);
        if (ev == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Profile not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", toJson(ev));
        return Response.ok(root).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") Long id, JsonNode body) {
        ExternalVideo ev = externalVideoService.findById(id);
        if (ev == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Not found"))
                    .build();
        }

        if (body.has("url")) ev.url = body.get("url").asText();
        if (body.has("title")) ev.title = body.get("title").asText();
        if (body.has("alternativeUrls")) {
            ev.alternativeUrls = body.get("alternativeUrls").toString();
        }
        if (body.has("seriesTitle")) ev.seriesTitle = body.get("seriesTitle").asText();
        if (body.has("seasonNumber") && !body.get("seasonNumber").isNull()) ev.seasonNumber = body.get("seasonNumber").asInt();
        if (body.has("episodeNumber") && !body.get("episodeNumber").isNull()) ev.episodeNumber = body.get("episodeNumber").asInt();
        if (body.has("episodeTitle")) ev.episodeTitle = body.get("episodeTitle").asText();
        if (body.has("entryType")) ev.entryType = ExistingVideo.valueOf(body.get("entryType").asText());
        ev.sourceType = externalVideoService.detectSourceType(ev.url);
        ev.persist();

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", toJson(ev));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        try {
            externalVideoService.delete(id);
            ObjectNode root = mapper.createObjectNode();
            root.put("success", true);
            return Response.ok(root).build();
        } catch (Exception e) {
            ObjectNode root = mapper.createObjectNode();
            root.put("success", false);
            root.put("error", "Delete failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(root).build();
        }
    }

    @POST
    @Path("/{id}/progress")
    public Response updateProgress(@PathParam("id") Long id, JsonNode body) {
        double currentTime = body.has("currentTime") ? body.get("currentTime").asDouble() : 0;
        double duration = body.has("duration") ? body.get("duration").asDouble() : 0;
        externalVideoService.updateProgress(id, currentTime, duration);
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        return Response.ok(root).build();
    }

    @GET
    @Path("/proxy/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response proxyStream(@QueryParam("url") String url,
                                @HeaderParam("Range") String range,
                                @HeaderParam("User-Agent") String userAgent) {
        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0");
            conn.setRequestProperty("Referer", new java.net.URL(url).getProtocol() + "://" + new java.net.URL(url).getHost());
            if (range != null) conn.setRequestProperty("Range", range);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            long contentLength = conn.getContentLengthLong();

            jakarta.ws.rs.core.Response.ResponseBuilder rb;
            if (status == 206 && range != null) {
                rb = Response.status(Response.Status.PARTIAL_CONTENT);
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) rb.header("Content-Range", contentRange);
            } else {
                rb = Response.ok();
            }

            rb.header("Access-Control-Allow-Origin", "*");
            rb.header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
            rb.header("Access-Control-Allow-Headers", "Range");
            rb.header("Cache-Control", "public, max-age=86400");

            if (contentType != null) rb.type(contentType);
            if (contentLength > 0) rb.header("Content-Length", contentLength);

            java.io.InputStream inputStream;
            if (status >= 400) {
                inputStream = conn.getErrorStream();
            } else {
                inputStream = conn.getInputStream();
            }

            if (inputStream == null) {
                return Response.status(Response.Status.BAD_GATEWAY).build();
            }

            // For HLS playlists, rewrite segment URLs to go through proxy
            if (contentType != null && (contentType.contains("m3u") || contentType.contains("vnd.apple.mpegurl"))) {
                String body = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();
                conn.disconnect();

                String baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
                String proxyBase = "/api/video/external/proxy/stream?url=";
                StringBuilder rewritten = new StringBuilder();
                for (String line : body.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        rewritten.append(line).append("\n");
                    } else {
                        String absoluteUrl = trimmed.startsWith("http") ? trimmed : baseUrl + trimmed;
                        rewritten.append(proxyBase).append(java.net.URLEncoder.encode(absoluteUrl, java.nio.charset.StandardCharsets.UTF_8)).append("\n");
                    }
                }
                return Response.ok(rewritten.toString())
                        .type("application/vnd.apple.mpegurl")
                        .header("Access-Control-Allow-Origin", "*")
                        .build();
            }

            rb.entity(inputStream);
            return rb.build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Proxy error: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        ExternalVideo ev = externalVideoService.findById(id);
        if (ev == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Not found"))
                    .build();
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", toJson(ev));
        return Response.ok(root).build();
    }

    @GET
    @Path("/series-titles")
    public Response getSeriesTitles() {
        List<String> titles = externalVideoService.findAllSeriesTitles();
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(titles));
        return Response.ok(root).build();
    }

    @GET
    @Path("/series/{seriesTitle}/seasons")
    public Response getSeasonsForSeries(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasons = externalVideoService.findSeasonNumbersForSeries(seriesTitle);
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(seasons));
        return Response.ok(root).build();
    }

    @GET
    @Path("/series/{seriesTitle}/seasons/{seasonNumber}/episodes")
    public Response getEpisodesForSeason(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<ExternalVideo> episodes = externalVideoService.findBySeriesAndSeason(seriesTitle, seasonNumber);
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(episodes.stream().map(this::toJson).toList()));
        return Response.ok(root).build();
    }

    @GET
    @Path("/movies")
    public Response getAllMovies() {
        List<ExternalVideo> movies = externalVideoService.findAllMovies();
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(movies.stream().map(this::toJson).toList()));
        return Response.ok(root).build();
    }

    private ObjectNode toJson(ExternalVideo ev) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ev.id);
        node.put("url", ev.url != null ? ev.url : "");
        node.put("title", ev.title != null ? ev.title : "");
        node.put("sourceType", ev.sourceType != null ? ev.sourceType : "unknown");
        node.put("currentTime", ev.currentTime);
        node.put("watchProgress", ev.watchProgress != null ? ev.watchProgress : 0.0);
        node.put("watched", ev.watched != null ? ev.watched : false);
        node.put("lastUpdated", ev.lastUpdated != null ? ev.lastUpdated.toString() : "");
        node.put("seriesTitle", ev.seriesTitle != null ? ev.seriesTitle : "");
        node.put("seasonNumber", ev.seasonNumber);
        node.put("episodeNumber", ev.episodeNumber);
        node.put("episodeTitle", ev.episodeTitle != null ? ev.episodeTitle : "");
        node.put("entryType", ev.entryType != null ? ev.entryType.name() : "");
        if (ev.alternativeUrls != null && !ev.alternativeUrls.isBlank() && !ev.alternativeUrls.equals("[]")) {
            try {
                node.set("alternativeUrls", mapper.readTree(ev.alternativeUrls));
            } catch (Exception e) {
                node.put("alternativeUrls", ev.alternativeUrls);
            }
        }
        return node;
    }
}
