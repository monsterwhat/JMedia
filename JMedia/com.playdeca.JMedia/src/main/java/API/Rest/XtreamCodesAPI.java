package API.Rest;

import Models.User;
import Models.Video;
import Models.LiveChannel;
import Models.M3uPlaylist;
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
        // Also dump raw query string to catch any params we don't have @QueryParam for
        try {
            String rawQuery = uriInfo.getRequestUri().getQuery();
            log.infof("Xtream request: %s, rawQuery=%s", allParams, rawQuery);
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
            case "get_epg":
            case "get_short_epg":
                return getShortEpg(liveStreamId);
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
        info.put("rating_5based", v.imdbRating != null ? Math.ceil(v.imdbRating / 2.0) : 0);
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
        
        String vodCategoryId = "0";
        if (v.genres != null && !v.genres.isEmpty()) {
            Models.Genre g = Models.Genre.find("LOWER(name) = ?1", v.genres.get(0).toLowerCase()).firstResult();
            if (g != null) vodCategoryId = g.id.toString();
        }

        java.util.Map<String, Object> movieData = new java.util.HashMap<>();
        movieData.put("stream_id", v.id);
        movieData.put("name", v.title);
        movieData.put("added", v.dateAdded != null ? String.valueOf(v.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
        movieData.put("category_id", vodCategoryId);
        movieData.put("container_extension", "mp4");
        movieData.put("custom_sid", "");
        movieData.put("direct_source", "");
        
        response.put("movie_data", movieData);
        
        log.infof("getVodInfo response for vodId=%d: %s", vodId, toJson(response));
        return Response.ok(response).build();
    }

    @GET
    @Path("/movie/{username}/{password}/{videoId}.{ext}")
    public Response streamMovie(@PathParam("videoId") Long videoId, @PathParam("ext") String ext,
                                @HeaderParam("Range") String rangeHeader) {
        log.infof("Stream movie request: videoId=%d, ext=%s, range=%s", videoId, ext, rangeHeader);
        Video video = Video.findById(videoId);
        if (video == null) { log.warnf("Movie not found: videoId=%d", videoId); return Response.status(Response.Status.NOT_FOUND).build(); }
        return proxyLocalVideo(video, ext, rangeHeader);
    }

    @GET
    @Path("/series/{username}/{password}/{videoId}.{ext}")
    public Response streamSeries(@PathParam("videoId") Long videoId, @PathParam("ext") String ext,
                                 @HeaderParam("Range") String rangeHeader) {
        log.infof("Stream series request: videoId=%d, ext=%s, range=%s", videoId, ext, rangeHeader);
        Video video = Video.findById(videoId);
        if (video == null) { log.warnf("Series episode not found: videoId=%d", videoId); return Response.status(Response.Status.NOT_FOUND).build(); }
        return proxyLocalVideo(video, ext, rangeHeader);
    }

    @GET
    @Path("/{username}/{password}/{channelId}.m3u8")
    public Response streamLive(@PathParam("channelId") Long channelId) {
        log.infof("Stream live request: channelId=%d", channelId);
        LiveChannel ch = LiveChannel.findById(channelId);
        if (ch == null || ch.streamUrl == null || ch.streamUrl.isBlank()) {
            log.warnf("Live channel not found or no URL: channelId=%d", channelId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        log.infof("Proxying live stream: channelId=%d, url=%s", channelId, ch.streamUrl);
        return proxyExternalStream(ch.streamUrl);
    }

    private Response proxyLocalVideo(Video video, String ext, String rangeHeader) {
        String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        java.nio.file.Path baseFilePath = java.nio.file.Paths.get(video.path);
        java.nio.file.Path filePath = baseFilePath.isAbsolute()
                ? baseFilePath : java.nio.file.Paths.get(libraryPath, video.path);

        File videoFile = filePath.toFile();
        if (!videoFile.exists() || !videoFile.isFile()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long fileLength = videoFile.length();
        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String rangeValue = rangeHeader.substring(6).trim();
                if (rangeValue.startsWith("-")) {
                    long suffix = Long.parseLong(rangeValue.substring(1));
                    start = Math.max(0, fileLength - suffix);
                    end = fileLength - 1;
                } else {
                    String[] parts = rangeValue.split("-", -1);
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        end = Long.parseLong(parts[1].trim());
                    } else {
                        end = fileLength - 1;
                    }
                }
                if (end >= fileLength) end = fileLength - 1;
                if (start > end) { start = 0; end = fileLength - 1; }
            } catch (Exception e) {
                start = 0;
                end = fileLength - 1;
            }
        }

        if (start >= fileLength) {
            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + fileLength)
                    .build();
        }

        long contentLength = end - start + 1;
        final long finalStart = start;
        final long finalContentLength = contentLength;

        String contentType = "video/mp4";
        if ("mkv".equalsIgnoreCase(ext)) contentType = "video/x-matroska";
        else if ("avi".equalsIgnoreCase(ext)) contentType = "video/x-msvideo";

        final String ct = contentType;
        jakarta.ws.rs.core.StreamingOutput stream = out -> {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(videoFile, "r")) {
                raf.seek(finalStart);
                byte[] buf = new byte[65536];
                long remaining = finalContentLength;
                while (remaining > 0) {
                    int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (read == -1) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
            }
        };

        Response.ResponseBuilder rb = Response.status(rangeHeader != null ? Response.Status.PARTIAL_CONTENT : Response.Status.OK)
                .entity(stream)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", ct)
                .header("Content-Length", contentLength)
                .header("Access-Control-Allow-Origin", "*");

        if (rangeHeader != null) {
            rb.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        }

        return rb.build();
    }

    private Response proxyExternalStream(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "JMedia/1.0");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.connect();

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
            rb.header("Access-Control-Allow-Origin", "*");

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
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Failed to connect to stream: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    @GET
    @Path("/proxy/stream")
    public Response proxyStream(@QueryParam("url") String url) {
        if (url == null || url.isBlank()) return Response.status(Response.Status.BAD_REQUEST).build();
        return proxyExternalStream(url);
    }

    private Response getSeriesInfo(String seriesId) {
        log.infof("getSeriesInfo: seriesId=%s, username=%s", seriesId, username);
        if (seriesId == null) return Response.status(Response.Status.BAD_REQUEST).build();
        
        List<Video> episodes = Video.find("type = 'episode'").list();
        log.infof("getSeriesInfo: total episodes in DB=%d", episodes.size());
        log.infof("getSeriesInfo: episode seriesTitles sample (first 10): %s",
                episodes.stream().filter(e -> e.seriesTitle != null).map(e -> e.seriesTitle).distinct().limit(10).collect(java.util.stream.Collectors.joining(", ")));
        log.infof("getSeriesInfo: episode seriesTitle hashCodes (first 10): %s",
                episodes.stream().filter(e -> e.seriesTitle != null).map(e -> e.seriesTitle).distinct().map(t -> t + "=" + t.hashCode() + "/abs=" + Math.abs(t.hashCode())).limit(10).collect(java.util.stream.Collectors.joining(", ")));
        List<Video> seriesEpisodes = new ArrayList<Video>(episodes).stream()
                .filter(e -> e.seriesTitle != null && (String.valueOf(e.seriesTitle.hashCode()).equals(seriesId) || String.valueOf(Math.abs(e.seriesTitle.hashCode())).equals(seriesId)))
                .sorted(java.util.Comparator.comparing((Video e) -> e.seasonNumber != null ? e.seasonNumber : 0, java.util.Comparator.naturalOrder())
                        .thenComparing((Video e) -> e.episodeNumber != null ? e.episodeNumber : 0, java.util.Comparator.naturalOrder()))
                .collect(Collectors.toList());

        log.infof("getSeriesInfo: matched %d episodes for seriesId=%s", seriesEpisodes.size(), seriesId);
        if (seriesEpisodes.isEmpty()) {
            log.warnf("getSeriesInfo: no episodes found for seriesId=%s, dumping hash table:", seriesId);
            episodes.stream().filter(e -> e.seriesTitle != null).collect(Collectors.groupingBy(e -> e.seriesTitle))
                    .forEach((title, eps) -> log.warnf("  title='%s', hashCode=%d, absHash=%d, episodeCount=%d",
                            title, title.hashCode(), Math.abs(title.hashCode()), eps.size()));
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // Construct complex Xtream response for series info
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        Video first = seriesEpisodes.get(0);
        info.put("name", first.seriesTitle);
        info.put("cover", getImageUrl(first));
        info.put("cover_big", getImageUrl(first));
        info.put("plot", first.overview != null ? first.overview : "");
        info.put("cast", first.cast != null ? String.join(", ", first.cast) : "");
        info.put("director", first.directors != null ? String.join(", ", first.directors) : "");
        List<String> seriesGenres = (first.series != null && first.series.genres != null && !first.series.genres.isEmpty())
            ? first.series.genres : first.genres;
        info.put("genre", seriesGenres != null ? String.join(", ", seriesGenres) : "");
        info.put("releaseDate", first.releaseDate);
        info.put("rating", first.imdbRating != null ? first.imdbRating.toString() : "0");
        info.put("rating_5based", first.imdbRating != null ? Math.ceil(first.imdbRating / 2.0) : 0);
        info.put("youtube_trailer", "");
        info.put("episode_run_time", first.runtimeMins != null ? String.valueOf(first.runtimeMins) : "");
        info.put("backdrop_path", new ArrayList<>());
        
        response.put("info", info);

        java.util.Set<Integer> seasonNumbers = seriesEpisodes.stream()
                .map(e -> e.seasonNumber != null ? e.seasonNumber : 1)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        List<java.util.Map<String, Object>> seasonsArray = new ArrayList<>();
        for (Integer sn : seasonNumbers) {
            java.util.Map<String, Object> seasonObj = new java.util.HashMap<>();
            seasonObj.put("season_number", sn);
            seasonObj.put("name", "Season " + sn);
            seasonsArray.add(seasonObj);
        }
        response.put("seasons", seasonsArray);

        // Episodes grouped by season number as STRING key (required by IPTV players)
        java.util.Map<String, List<java.util.Map<String, Object>>> episodesBySeason = new java.util.LinkedHashMap<>();
        for (Video e : seriesEpisodes) {
            String seasonNum = String.valueOf(e.seasonNumber != null ? e.seasonNumber : 1);
            episodesBySeason.computeIfAbsent(seasonNum, k -> new ArrayList<>());
            
            java.util.Map<String, Object> ep = new java.util.HashMap<>();
            ep.put("id", String.valueOf(e.id));
            ep.put("episode_num", e.episodeNumber);
            ep.put("title", e.title != null ? e.title : "Episode " + e.episodeNumber);
            ep.put("container_extension", "mp4");
            ep.put("season", e.seasonNumber != null ? e.seasonNumber : 1);
            ep.put("custom_sid", "");
            ep.put("added", e.dateAdded != null ? String.valueOf(e.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            ep.put("direct_source", "");
            
            java.util.Map<String, Object> epInfo = new java.util.HashMap<>();
            epInfo.put("movie_image", getImageUrl(e));
            epInfo.put("plot", e.overview != null ? e.overview : "");
            epInfo.put("rating", e.imdbRating != null ? e.imdbRating.toString() : "0");
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
        response.userInfo.expDate = 4102444800L;
        response.userInfo.isTrial = 0;
        response.userInfo.activeCons = 0;
        response.userInfo.createdAt = System.currentTimeMillis() / 1000;
        response.userInfo.maxConnections = 5;
        response.userInfo.allowedOutputFormats = List.of("mp4", "mkv", "m3u8");

        response.serverInfo = new XtreamLoginResponse.ServerInfo();
        response.serverInfo.url = uriInfo.getBaseUri().getHost();
        response.serverInfo.port = String.valueOf(uriInfo.getBaseUri().getPort());
        response.serverInfo.httpsPort = "443";
        response.serverInfo.serverProtocol = uriInfo.getBaseUri().getScheme();
        response.serverInfo.rtmpPort = "";
        response.serverInfo.timezone = "UTC";
        response.serverInfo.timestampNow = System.currentTimeMillis() / 1000;
        response.serverInfo.timeNow = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        
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
            s.streamIcon = getImageUrl(v);
            s.rating = v.imdbRating != null ? v.imdbRating.toString() : "0";
            s.rating5based = v.imdbRating != null ? Math.ceil(v.imdbRating / 2.0) : 0;
            s.added = v.dateAdded != null ? String.valueOf(v.dateAdded.toEpochSecond(java.time.ZoneOffset.UTC)) : "0";
            s.containerExtension = "mp4";
            // Map the first genre ID if available
            if (v.genres != null && !v.genres.isEmpty()) {
                Models.Genre g = Models.Genre.find("LOWER(name) = ?1", v.genres.get(0).toLowerCase()).firstResult();
                s.categoryId = g != null ? g.id.toString() : "0";
            } else {
                s.categoryId = "0";
            }
            streams.add(s);
        }
        log.infof("getVodStreams returning %d streams, sample: %s", streams.size(), streams.isEmpty() ? "empty" : toJson(streams.subList(0, Math.min(3, streams.size()))));
        return Response.ok(streams).build();
    }

    private Response getSeriesCategories() {
        return getVodCategories();
    }

    private Response getLiveCategories() {
        List<XtreamCategory> categories = new ArrayList<>();

        List<Models.M3uPlaylist> playlists = Models.M3uPlaylist.list("isActive = true");
        for (Models.M3uPlaylist pl : playlists) {
            String name = pl.name != null ? pl.name : "Unknown Playlist";
            categories.add(new XtreamCategory(String.valueOf(pl.id), name));
        }

        for (Models.M3uPlaylist pl : playlists) {
            String parentCategoryId = String.valueOf(pl.id);
            List<LiveChannel> channels = LiveChannel.find("playlist.id = ?1", pl.id).list();
            java.util.Set<String> seenGroups = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (LiveChannel ch : channels) {
                String group = ch.groupTitle != null && !ch.groupTitle.isBlank() ? ch.groupTitle : null;
                if (group != null && seenGroups.add(group)) {
                    String groupCatId = "g_" + pl.id + "_" + group.hashCode();
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
        if (catId != null && !catId.equals("0")) {
            if (catId.startsWith("g_")) {
                int firstUnderscore = catId.indexOf('_');
                int secondUnderscore = catId.indexOf('_', firstUnderscore + 1);
                if (secondUnderscore > 0) {
                    Long playlistId = Long.parseLong(catId.substring(firstUnderscore + 1, secondUnderscore));
                    String groupHash = catId.substring(secondUnderscore + 1);
                    List<LiveChannel> playlistChannels = LiveChannel.find("playlist.id = ?1", playlistId).list();
                    channels = playlistChannels.stream()
                            .filter(ch -> ch.groupTitle != null && String.valueOf(ch.groupTitle.hashCode()).equals(groupHash))
                            .collect(Collectors.toList());
                } else {
                    channels = LiveChannel.listAll();
                }
            } else {
                try {
                    Long playlistId = Long.parseLong(catId);
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
            s.put("stream_icon", ch.logoUrl != null ? ch.logoUrl : "");
            s.put("epg_channel_id", ch.tvgId != null ? ch.tvgId : "");
            s.put("added", ch.createdAt != null ? String.valueOf(ch.createdAt.toEpochSecond(java.time.ZoneOffset.UTC)) : "0");
            s.put("is_adult", "0");
            s.put("category_id", ch.playlist != null ? String.valueOf(ch.playlist.id) : "0");
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

        String categoryId = ch.playlist != null ? String.valueOf(ch.playlist.id) : "0";

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", ch.name);
        info.put("stream_icon", ch.logoUrl != null ? ch.logoUrl : "");
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
        channel.put("stream_icon", ch.logoUrl != null ? ch.logoUrl : "");
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
        List<Models.EpgEntry> entries = epgService.findUpcoming(epgId, limit);
        if (entries.isEmpty()) {
            entries = epgService.findCurrentPrograms().stream()
                .filter(e -> epgId.equals(e.epgChannelId))
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
        }
        
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
        
        List<Models.EpgEntry> entries = epgService.findAllForChannel(epgId);
        return Response.ok(buildEpgResponse(entries, streamId, true)).build();
    }

    private java.util.Map<String, Object> buildEpgResponse(List<Models.EpgEntry> entries, Long streamId, boolean setNowPlaying) {
        java.time.format.DateTimeFormatter dtFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.util.List<java.util.Map<String, Object>> epgList = new java.util.ArrayList<>();
        int i = 0;
        for (Models.EpgEntry entry : entries) {
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
            epg.put("now_playing", (setNowPlaying && i == 1) ? 1 : 0);
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
        List<Video> episodes = Video.find("type = 'episode'").list();
        java.util.Map<String, List<Video>> seriesGroups = episodes.stream()
                .filter(e -> e.seriesTitle != null)
                .collect(Collectors.groupingBy(e -> e.seriesTitle));

        log.infof("getSeries: found %d episode groups from %d total episodes", seriesGroups.size(), episodes.size());
        List<XtreamSeries> seriesList = new ArrayList<>();
        int num = 1;
        for (java.util.Map.Entry<String, List<Video>> entry : seriesGroups.entrySet()) {
            Video first = entry.getValue().get(0);
            String imageUrl = getImageUrl(first);
            log.infof("getSeries: series=%s, seriesId=%s, episodes=%d, tmdbId=%s, posterPath=%s, imageUrl=%s",
                    entry.getKey(), Math.abs(entry.getKey().hashCode()), entry.getValue().size(),
                    first.tmdbId, first.posterPath, imageUrl);
            XtreamSeries s = new XtreamSeries();
            s.num = num++;
            s.name = entry.getKey();
            s.seriesId = String.valueOf(Math.abs(entry.getKey().hashCode()));
            s.cover = imageUrl;
            s.plot = first.overview;
            s.rating = first.imdbRating != null ? first.imdbRating.toString() : "0";
            s.rating5based = first.imdbRating != null ? Math.ceil(first.imdbRating / 2.0) : 0;
            s.releaseDate = first.releaseDate;
            s.lastModified = String.valueOf(System.currentTimeMillis() / 1000);
            List<String> seriesGenres = (first.series != null && first.series.genres != null && !first.series.genres.isEmpty())
                ? first.series.genres : first.genres;
            if (seriesGenres != null && !seriesGenres.isEmpty()) {
                Models.Genre g = Models.Genre.find("LOWER(name) = ?1", seriesGenres.get(0).toLowerCase()).firstResult();
                s.categoryId = g != null ? g.id.toString() : "0";
            } else {
                s.categoryId = "0";
            }
            seriesList.add(s);
        }
        log.infof("getSeries: returning %d series, sample: %s", seriesList.size(), seriesList.isEmpty() ? "empty" : toJson(seriesList.subList(0, Math.min(2, seriesList.size()))));
        return Response.ok(seriesList).build();
    }

    private String getExternalBaseUri() {
        if (uriInfo.getBaseUri().getHost().equals("localhost") || uriInfo.getBaseUri().getHost().equals("127.0.0.1")) {
            return "http://" + System.getenv().getOrDefault("EXTERNAL_HOST", "localhost") + ":" + uriInfo.getBaseUri().getPort() + "/";
        }
        return uriInfo.getBaseUri().toString();
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
        String[] parts = resolution.split("x");
        return parts.length == 2 ? Integer.parseInt(parts[0].trim()) : 0;
    }

    private int parseHeight(String resolution) {
        if (resolution == null || resolution.isEmpty()) return 0;
        String[] parts = resolution.split("x");
        return parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 0;
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
