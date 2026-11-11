package API.Rest;

import Services.HlsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/hls")
public class HlsResource {

    private static final Logger LOG = LoggerFactory.getLogger(HlsResource.class);

    @Inject HlsService hlsService;

    @POST
    @Path("/session/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HlsService.SessionInfo createSession(@PathParam("videoId") Long videoId,
                                                @QueryParam("start") Double startSeconds,
                                                @QueryParam("profileId") Long profileId,
                                                @QueryParam("audioTrack") Integer audioTrackIndex,
                                                @QueryParam("quality") Integer qualityHeight,
                                                @QueryParam("device") String deviceToken,
                                                @QueryParam("codecs") String codecs,
                                                @QueryParam("audioCodecs") String audioCodecs,
                                                @HeaderParam("User-Agent") String userAgent) {
        try {
            double start = startSeconds != null ? startSeconds : 0.0;
            HlsService.HlsSession session = hlsService.createSession(videoId, start, profileId,
                    audioTrackIndex, qualityHeight, deviceToken, codecs, userAgent, audioCodecs);
            String playlistUrl = "/api/hls/master/" + session.sessionId + ".m3u8";
            return new HlsService.SessionInfo(session.sessionId, playlistUrl, session.effectiveStartSeconds);
        } catch (Exception e) {
            LOG.error("Failed to create HLS session for video {}: {}", videoId, e.getMessage(), e);
            throw new WebApplicationException("Failed to create HLS session: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/master/{sessionId}.m3u8")
    @Produces("application/vnd.apple.mpegurl")
    public Response getMasterPlaylist(@PathParam("sessionId") String sessionId) {
        String playlist = hlsService.getMasterPlaylist(sessionId);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(playlist).type("application/vnd.apple.mpegurl").build();
    }

    @GET
    @Path("/playlist/{sessionId}/{variant}.m3u8")
    @Produces("application/vnd.apple.mpegurl")
    public Response getVariantPlaylist(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant) {
        String playlist = hlsService.getMediaPlaylist(sessionId, variant);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(playlist).type("application/vnd.apple.mpegurl").build();
    }

    @DELETE
    @Path("/session/{sessionId}")
    public Response destroySession(@PathParam("sessionId") String sessionId) {
        hlsService.destroySession(sessionId);
        return Response.ok().build();
    }

    @GET
    @Path("/media/{sessionId}/{variant}/{segment}")
    @Produces({"video/iso.segment", "text/vtt"})
    public Response getSegment(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant, @PathParam("segment") String segment) {
        // Sanitize segment name to prevent path traversal
        segment = java.nio.file.Path.of(segment).getFileName().toString();
        String contentType = segment.endsWith(".vtt") ? "text/vtt" : "video/iso.segment";
        // Wait for segment to be available (up to 2s — hls.js retries 503 with backoff)
        long deadline = System.currentTimeMillis() + 2000;
        java.nio.file.Path segmentPath = hlsService.getSegmentPath(sessionId, variant, segment);
        long lastLen = -1;
        long lastLenChangeAt = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            if (segmentPath != null && Files.exists(segmentPath)) {
                // WebVTT needs X-TIMESTAMP-MAP for Apple players; ensured on serve.
                if (segment.endsWith(".vtt")) {
                    java.io.File vtt = hlsService.getVttSegmentFile(sessionId, variant, segment);
                    if (vtt != null) return Response.ok(vtt).type(contentType).build();
                } else {
                    // The file exists as soon as ffmpeg opens it, before the last
                    // fragments are written. Serving at that moment hands the player
                    // a truncated segment - the classic "audio is choppy / glitches
                    // at some segment boundaries" symptom. Only serve once the size
                    // has stopped growing.
                    long len = safeSize(segmentPath);
                    long now = System.currentTimeMillis();
                    if (len > 0 && len == lastLen && now - lastLenChangeAt >= 150) {
                        return Response.ok(segmentPath.toFile()).type(contentType).build();
                    }
                    if (len != lastLen) {
                        lastLen = len;
                        lastLenChangeAt = now;
                    }
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            segmentPath = hlsService.getSegmentPath(sessionId, variant, segment);
        }
        
        LOG.debug("Segment not ready after 2s: {}/{}/{}", sessionId, variant, segment);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
    }

    @GET
    @Path("/media/{sessionId}/{variant}/init.mp4")
    @Produces("video/mp4")
    public Response getInitSegment(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant) {
        long deadline = System.currentTimeMillis() + 5000;
        long lastLen = -1;
        long lastLenChangeAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            java.nio.file.Path initPath = hlsService.getInitSegmentPath(sessionId, variant);
            if (initPath != null && Files.exists(initPath)) {
                // Same truncation guard as media segments: the init file exists
                // before its boxes are completely written.
                long len = safeSize(initPath);
                long now = System.currentTimeMillis();
                if (len > 0 && len == lastLen && now - lastLenChangeAt >= 150) {
                    return Response.ok(initPath.toFile()).type("video/mp4").build();
                }
                if (len != lastLen) {
                    lastLen = len;
                    lastLenChangeAt = now;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
    }
    /** File size, or -1 when the file cannot be stat'ed (still being written). */
    private static long safeSize(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (java.io.IOException e) {
            LOG.debug("Could not stat HLS media file {}: {}", path, e.getMessage());
            return -1L;
        }
    }
}