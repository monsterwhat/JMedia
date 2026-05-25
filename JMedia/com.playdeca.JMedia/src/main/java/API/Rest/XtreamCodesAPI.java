package API.Rest;

import Models.User;
import Models.Video;
import Models.Xtream.*;
import Services.AuthService;
import Services.VideoService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/player_api.php")
@Produces(MediaType.APPLICATION_JSON)
public class XtreamCodesAPI {

    @Inject
    AuthService authService;

    @Inject
    VideoService videoService;

    @QueryParam("username")
    String username;

    @QueryParam("password")
    String password;

    @QueryParam("action")
    String action;

    @QueryParam("category_id")
    String categoryId;

    @QueryParam("series_id")
    String seriesId;

    @GET
    public Response handleRequest() {
        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        User user = userOpt.get();

        if (action == null) {
            return loginResponse(user);
        }

        switch (action) {
            case "get_vod_categories":
                return getVodCategories();
            case "get_vod_streams":
                return getVodStreams(categoryId);
            case "get_series_categories":
                return getSeriesCategories();
            case "get_series":
                return getSeries(categoryId);
            case "get_series_info":
                return getSeriesInfo(seriesId);
            case "get_vod_info":
                return getVodInfo(vodId);
            default:
                return Response.ok(new ArrayList<>()).build();
        }
    }

    @QueryParam("vod_id")
    Long vodId;

    private Response getVodInfo(Long vodId) {
        if (vodId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Video v = Video.findById(vodId);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", v.title);
        info.put("o_name", v.title);
        info.put("movie_image", "/api/video/thumbnail/" + v.id);
        info.put("releasedate", v.releaseDate);
        info.put("plot", v.overview);
        info.put("rating", v.imdbRating != null ? v.imdbRating.toString() : "0");
        info.put("director", v.directors != null ? String.join(", ", v.directors) : "");
        info.put("actors", v.cast != null ? String.join(", ", v.cast) : "");
        info.put("genre", v.genres != null ? String.join(", ", v.genres) : "");
        info.put("duration_secs", v.getDurationSeconds());
        
        response.put("info", info);
        
        java.util.Map<String, Object> movieData = new java.util.HashMap<>();
        movieData.put("stream_id", v.id);
        movieData.put("container_extension", v.container != null ? v.container : "mp4");
        
        response.put("movie_data", movieData);
        
        return Response.ok(response).build();
    }

    @GET
    @Path("/movie/{username}/{password}/{videoId}.{ext}")
    public Response streamMovie(@PathParam("videoId") Long videoId) {
        // Redirect to the existing stream API
        return Response.temporaryRedirect(java.net.URI.create("/api/video/stream/" + videoId)).build();
    }

    @GET
    @Path("/series/{username}/{password}/{videoId}.{ext}")
    public Response streamSeries(@PathParam("videoId") Long videoId) {
        // Redirect to the existing stream API
        return Response.temporaryRedirect(java.net.URI.create("/api/video/stream/" + videoId)).build();
    }

    private Response getSeriesInfo(String seriesId) {
        if (seriesId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        
        // Find episodes by hashing matching (as used in getSeries)
        List<Video> episodes = Video.find("type = 'episode'").list();
        List<Video> seriesEpisodes = episodes.stream()
                .filter(e -> e.seriesTitle != null && String.valueOf(e.seriesTitle.hashCode()).equals(seriesId))
                .sorted(java.util.Comparator.comparing(e -> e.seasonNumber != null ? e.seasonNumber : 0))
                .collect(Collectors.toList());

        if (seriesEpisodes.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();

        // Construct complex Xtream response for series info
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        Video first = seriesEpisodes.get(0);
        info.put("name", first.seriesTitle);
        info.put("cover", "/api/video/thumbnail/" + first.id);
        info.put("plot", first.overview);
        info.put("cast", first.cast != null ? String.join(", ", first.cast) : "");
        info.put("director", first.directors != null ? String.join(", ", first.directors) : "");
        info.put("genre", first.genres != null ? String.join(", ", first.genres) : "");
        info.put("releaseDate", first.releaseDate);
        info.put("rating", first.imdbRating);
        
        response.put("info", info);

        java.util.Map<String, List<java.util.Map<String, Object>>> seasons = new java.util.HashMap<>();
        for (Video e : seriesEpisodes) {
            String seasonNum = String.valueOf(e.seasonNumber != null ? e.seasonNumber : 1);
            seasons.computeIfAbsent(seasonNum, k -> new ArrayList<>());
            
            java.util.Map<String, Object> ep = new java.util.HashMap<>();
            ep.put("id", e.id);
            ep.put("title", e.title != null ? e.title : "Episode " + e.episodeNumber);
            ep.put("container_extension", e.container != null ? e.container : "mp4");
            ep.put("season", e.seasonNumber);
            ep.put("episode_num", e.episodeNumber);
            
            seasons.get(seasonNum).add(ep);
        }
        
        response.put("episodes", seasons);
        return Response.ok(response).build();
    }

    @Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    private Response loginResponse(User user) {
        XtreamLoginResponse response = new XtreamLoginResponse();
        
        response.userInfo = new XtreamLoginResponse.UserInfo();
        response.userInfo.username = user.getUsername();
        response.userInfo.password = "******"; // Hide password
        response.userInfo.auth = 1;
        response.userInfo.status = "Active";
        response.userInfo.exp_date = "1923052800"; // Far future timestamp
        response.userInfo.is_trial = "0";
        response.userInfo.active_cons = "0";
        response.userInfo.max_connections = "5";
        response.userInfo.allowed_output_formats = List.of("mp4", "mkv", "m3u8");

        response.serverInfo = new XtreamLoginResponse.ServerInfo();
        response.serverInfo.url = uriInfo.getBaseUri().getHost();
        response.serverInfo.port = String.valueOf(uriInfo.getBaseUri().getPort());
        response.serverInfo.https_port = "443";
        response.serverInfo.server_protocol = uriInfo.getBaseUri().getScheme();
        response.serverInfo.timezone = "UTC";
        response.serverInfo.timestamp_now = System.currentTimeMillis() / 1000;
        
        return Response.ok(response).build();
    }

    private Response getVodCategories() {
        // Map genres to categories
        List<Models.Genre> genres = Models.Genre.list("isActive = true");
        List<XtreamCategory> categories = genres.stream()
                .map(g -> new XtreamCategory(g.id.toString(), g.name))
                .collect(Collectors.toList());
        return Response.ok(categories).build();
    }

    private Response getVodStreams(String catId) {
        List<Video> videos;
        if (catId != null && !catId.equals("0")) {
            // Find genre name from ID
            Models.Genre genre = Models.Genre.findById(Long.parseLong(catId));
            if (genre != null) {
                videos = videoService.findByGenre(genre.name.toLowerCase(), 1, 1000);
            } else {
                videos = Video.find("type = 'movie'").list();
            }
        } else {
            videos = Video.find("type = 'movie'").list();
        }

        List<XtreamVodStream> streams = new ArrayList<>();
        int num = 1;
        for (Video v : videos) {
            XtreamVodStream s = new XtreamVodStream();
            s.num = num++;
            s.name = v.title;
            s.streamId = v.id;
            s.streamIcon = "/api/video/thumbnail/" + v.id;
            s.rating = v.imdbRating != null ? v.imdbRating.toString() : "0";
            s.added = v.dateAdded != null ? String.valueOf(v.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0";
            s.containerExtension = v.container != null ? v.container : "mp4";
            // Map the first genre ID if available
            if (v.genres != null && !v.genres.isEmpty()) {
                Models.Genre g = Models.Genre.find("LOWER(name) = ?1", v.genres.get(0).toLowerCase()).firstResult();
                s.categoryId = g != null ? g.id.toString() : "0";
            } else {
                s.categoryId = "0";
            }
            streams.add(s);
        }
        return Response.ok(streams).build();
    }

    private Response getSeriesCategories() {
        // Reuse genres for simplicity or define specific series categories
        return getVodCategories();
    }

    private Response getSeries(String catId) {
        List<Video> episodes = Video.find("type = 'episode'").list();
        // Group by series title
        java.util.Map<String, List<Video>> seriesGroups = episodes.stream()
                .filter(e -> e.seriesTitle != null)
                .collect(Collectors.groupingBy(e -> e.seriesTitle));

        List<XtreamSeries> seriesList = new ArrayList<>();
        int num = 1;
        for (java.util.Map.Entry<String, List<Video>> entry : seriesGroups.entrySet()) {
            Video first = entry.getValue().get(0);
            XtreamSeries s = new XtreamSeries();
            s.num = num++;
            s.name = entry.getKey();
            s.seriesId = String.valueOf(entry.getKey().hashCode()); // Hacky unique ID
            s.cover = "/api/video/thumbnail/" + first.id;
            s.plot = first.overview;
            s.rating = first.imdbRating != null ? first.imdbRating.toString() : "0";
            s.releaseDate = first.releaseDate;
            s.lastModified = String.valueOf(System.currentTimeMillis() / 1000);
            s.categoryId = "0"; // Map properly if possible
            seriesList.add(s);
        }
        return Response.ok(seriesList).build();
    }
}
