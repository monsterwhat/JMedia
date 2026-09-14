package API.Rest;

import Models.Video.Video;
import Models.Video.LiveChannel;
import Models.Settings.User;
import Services.AuthService;
import Utils.IpResolutionUtils;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;
import java.io.File;
import java.util.Optional;

/**
 * Root-level streaming endpoints for Xtream Codes compliance.
 * IPTV clients construct URLs as: server:port/live/user/pass/id.ext
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("/")
public class XtreamStreamAPI {

    private static final Logger log = Logger.getLogger(XtreamStreamAPI.class);

    @Inject AuthService authService;

    @Inject Services.SettingsService settingsService;

    @Inject Services.HlsService hlsService;

    @Inject Services.XtreamSessionService xtreamSessionService;

    @Context
    ContainerRequestContext requestContext;

    @Context
    HttpServerRequest vertxRequest;

    @GET
    @Path("/movie/{username}/{password}/{videoId}.{ext}")
    public Response streamMovie(@PathParam("username") String pathUsername, @PathParam("password") String pathPassword,
                                @PathParam("videoId") Long videoId, @PathParam("ext") String ext,
                                @HeaderParam("Range") String rangeHeader) {
        Optional<User> userOpt = authService.authenticate(pathUsername, pathPassword);
        if (userOpt.isEmpty()) return unauthorizedStreamResponse();
        log.infof("Stream movie request: videoId=%d, ext=%s, range=%s", videoId, ext, rangeHeader);
        Video video = Video.findById(videoId);
        if (video == null) { log.warnf("Movie not found: videoId=%d", videoId); return Response.status(Response.Status.NOT_FOUND).build(); }
        return streamVideoWithHls(video, videoId, "movie", ext, rangeHeader, userOpt.get(), resolveClientIp());
    }

    @GET
    @Path("/series/{username}/{password}/{videoId}.{ext}")
    public Response streamSeries(@PathParam("username") String pathUsername, @PathParam("password") String pathPassword,
                                 @PathParam("videoId") Long videoId, @PathParam("ext") String ext,
                                 @HeaderParam("Range") String rangeHeader) {
        Optional<User> userOpt = authService.authenticate(pathUsername, pathPassword);
        if (userOpt.isEmpty()) return unauthorizedStreamResponse();
        log.infof("Stream series request: videoId=%d, ext=%s, range=%s", videoId, ext, rangeHeader);
        Video video = Video.findById(videoId);
        if (video == null) { log.warnf("Series episode not found: videoId=%d", videoId); return Response.status(Response.Status.NOT_FOUND).build(); }
        return streamVideoWithHls(video, videoId, "series", ext, rangeHeader, userOpt.get(), resolveClientIp());
    }

    /**
     * TS/HLS-only IPTV apps request .m3u8; serve a transcoded HLS session for
     * those and keep progressive byte-serving for direct-source extensions.
     */
    private Response streamVideoWithHls(Video video, Long videoId, String type, String ext, String rangeHeader, User user, String ip) {
        if ("m3u8".equalsIgnoreCase(ext)) {
            Models.Settings.XtreamSession rec = xtreamSessionService.startSession(type, user.getUsername(), String.valueOf(user.id), videoId, "m3u8", ip);
            try {
                Services.HlsService.HlsSession session =
                        hlsService.createSession(videoId, 0.0, null, null, null, "xtream:" + rec.sessionId);
                return Response.temporaryRedirect(
                        java.net.URI.create(getExternalBaseUri() + "api/hls/master/" + session.sessionId + ".m3u8")).build();
            } catch (Exception e) {
                log.warnf("HLS session failed for videoId=%d, falling back to progressive stream: %s",
                        videoId, e.getMessage());
                // Record stays open; the progressive path below reuses it (ext stays "m3u8").
                return proxyLocalVideo(video, ext, rangeHeader, rec);
            }
        }
        Models.Settings.XtreamSession rec = xtreamSessionService.startSession(type, user.getUsername(), String.valueOf(user.id), videoId, ext, ip);
        return proxyLocalVideo(video, ext, rangeHeader, rec);
    }

    @GET
    @Path("/live/{username}/{password}/{channelId}.{ext}")
    public Response streamLive(@PathParam("username") String pathUsername, @PathParam("password") String pathPassword,
                               @PathParam("channelId") Long channelId, @PathParam("ext") String ext,
                               @HeaderParam("Range") String rangeHeader) {
        Optional<User> userOpt = authService.authenticate(pathUsername, pathPassword);
        if (userOpt.isEmpty()) return unauthorizedStreamResponse();
        log.infof("Stream live request: channelId=%d, ext=%s", channelId, ext);
        LiveChannel ch = LiveChannel.findById(channelId);
        if (ch == null || ch.streamUrl == null || ch.streamUrl.isBlank()) {
            log.warnf("Live channel not found or no URL: channelId=%d", channelId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Models.Settings.XtreamSession rec = xtreamSessionService.startSession("live", userOpt.get().getUsername(), String.valueOf(userOpt.get().id), channelId, ext, resolveClientIp());
        log.infof("Proxying live stream: channelId=%d, url=%s", channelId, ch.streamUrl);
        return proxyExternalStream(ch.streamUrl, pathUsername, pathPassword, rec);
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

    private String resolveClientIp() {
        try {
            return IpResolutionUtils.getClientIp(requestContext, vertxRequest);
        } catch (Exception e) {
            log.warnf("Failed to resolve client IP, defaulting to unknown: %s", e.getMessage());
            return "unknown";
        }
    }

    private Response proxyLocalVideo(Video video, String ext, String rangeHeader, Models.Settings.XtreamSession rec) {
        String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        java.nio.file.Path baseFilePath = java.nio.file.Paths.get(video.path);
        java.nio.file.Path filePath = baseFilePath.isAbsolute()
                ? baseFilePath : java.nio.file.Paths.get(libraryPath, video.path);

        File videoFile = filePath.toFile();
        if (!videoFile.exists() || !videoFile.isFile()) {
            log.warnf("Movie file missing: videoId=%d, stored path=%s, resolved=%s, library=%s",
                    video.id, video.path, filePath, libraryPath);
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
            try {
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
            } catch (Exception e) {
                xtreamSessionService.endSession(rec, "error");
                throw e;
            }
            xtreamSessionService.endSession(rec, "completed");
        };

        Response.ResponseBuilder rb = Response.status(rangeHeader != null ? Response.Status.PARTIAL_CONTENT : Response.Status.OK)
                .entity(stream)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", ct)
                .header("Content-Length", contentLength);

        if (rangeHeader != null) {
            rb.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        }

        return rb.build();
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
            boolean allowPrivate = false;
            try {
                allowPrivate = Boolean.TRUE.equals(settingsService.getOrCreateSettings().getXtreamAllowPrivateStreamSources());
            } catch (Exception ignored) {
            }
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                if (!allowPrivate && (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress())) {
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

    private Response proxyExternalStream(String url, String pathUsername, String pathPassword, Models.Settings.XtreamSession rec) {
        try {
            java.net.HttpURLConnection conn = openValidatedConnection(url);

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            long contentLength = conn.getContentLengthLong();

            jakarta.ws.rs.core.Response.ResponseBuilder rb;
            if (status >= 400) {
                xtreamSessionService.endSession(rec, "error");
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
                // Record stays open; HLS live segments bump lastActivity via XtreamCodesAPI.proxyStream.
                return Response.ok(rewritten.toString())
                        .type("application/vnd.apple.mpegurl")
                        .build();
            }

            final java.io.InputStream inputStream = conn.getInputStream();
            jakarta.ws.rs.core.StreamingOutput stream = out -> {
                try {
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = inputStream.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    } finally {
                        try { inputStream.close(); } catch (Exception ignored) {}
                        conn.disconnect();
                    }
                    xtreamSessionService.endSession(rec, "completed");
                } catch (Exception e) {
                    xtreamSessionService.endSession(rec, "disconnected");
                    throw e;
                }
            };
            rb.entity(stream);
            return rb.build();
        } catch (Exception e) {
            xtreamSessionService.endSession(rec, "error");
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

    @Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    @Context
    jakarta.ws.rs.core.HttpHeaders httpHeaders;

    private String getExternalBaseUri() {
        if (httpHeaders != null) {
            String forwardedHost = httpHeaders.getHeaderString("X-Forwarded-Host");
            if (forwardedHost != null && !forwardedHost.isBlank()) {
                String forwardedProto = httpHeaders.getHeaderString("X-Forwarded-Proto");
                String forwardedPort = httpHeaders.getHeaderString("X-Forwarded-Port");
                String scheme = (forwardedProto != null && !forwardedProto.isBlank())
                        ? forwardedProto.split(",")[0].trim() : uriInfo.getBaseUri().getScheme();
                String host = forwardedHost.split(",")[0].trim();
                StringBuilder base = new StringBuilder(scheme).append("://").append(host);
                if (forwardedPort != null && !forwardedPort.isBlank()) {
                    String port = forwardedPort.split(",")[0].trim();
                    if (!(("http".equals(scheme) && "80".equals(port)) || ("https".equals(scheme) && "443".equals(port)))) {
                        base.append(":").append(port);
                    }
                }
                base.append("/");
                return base.toString();
            }
        }
        if (uriInfo.getBaseUri().getHost().equals("localhost") || uriInfo.getBaseUri().getHost().equals("127.0.0.1")) {
            return "http://" + System.getenv().getOrDefault("EXTERNAL_HOST", "localhost") + ":" + uriInfo.getBaseUri().getPort() + "/";
        }
        return uriInfo.getBaseUri().toString();
    }
}
