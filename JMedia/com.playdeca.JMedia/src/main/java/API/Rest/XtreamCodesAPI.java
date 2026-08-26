package API.Rest;

import Models.Settings.User;
import Models.Video.Video;
import Models.Video.LiveChannel;
import Models.Video.M3uPlaylist;
import Models.Xtream.*;
import Services.AuthService;
import Services.VideoService;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/player_api.php")
@Produces(MediaType.APPLICATION_JSON)
public class XtreamCodesAPI {

    private static final Logger log = Logger.getLogger(XtreamCodesAPI.class);

    @Inject
    AuthService authService;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    Services.EpgService epgService;

    @Inject
    Services.ThumbnailService thumbnailService;

    @QueryParam("username")
    String username;

    @QueryParam("password")
    String password;

    @QueryParam("action")
    String action;

    @QueryParam("category_id")
    String categoryId;

    @QueryParam("limit")
    Integer epgLimit;

    @QueryParam("series_id")
    String seriesId;

    @GET
    public Response handleRequest() {
        // Log ALL query params to detect what the client sends
        java.util.Map<String, String> allParams = new java.util.LinkedHashMap<>();
        if (username != null) allParams.put("username", username);
        if (password != null) allParams.put("password", "***");
        if (action != null) allParams.put("action", action);
        if (categoryId != null) allParams.put("category_id", categoryId);
        if (seriesId != null) allParams.put("series_id", seriesId);
        if (vodId != null) allParams.put("vod_id", String.valueOf(vodId));
        if (liveStreamId != null) allParams.put("stream_id", String.valueOf(liveStreamId));
        if (epgLimit != null) allParams.put("limit", String.valueOf(epgLimit));
        // Also dump raw query string to catch any params we don't have @QueryParam for (password redacted)
        try {
            String rawQuery = uriInfo.getRequestUri().getQuery();
            log.infof("Xtream request: %s, rawQuery=%s", allParams, redactQuery(rawQuery));
        } catch (Exception e) {
            log.infof("Xtream request: %s (could not get raw query: %s)", allParams, e.getMessage());
        }

        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            log.warnf("Auth failed for username=%s", username);
            java.util.Map<String, Object> authFail = new java.util.HashMap<>();
            java.util.Map<String, Object> userInfo = new java.util.HashMap<>();
            userInfo.put("auth", 0);
            authFail.put("user_info", userInfo);
            return Response.ok(authFail).build();
        }

        User user = userOpt.get();

        if (action == null) {
            log.infof("Login response for user=%s", user.getUsername());
            return loginResponse(user, password);
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
            case "get_live_categories":
                return getLiveCategories();
            case "get_live_streams":
                return getLiveStreams(categoryId);
            case "get_live_stream_info":
                return getLiveStreamInfo(liveStreamId);
            case "get_short_epg":
                return getShortEpg(liveStreamId);
            case "get_epg":
                return getFullEpg(liveStreamId);
            case "get_simple_data_table":
                return getFullEpg(liveStreamId);
            case "get_thumbnail":
                return getThumbnail(vodId);
            case "changePassword":
                return Response.ok(java.util.Map.of("user_info", java.util.Map.of("auth", 1))).build();
            default:
                return Response.ok(new ArrayList<>()).build();
        }
    }

    @QueryParam("vod_id")
    Long vodId;

    @QueryParam("stream_id")
    Long liveStreamId;

    private Response getVodInfo(Long vodId) {
        if (vodId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Video v = Video.findById(vodId);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();
        log.infof("getVodInfo: vodId=%d, title=%s, tmdbId=%s, posterPath=%s, container=%s, path=%s",
                vodId, v.title, v.tmdbId, v.posterPath, v.container, v.path);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", v.title);
        info.put("o_name", v.title);
        info.put("movie_image", getImageUrl(v));
        info.put("cover_big", getImageUrl(v));
        info.put("releasedate", v.releaseDate);
        info.put("plot", v.overview != null ? v.overview : "");
        info.put("description", v.overview != null ? v.overview : "");
        info.put("rating", v.imdbRating != null ? v.imdbRating.toString() : "0");
        info.put("rating_5based", v.imdbRating != null ? Math.ceil(v.imdbRating / 2.0) : 0.0);
        info.put("director", v.directors != null ? String.join(", ", v.directors) : "");
        info.put("actors", v.cast != null ? String.join(", ", v.cast) : "");
        info.put("cast", v.cast != null ? String.join(", ", v.cast) : "");
        info.put("genre", v.genres != null ? String.join(", ", v.genres) : "");
        info.put("duration_secs", v.getDurationSeconds());
        info.put("duration", formatDuration(v.getDurationSeconds()));
        info.put("bitrate", v.bitrate != null ? v.bitrate : 0);
        info.put("youtube_trailer", v.trailerUrl != null ? v.trailerUrl : "");
        info.put("backdrop_path", new ArrayList<>());
        info.put("tmdb_id", v.tmdbId != null ? v.tmdbId : "");
        
        java.util.Map<String, Object> videoInfo = new java.util.HashMap<>();
        videoInfo.put("codec_name", v.videoCodec != null ? v.videoCodec : "");
        videoInfo.put("width", v.resolution != null ? parseWidth(v.resolution) : 0);
        videoInfo.put("height", v.resolution != null ? parseHeight(v.resolution) : 0);
        info.put("video", videoInfo);
        
        java.util.Map<String, Object> audioInfo = new java.util.HashMap<>();
        audioInfo.put("codec_name", v.audioCodec != null ? v.audioCodec : "");
        audioInfo.put("channels", v.audioChannels != null ? v.audioChannels : 0);
        info.put("audio", audioInfo);
        
        response.put("info", info);
        
        java.util.Map<String, String> genreIds = genreIdByName();
        String vodCategoryId = "0";
        List<String> vodCategoryIds = new ArrayList<>();
        if (v.genres != null && !v.genres.isEmpty()) {
            for (String g : v.genres) {
                String gid = genreIds.getOrDefault(g.toLowerCase(), "0");
                if (!"0".equals(gid)) vodCategoryIds.add(gid);
            }
            vodCategoryId = vodCategoryIds.isEmpty() ? "0" : vodCategoryIds.get(0);
        }

        java.util.Map<String, Object> movieData = new java.util.HashMap<>();
        movieData.put("stream_id", v.id);
        movieData.put("name", v.title);
        movieData.put("added", v.dateAdded != null ? String.valueOf(v.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
        movieData.put("category_id", vodCategoryId);
        movieData.put("category_ids", vodCategoryIds.isEmpty() ? new ArrayList<>(List.of("0")) : vodCategoryIds);
        movieData.put("stream_icon", getImageUrl(v));
        movieData.put("year", v.releaseYear != null ? String.valueOf(v.releaseYear) : "");
        movieData.put("container_extension", v.container != null ? v.container : "mp4");
        movieData.put("custom_sid", "");
        movieData.put("direct_source", "");
        
        response.put("movie_data", movieData);
        
        log.infof("getVodInfo response for vodId=%d: %s", vodId, toJson(response));
        return Response.ok(response).build();
    }

    private boolean isValidStreamCredentials(String pathUsername, String pathPassword) {
        if (pathUsername == null || pathUsername.isBlank() || pathPassword == null || pathPassword.isBlank()) {
            log.warnf("Stream auth failed: missing credentials");
            return false;
        }
        return authService.authenticate(pathUsername, pathPassword).isPresent();
    }

    private Response unauthorizedStreamResponse() {
        java.util.Map<String, Object> authFail = new java.util.HashMap<>();
        java.util.Map<String, Object> userInfo = new java.util.HashMap<>();
        userInfo.put("auth", 0);
        authFail.put("user_info", userInfo);
        return Response.status(Response.Status.UNAUTHORIZED).entity(authFail).build();
    }

    private boolean isBlockedSsrfUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                log.warnf("SSRF guard: blocked non-http(s) URL: %s", url);
                return true;
            }
            String host = uri.getHost();
            if (host == null) {
                log.warnf("SSRF guard: blocked URL without host: %s", url);
                return true;
            }
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                    log.warnf("SSRF guard: blocked local/reserved address %s for host %s (url=%s)",
                            addr.getHostAddress(), host, url);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warnf("SSRF guard: couldn't resolve host, blocking url=%s: %s", url, e.getMessage());
            return true;
        }
    }

    private java.net.HttpURLConnection openValidatedConnection(String url) throws Exception {
        java.net.URL currentUrl = new java.net.URL(url);
        java.net.HttpURLConnection conn = null;
        for (int hop = 0; hop < 5; hop++) {
            if (isBlockedSsrfUrl(currentUrl.toString())) {
                throw new java.net.ConnectException("Blocked by SSRF guard: " + currentUrl);
            }
            conn = (java.net.HttpURLConnection) currentUrl.openConnection();
            conn.setRequestProperty("User-Agent", "JMedia/1.0");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.connect();
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = conn.getHeaderField("Location");
                if (location == null) return conn;
                conn.disconnect();
                currentUrl = new java.net.URL(currentUrl, location);
            } else {
                return conn;
            }
        }
        throw new java.net.ConnectException("Too many redirects: " + url);
    }

    private Response proxyExternalStream(String url, String pathUsername, String pathPassword) {
        try {
            java.net.HttpURLConnection conn = openValidatedConnection(url);

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            long contentLength = conn.getContentLengthLong();

            jakarta.ws.rs.core.Response.ResponseBuilder rb;
            if (status >= 400) {
                return Response.status(Response.Status.BAD_GATEWAY).build();
            }
            rb = Response.ok();

            if (contentType != null) rb.type(contentType);
            if (contentLength > 0) rb.header("Content-Length", contentLength);

            boolean isHls = (contentType != null && contentType.contains("mpegurl"))
                    || url.contains(".m3u8") || url.contains(".m3u");
            if (isHls) {
                String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                conn.disconnect();
                String baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
                String proxyBase = getExternalBaseUri() + "player_api.php/proxy/stream?url=";
                StringBuilder rewritten = new StringBuilder();
                for (String line : body.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        rewritten.append(line).append("\n");
                    } else if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP")) {
                        rewritten.append(rewriteHlsAssetLine(line, baseUrl, proxyBase, pathUsername, pathPassword)).append("\n");
                    } else if (trimmed.startsWith("#")) {
                        rewritten.append(line).append("\n");
                    } else {
                        String absoluteUrl = trimmed.startsWith("http") ? trimmed : baseUrl + trimmed;
                        rewritten.append(proxyBase).append(java.net.URLEncoder.encode(absoluteUrl, java.nio.charset.StandardCharsets.UTF_8))
                                .append("&username=").append(java.net.URLEncoder.encode(pathUsername != null ? pathUsername : "", java.nio.charset.StandardCharsets.UTF_8))
                                .append("&password=").append(java.net.URLEncoder.encode(pathPassword != null ? pathPassword : "", java.nio.charset.StandardCharsets.UTF_8))
                                .append("\n");
                    }
                }
                return Response.ok(rewritten.toString())
                        .type("application/vnd.apple.mpegurl")
                        .build();
            }

            final java.io.InputStream inputStream = conn.getInputStream();
            jakarta.ws.rs.core.StreamingOutput stream = out -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = inputStream.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {} finally {
                    try { inputStream.close(); } catch (Exception ignored) {}
                    conn.disconnect();
                }
            };
            rb.entity(stream);
            return rb.build();
        } catch (Exception e) {
            log.warnf("Proxy stream error: %s", e.getClass().getSimpleName());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Failed to connect to stream\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    private String rewriteHlsAssetLine(String line, String baseUrl, String proxyBase, String pathUsername, String pathPassword) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String uri = m.group(1);
            if (!uri.startsWith("http")) {
                try {
                    uri = new java.net.URL(new java.net.URL(baseUrl), uri).toString();
                } catch (Exception ignored) {
                }
            }
            String proxied = proxyBase
                    + java.net.URLEncoder.encode(uri, java.nio.charset.StandardCharsets.UTF_8)
                    + "&username=" + java.net.URLEncoder.encode(pathUsername != null ? pathUsername : "", java.nio.charset.StandardCharsets.UTF_8)
                    + "&password=" + java.net.URLEncoder.encode(pathPassword != null ? pathPassword : "", java.nio.charset.StandardCharsets.UTF_8);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("URI=\"" + proxied + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String getExternalBaseUri() {
        String host = uriInfo.getBaseUri().getHost();
        if (host != null && (host.equals("localhost") || host.equals("127.0.0.1"))) {
            return "http://" + System.getenv().getOrDefault("EXTERNAL_HOST", "localhost") + ":" + uriInfo.getBaseUri().getPort() + "/";
        }
        return uriInfo.getBaseUri().toString();
    }

    @GET
    @Path("/proxy/stream")
    public Response proxyStream(@QueryParam("url") String url) {
        if (url == null || url.isBlank()) return Response.status(Response.Status.BAD_REQUEST).build();
        if (!isValidStreamCredentials(username, password)) return unauthorizedStreamResponse();
        return proxyExternalStream(url, username, password);
    }

    private Response getSeriesInfo(String seriesId) {
        log.infof("getSeriesInfo: seriesId=%s, username=%s", seriesId, username);
        if (seriesId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        
        Models.Video.Series matchedSeries = null;
        for (Models.Video.Series sv : Models.Video.Series.<Models.Video.Series>listAll()) {
            if (hashId(sv.title).equals(seriesId)) {
                matchedSeries = sv;
                break;
            }
        }
        if (matchedSeries == null) {
            log.warnf("getSeriesInfo: no series match for seriesId=%s", seriesId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        List<Video> seriesEpisodes = Video.<Video>find("series = ?1 AND type = 'episode' ORDER BY seasonNumber, episodeNumber", matchedSeries).list();

        log.infof("getSeriesInfo: matched %d episodes for seriesId=%s", seriesEpisodes.size(), seriesId);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        Models.Video.Series s = matchedSeries;
        info.put("name", s.title);
        if (s.posterPath != null && !s.posterPath.isBlank()) {
            info.put("cover", "https://image.tmdb.org/t/p/w500" + s.posterPath);
            info.put("cover_big", "https://image.tmdb.org/t/p/w1280" + s.posterPath);
        } else if (!seriesEpisodes.isEmpty()) {
            Video first = seriesEpisodes.get(0);
            info.put("cover", getImageUrl(first));
            info.put("cover_big", getImageUrl(first));
        }
        String plotText = (s.overview != null && !s.overview.isBlank()) ? s.overview : "";
        info.put("plot", plotText);
        info.put("description", plotText);
        info.put("cast", (s.cast != null && !s.cast.isEmpty()) ? String.join(", ", s.cast) : "");
        info.put("director", (s.directors != null && !s.directors.isEmpty()) ? String.join(", ", s.directors) : "");
        List<String> seriesGenres = s.genres;
        info.put("genre", seriesGenres != null ? String.join(", ", seriesGenres) : "");
        info.put("releaseDate", s.releaseDate);
        Double seriesRating = s.tmdbRating;
        info.put("rating", seriesRating != null ? seriesRating.toString() : "0");
        info.put("rating_5based", seriesRating != null ? Math.ceil(seriesRating / 2.0) : 0.0);
        info.put("youtube_trailer", s.trailerUrl != null ? s.trailerUrl : "");
        info.put("episode_run_time", s.runtimeMins != null ? String.valueOf(s.runtimeMins) : "");
        if (s.backdropPath != null && !s.backdropPath.isBlank()) {
            info.put("backdrop_path", new ArrayList<>(List.of("https://image.tmdb.org/t/p/w1280" + s.backdropPath)));
        } else {
            info.put("backdrop_path", new ArrayList<>());
        }
        
        response.put("info", info);

        java.util.Set<Integer> seasonNumbers = seriesEpisodes.stream()
                .map(e -> e.seasonNumber != null ? e.seasonNumber : 1)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        java.util.Map<Integer, Long> episodeCountsBySeason = seriesEpisodes.stream()
                .collect(Collectors.groupingBy(e -> e.seasonNumber != null ? e.seasonNumber : 1, Collectors.counting()));
        List<java.util.Map<String, Object>> seasonsArray = new ArrayList<>();
        for (Integer sn : seasonNumbers) {
            java.util.Map<String, Object> seasonObj = new java.util.HashMap<>();
            seasonObj.put("season_number", sn);
            seasonObj.put("name", "Season " + sn);
            seasonObj.put("episode_count", episodeCountsBySeason.getOrDefault(sn, 0L).intValue());
            Video firstEp = seriesEpisodes.stream()
                    .filter(e -> (e.seasonNumber != null ? e.seasonNumber : 1) == sn)
                    .findFirst().orElse(null);
            if (firstEp != null) {
                seasonObj.put("cover", getImageUrl(firstEp));
                seasonObj.put("cover_big", getImageUrl(firstEp));
                seasonObj.put("air_date", firstEp.releaseDate != null ? firstEp.releaseDate : "");
                seasonObj.put("overview", firstEp.overview != null ? firstEp.overview : "");
            } else {
                seasonObj.put("cover", "");
                seasonObj.put("cover_big", "");
                seasonObj.put("air_date", "");
                seasonObj.put("overview", "");
            }
            seasonsArray.add(seasonObj);
        }
        response.put("seasons", seasonsArray);

        // Episodes grouped by season number as STRING key (required by IPTV players)
        java.util.Map<String, List<java.util.Map<String, Object>>> episodesBySeason = new java.util.LinkedHashMap<>();
        for (Video e : seriesEpisodes) {
            String seasonNum = String.valueOf(e.seasonNumber != null ? e.seasonNumber : 1);
            episodesBySeason.computeIfAbsent(seasonNum, k -> new ArrayList<>());
            
            java.util.Map<String, Object> ep = new java.util.HashMap<>();
            ep.put("id", e.id);
            ep.put("episode_num", e.episodeNumber);
            ep.put("title", e.title != null ? e.title : "Episode " + e.episodeNumber);
            ep.put("container_extension", e.container != null ? e.container : "mp4");
            ep.put("season", e.seasonNumber != null ? e.seasonNumber : 1);
            ep.put("custom_sid", "");
            ep.put("added", e.dateAdded != null ? String.valueOf(e.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            ep.put("direct_source", "");
            
            java.util.Map<String, Object> epInfo = new java.util.HashMap<>();
            epInfo.put("name", e.title != null ? e.title : "Episode " + e.episodeNumber);
            epInfo.put("movie_image", getImageUrl(e));
            epInfo.put("plot", e.overview != null ? e.overview : "");
            epInfo.put("rating", e.imdbRating != null ? e.imdbRating.toString() : "0");
            epInfo.put("rating_5based", e.imdbRating != null ? Math.ceil(e.imdbRating / 2.0) : 0.0);
            epInfo.put("releasedate", e.releaseDate != null ? e.releaseDate : "");
            epInfo.put("duration_secs", e.getDurationSeconds());
            epInfo.put("duration", formatDuration(e.getDurationSeconds()));
            ep.put("info", epInfo);
            
            episodesBySeason.get(seasonNum).add(ep);
        }
        
        response.put("episodes", episodesBySeason);
        log.infof("getSeriesInfo response for seriesId=%s: %s", seriesId, toJson(response));
        return Response.ok(response).build();
    }

    @Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    private Response loginResponse(User user, String password) {
        XtreamLoginResponse response = new XtreamLoginResponse();
        
        response.userInfo = new XtreamLoginResponse.UserInfo();
        response.userInfo.username = user.getUsername();
        response.userInfo.password = password;
        response.userInfo.message = "Welcome to JMedia";
        response.userInfo.auth = 1;
        response.userInfo.status = "Active";
        response.userInfo.expDate = String.valueOf(4102444800L);
        response.userInfo.isTrial = "0";
        response.userInfo.activeCons = "0";
        response.userInfo.createdAt = String.valueOf(System.currentTimeMillis() / 1000);
        response.userInfo.maxConnections = "5";
        response.userInfo.allowedOutputFormats = List.of("ts", "m3u8");

        response.serverInfo = new XtreamLoginResponse.ServerInfo();
        response.serverInfo.url = uriInfo.getBaseUri().getHost();
        response.serverInfo.port = String.valueOf(uriInfo.getBaseUri().getPort());
        response.serverInfo.httpsPort = "443";
        response.serverInfo.serverProtocol = uriInfo.getBaseUri().getScheme();
        response.serverInfo.rtmpPort = "";
        response.serverInfo.timezone = "UTC";
        response.serverInfo.timestampNow = System.currentTimeMillis() / 1000;
        response.serverInfo.timeNow = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        response.serverInfo.process = true;
        
        return Response.ok(response).build();
    }

    private Response getVodCategories() {
        // Map genres to categories
        List<Models.Video.Genre> genres = Models.Video.Genre.list("isActive = true");
        List<XtreamCategory> categories = genres.stream()
                .map(g -> new XtreamCategory(g.id.toString(), g.name))
                .collect(Collectors.toList());
        return Response.ok(categories).build();
    }

    private java.util.Map<String, String> genreIdByName() {
        List<Models.Video.Genre> genres = Models.Video.Genre.list("isActive = true");
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (Models.Video.Genre g : genres) {
            map.put(g.name.toLowerCase(), g.id.toString());
        }
        return map;
    }

    private java.util.Map<String, String> genreIdByNameReverse() {
        List<Models.Video.Genre> genres = Models.Video.Genre.list("isActive = true");
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (Models.Video.Genre g : genres) {
            map.put(g.id.toString(), g.name);
        }
        return map;
    }

    private Response getVodStreams(String catId) {
        List<Video> videos;
        if (catId != null && !catId.equals("0")) {
            Long genreId = null;
            try {
                genreId = Long.parseLong(catId);
            } catch (NumberFormatException e) {
            }
            if (genreId != null) {
                videos = Video.<Video>find("SELECT DISTINCT v FROM Video v JOIN VideoGenre vg ON v.id = vg.video.id WHERE vg.genre.id = ?1 AND v.type = 'movie' ORDER BY v.popularityScore DESC", genreId).list();
            } else {
                videos = Video.<Video>find("type = 'movie'").list();
            }
        } else {
            videos = Video.<Video>find("type = 'movie'").list();
        }

        java.util.Map<String, String> genreIds = genreIdByName();
        List<XtreamVodStream> streams = new ArrayList<>();
        int num = 1;
        for (Video v : videos) {
            XtreamVodStream s = new XtreamVodStream();
            s.num = num++;
            s.name = v.title;
            s.streamId = v.id;
            s.streamIcon = getImageUrl(v);
            s.rating = v.imdbRating != null ? v.imdbRating.toString() : "0";
            s.rating5based = v.imdbRating != null ? Math.ceil(v.imdbRating / 2.0) : 0;
            s.added = v.dateAdded != null ? String.valueOf(v.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0";
            s.containerExtension = v.container != null ? v.container : "mp4";
            s.streamType = "movie";
            // Map genre IDs
            if (v.genres != null && !v.genres.isEmpty()) {
                s.categoryId = genreIds.getOrDefault(v.genres.get(0).toLowerCase(), "0");
                for (String g : v.genres) {
                    String gid = genreIds.getOrDefault(g.toLowerCase(), "0");
                    if (!"0".equals(gid)) s.categoryIds.add(Integer.parseInt(gid));
                }
            } else {
                s.categoryId = "0";
            }
            streams.add(s);
        }
        log.infof("getVodStreams returning %d streams, sample: %s", streams.size(), streams.isEmpty() ? "empty" : toJson(streams.subList(0, Math.min(3, streams.size()))));
        return Response.ok(streams).build();
    }

    private Response getSeriesCategories() {
        List<XtreamCategory> categories = new ArrayList<>();
        List<Models.Video.Genre> genres = Models.Video.Genre.list("isActive = true");
        for (Models.Video.Genre g : genres) {
            categories.add(new XtreamCategory(g.id.toString(), g.name));
        }
        return Response.ok(categories).build();
    }

    private Response getLiveCategories() {
        List<XtreamCategory> categories = new ArrayList<>();

        List<Models.Video.M3uPlaylist> playlists = Models.Video.M3uPlaylist.list("isActive = true");
        for (Models.Video.M3uPlaylist pl : playlists) {
            String name = pl.name != null ? pl.name : "Unknown Playlist";
            categories.add(new XtreamCategory(String.valueOf(pl.id), name));
        }

        for (Models.Video.M3uPlaylist pl : playlists) {
            String parentCategoryId = String.valueOf(pl.id);
            List<LiveChannel> channels = LiveChannel.find("playlist.id = ?1", pl.id).list();
            java.util.Set<String> seenGroups = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (LiveChannel ch : channels) {
                String group = ch.groupTitle != null && !ch.groupTitle.isBlank() ? ch.groupTitle : null;
                if (group != null && seenGroups.add(group)) {
                    String groupCatId = "g_" + pl.id + "_" + hashId(group);
                    XtreamCategory groupCat = new XtreamCategory(groupCatId, group);
                    groupCat.parentId = Integer.parseInt(parentCategoryId);
                    categories.add(groupCat);
                }
            }
        }

        return Response.ok(categories).build();
    }

    private Response getLiveStreams(String catId) {
        List<LiveChannel> channels;
        String effectiveCategoryId = "0";
        if (catId != null && !catId.equals("0")) {
            if (catId.startsWith("g_")) {
                effectiveCategoryId = catId;
                int firstUnderscore = catId.indexOf('_');
                int secondUnderscore = catId.indexOf('_', firstUnderscore + 1);
                Long parsedPlaylistId = null;
                String parsedGroupHash = null;
                if (secondUnderscore > 0) {
                    try {
                        parsedPlaylistId = Long.parseLong(catId.substring(firstUnderscore + 1, secondUnderscore));
                        parsedGroupHash = catId.substring(secondUnderscore + 1);
                    } catch (NumberFormatException ignored) {
                    }
                }
                final Long playlistId = parsedPlaylistId;
                final String groupHash = parsedGroupHash;
                if (playlistId != null && groupHash != null && !groupHash.isBlank()) {
                    List<LiveChannel> playlistChannels = LiveChannel.find("playlist.id = ?1", playlistId).list();
                    channels = playlistChannels.stream()
                            .filter(ch -> ch.groupTitle != null && hashId(ch.groupTitle).equals(groupHash))
                            .collect(Collectors.toList());
                } else {
                    channels = LiveChannel.listAll();
                }
            } else {
                try {
                    Long playlistId = Long.parseLong(catId);
                    effectiveCategoryId = catId;
                    channels = LiveChannel.find("playlist.id = ?1", playlistId).list();
                } catch (NumberFormatException e) {
                    channels = LiveChannel.listAll();
                }
            }
        } else {
            channels = LiveChannel.listAll();
        }

        List<java.util.Map<String, Object>> streams = new ArrayList<>();
        int num = 1;
        for (LiveChannel ch : channels) {
            if ("dead".equals(ch.streamStatus)) continue;
            java.util.Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("num", num++);
            s.put("name", ch.name);
            s.put("stream_type", "live");
            s.put("stream_id", ch.id);
            s.put("stream_icon", getLiveChannelLogo(ch));
            s.put("epg_channel_id", ch.tvgId != null ? ch.tvgId : "");
            s.put("added", ch.createdAt != null ? String.valueOf(ch.createdAt.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            s.put("is_adult", "0");
            // Report the same category id space that get_live_categories issues
            // (playlist id, or g_<playlist>_<hash> when grouped): video-genre ids
            // made client-side live filtering match nothing.
            String channelCategoryId;
            if (ch.playlist != null && ch.groupTitle != null && !ch.groupTitle.isBlank()) {
                channelCategoryId = "g_" + ch.playlist.id + "_" + hashId(ch.groupTitle);
            } else if (ch.playlist != null) {
                channelCategoryId = String.valueOf(ch.playlist.id);
            } else {
                channelCategoryId = "0";
            }
            if ("0".equals(effectiveCategoryId)) {
                s.put("category_id", channelCategoryId);
                s.put("category_ids", new ArrayList<>(List.of(channelCategoryId)));
            } else {
                s.put("category_id", effectiveCategoryId);
                s.put("category_ids", new ArrayList<>(List.of(effectiveCategoryId)));
            }
            s.put("custom_sid", "");
            s.put("tv_archive", 0);
            s.put("direct_source", "");
            s.put("tv_archive_duration", 0);
            streams.add(s);
        }
        return Response.ok(streams).build();
    }

    private Response getLiveStreamInfo(Long streamId) {
        if (streamId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        LiveChannel ch = LiveChannel.findById(streamId);
        if (ch == null) return Response.status(Response.Status.NOT_FOUND).build();

        String categoryId;
        if (ch.playlist != null && ch.groupTitle != null && !ch.groupTitle.isBlank()) {
            categoryId = "g_" + ch.playlist.id + "_" + hashId(ch.groupTitle);
        } else {
            categoryId = ch.playlist != null ? String.valueOf(ch.playlist.id) : "0";
        }

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", ch.name);
        info.put("stream_icon", getLiveChannelLogo(ch));
        info.put("epg_channel_id", ch.tvgId != null ? ch.tvgId : "");
        info.put("category", ch.playlist != null ? ch.playlist.name : "");
        info.put("tv_archive", 0);
        info.put("direct_source", "");
        info.put("custom_sid", "");
        response.put("info", info);

        java.util.Map<String, Object> channel = new java.util.LinkedHashMap<>();
        channel.put("num", 1);
        channel.put("name", ch.name);
        channel.put("stream_type", "live");
        channel.put("stream_id", ch.id);
        channel.put("stream_icon", getLiveChannelLogo(ch));
        channel.put("epg_channel_id", ch.tvgId != null ? ch.tvgId : "");
        channel.put("category_id", categoryId);
        channel.put("category_ids", new ArrayList<>(List.of(categoryId)));
        response.put("channel", channel);

        return Response.ok(response).build();
    }

    private Response getShortEpg(Long streamId) {
        if (streamId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        LiveChannel ch = LiveChannel.findById(streamId);
        if (ch == null) return Response.status(Response.Status.NOT_FOUND).build();
        
        String epgId = ch.tvgId;
        if (epgId == null || epgId.isBlank()) {
            return Response.ok(java.util.Map.of("epg_listings", new ArrayList<>())).build();
        }
        
        int limit = (epgLimit != null && epgLimit > 0) ? epgLimit : 4;
        List<Models.Video.EpgEntry> entries = epgService.findCurrentAndUpcoming(epgId, limit);
        
        return Response.ok(buildEpgResponse(entries, streamId, false)).build();
    }

    private Response getFullEpg(Long streamId) {
        if (streamId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        LiveChannel ch = LiveChannel.findById(streamId);
        if (ch == null) return Response.status(Response.Status.NOT_FOUND).build();
        
        String epgId = ch.tvgId;
        if (epgId == null || epgId.isBlank()) {
            return Response.ok(java.util.Map.of("epg_listings", new ArrayList<>())).build();
        }
        
        List<Models.Video.EpgEntry> entries = epgService.findAllForChannel(epgId);
        return Response.ok(buildEpgResponse(entries, streamId, true)).build();
    }

    private java.util.Map<String, Object> buildEpgResponse(List<Models.Video.EpgEntry> entries, Long streamId, boolean setNowPlaying) {
        java.time.format.DateTimeFormatter dtFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.util.List<java.util.Map<String, Object>> epgList = new java.util.ArrayList<>();
        int i = 0;
        for (Models.Video.EpgEntry entry : entries) {
            i++;
            java.util.Map<String, Object> epg = new java.util.HashMap<>();
            epg.put("id", String.valueOf(entry.id));
            epg.put("epg_id", entry.epgChannelId != null ? entry.epgChannelId : "");
            epg.put("title", base64Encode(entry.title));
            epg.put("lang", entry.language != null ? entry.language : "en");
            epg.put("start", entry.startTime != null ? entry.startTime.format(dtFmt) : "");
            epg.put("end", entry.endTime != null ? entry.endTime.format(dtFmt) : "");
            epg.put("description", base64Encode(entry.description));
            epg.put("channel_id", entry.epgChannelId != null ? entry.epgChannelId : "");
            epg.put("stream_id", String.valueOf(streamId));
            epg.put("start_timestamp", entry.startTime != null ? String.valueOf(entry.startTime.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            epg.put("stop_timestamp", entry.endTime != null ? String.valueOf(entry.endTime.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            if (setNowPlaying && entry.startTime != null && entry.endTime != null) {
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                epg.put("now_playing", (!now.isBefore(entry.startTime) && now.isBefore(entry.endTime)) ? 1 : 0);
            } else {
                epg.put("now_playing", 0);
            }
            epg.put("has_archive", 0);
            epgList.add(epg);
        }
        java.util.Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
        wrapper.put("epg_listings", epgList);
        return wrapper;
    }

    private String base64Encode(String value) {
        if (value == null || value.isEmpty()) return "";
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Response getSeries(String catId) {
        log.infof("getSeries: catId=%s", catId);

        List<Models.Video.Series> allSeries;
        if (catId != null && !catId.isBlank()) {
            String genreName = genreIdByNameReverse().get(catId);
            if (genreName != null) {
                allSeries = Models.Video.Series.<Models.Video.Series>find("SELECT s FROM Series s JOIN s.genres g WHERE LOWER(g) LIKE ?1", "%" + genreName.toLowerCase() + "%").list();
            } else {
                allSeries = Models.Video.Series.<Models.Video.Series>listAll();
            }
        } else {
            allSeries = Models.Video.Series.<Models.Video.Series>listAll();
        }

        log.infof("getSeries: found %d series from Series entity", allSeries.size());
        java.util.Map<String, String> genreIds = genreIdByName();
        List<XtreamSeries> seriesList = new ArrayList<>();
        int num = 1;
        for (Models.Video.Series ser : allSeries) {
            XtreamSeries xs = new XtreamSeries();
            xs.num = num++;
            xs.name = ser.title;
            xs.seriesId = hashId(ser.title);

            // Cover: prefer series poster, fall back to first episode poster
            if (ser.posterPath != null && !ser.posterPath.isBlank()) {
                xs.cover = "https://image.tmdb.org/t/p/w500" + ser.posterPath;
                xs.coverBig = "https://image.tmdb.org/t/p/w1280" + ser.posterPath;
            } else {
                Video firstEp = Video.<Video>find("series = ?1 AND type = 'episode' ORDER BY id", ser).firstResult();
                xs.cover = firstEp != null ? getImageUrl(firstEp) : "";
                xs.coverBig = xs.cover;
            }

            xs.plot = (ser.overview != null && !ser.overview.isBlank()) ? ser.overview : "";
            Double seriesRating = ser.tmdbRating;
            xs.rating = seriesRating != null ? seriesRating.toString() : "0";
            xs.rating5based = seriesRating != null ? Math.ceil(seriesRating / 2.0) : 0;
            xs.releaseDate = ser.releaseDate;
            xs.lastModified = String.valueOf(System.currentTimeMillis() / 1000);
            List<String> seriesGenres = ser.genres;
            if (seriesGenres != null && !seriesGenres.isEmpty()) {
                xs.categoryId = genreIds.getOrDefault(seriesGenres.get(0).toLowerCase(), "0");
                for (String g : seriesGenres) {
                    String gid = genreIds.getOrDefault(g.toLowerCase(), "0");
                    if (!"0".equals(gid)) xs.categoryIds.add(Integer.parseInt(gid));
                }
            } else {
                xs.categoryId = "0";
            }
            xs.year = ser.releaseDate != null ? ser.releaseDate : "";
            xs.cast = (ser.cast != null && !ser.cast.isEmpty())
                ? String.join(", ", ser.cast) : "";
            xs.director = (ser.directors != null && !ser.directors.isEmpty())
                ? String.join(", ", ser.directors) : "";
            xs.genre = seriesGenres != null ? String.join(", ", seriesGenres) : "";
            xs.youtubeTrailer = ser.trailerUrl != null ? ser.trailerUrl : "";
            if (ser.backdropPath != null && !ser.backdropPath.isBlank()) {
                xs.backdropPath = new ArrayList<>(List.of("https://image.tmdb.org/t/p/w1280" + ser.backdropPath));
            }
            seriesList.add(xs);
        }
        log.infof("getSeries: returning %d series", seriesList.size());
        return Response.ok(seriesList).build();
    }

    private String getImageUrl(Video v) {
        if (v.tmdbId != null && !v.tmdbId.isEmpty() && v.posterPath != null && !v.posterPath.isEmpty()) {
            String url = "https://image.tmdb.org/t/p/w500" + v.posterPath;
            log.debugf("getImageUrl: video=%d, using TMDB poster: %s", v.id, url);
            return url;
        }
        String url = getExternalBaseUri() + "player_api.php?action=get_thumbnail&vod_id=" + v.id + "&username=" + username + "&password=" + password;
        log.debugf("getImageUrl: video=%d, tmdbId=%s, posterPath=%s, thumbnail URL: %s", v.id, v.tmdbId, v.posterPath, url);
        return url;
    }

    private String getLiveChannelLogo(LiveChannel ch) {
        if (ch.logoUrl != null && !ch.logoUrl.isBlank()) {
            if (ch.logoUrl.startsWith("http")) {
                return ch.logoUrl;
            }
            return getExternalBaseUri() + "api/video/thumbnail/live/" + ch.id;
        }
        return "";
    }

    private static String hashId(String value) {
        if (value == null) return "";
        return java.util.UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String redactQuery(String rawQuery) {
        if (rawQuery == null) return null;
        return rawQuery.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    private Response getThumbnail(Long videoId) {
        if (videoId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Video v = Video.findById(videoId);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();

        if (thumbnailService.hasThumbnail(videoId)) {
            byte[] img = thumbnailService.getThumbnailBytes(videoId);
            return Response.ok(img).type("image/jpeg").build();
        }
        log.warnf("getThumbnail: no thumbnail for videoId=%d, serving fallback", videoId);
        return Response.temporaryRedirect(java.net.URI.create("https://placehold.co/300x450/1a1a2e/eaeaea?text=No+Image"))
                .build();
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) return "0";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    private int parseWidth(String resolution) {
        if (resolution == null || resolution.isEmpty()) return 0;
        try {
            String[] parts = resolution.split("x");
            return parts.length == 2 ? Integer.parseInt(parts[0].trim()) : 0;
        } catch (NumberFormatException e) {
            log.warnf("parseWidth: malformed resolution '%s'", resolution);
            return 0;
        }
    }

    private int parseHeight(String resolution) {
        if (resolution == null || resolution.isEmpty()) return 0;
        try {
            String[] parts = resolution.split("x");
            return parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 0;
        } catch (NumberFormatException e) {
            log.warnf("parseHeight: malformed resolution '%s'", resolution);
            return 0;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectWriter jsonWriter = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .writerWithDefaultPrettyPrinter();

    private String toJson(Object obj) {
        try {
            return jsonWriter.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"jsonError\":\"" + e.getMessage() + "\"}";
        }
    }
}
