package API.Rest;

import Services.HlsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
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
                                                @QueryParam("quality") Integer qualityHeight) {
        try {
            double start = startSeconds != null ? startSeconds : 0.0;
            HlsService.HlsSession session = hlsService.createSession(videoId, start, profileId, audioTrackIndex, qualityHeight);
            String playlistUrl = "/api/hls/master/" + session.sessionId + ".m3u8";
            return new HlsService.SessionInfo(session.sessionId, playlistUrl);
        } catch (Exception e) {
            LOG.error("Failed to create HLS session for video {}: {}", videoId, e.getMessage(), e);
            throw new WebApplicationException("Failed to create HLS session: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/master/{sessionId}.m3u8")
    @Produces("application/x-mpegURL")
    public Response getMasterPlaylist(@PathParam("sessionId") String sessionId) {
        String playlist = hlsService.getMasterPlaylist(sessionId);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(playlist).type("application/x-mpegURL").build();
    }

    @GET
    @Path("/playlist/{sessionId}/{variant}.m3u8")
    @Produces("application/x-mpegURL")
    public Response getVariantPlaylist(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant) {
        String playlist = hlsService.getMediaPlaylist(sessionId, variant);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(playlist).type("application/x-mpegURL").build();
    }

    @DELETE
    @Path("/session/{sessionId}")
    public Response destroySession(@PathParam("sessionId") String sessionId) {
        hlsService.destroySession(sessionId);
        return Response.ok().build();
    }

    @GET
    @Path("/media/{sessionId}/{variant}/{segment}")
    @Produces("video/MP2T")
    public Response getSegment(@PathParam("sessionId") String sessionId, @PathParam("variant") String variant, @PathParam("segment") String segment) {
        File segmentFile = hlsService.getSegment(sessionId, variant, segment);
        if (segmentFile == null || !segmentFile.exists()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(segmentFile).type("video/MP2T").build();
    }
}
