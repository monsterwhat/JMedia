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
                                                @QueryParam("device") String deviceToken) {
        try {
            double start = startSeconds != null ? startSeconds : 0.0;
            HlsService.HlsSession session = hlsService.createSession(videoId, start, profileId, audioTrackIndex, qualityHeight, deviceToken);
            String playlistUrl = "/api/hls/master/" + session.sessionId + ".m3u8";
            return new HlsService.SessionInfo(session.sessionId, playlistUrl);
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
    @Produces("video/iso.segment")
    public Response getSegment(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant, @PathParam("segment") String segment) {
        // Wait for segment to be available (up to 5s polling every 100ms)
        long deadline = System.currentTimeMillis() + 15000;
        java.nio.file.Path segmentPath = hlsService.getSegmentPath(sessionId, variant, segment);
        
        while (System.currentTimeMillis() < deadline) {
            if (segmentPath != null && Files.exists(segmentPath)) {
                return Response.ok(segmentPath.toFile()).type("video/iso.segment").build();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            segmentPath = hlsService.getSegmentPath(sessionId, variant, segment);
        }
        
        LOG.debug("Segment not ready after 15s: {}/{}/{}", sessionId, variant, segment);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
    }

    @GET
    @Path("/media/{sessionId}/{variant}/init.mp4")
    @Produces("video/mp4")
    public Response getInitSegment(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            java.nio.file.Path initPath = hlsService.getInitSegmentPath(sessionId, variant);
            if (initPath != null && Files.exists(initPath)) {
                return Response.ok(initPath.toFile()).type("video/mp4").build();
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
}
