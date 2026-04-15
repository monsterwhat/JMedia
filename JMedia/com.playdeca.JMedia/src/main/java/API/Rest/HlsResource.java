package API.Rest;

import API.ApiResponse;
import Services.HlsService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;

@Path("/api/video/hls")
@Blocking
public class HlsResource {

    private static final Logger LOG = LoggerFactory.getLogger(HlsResource.class);

    @Inject
    HlsService hlsService;

    @GET
    @Path("/{videoId}/check")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkSession(@PathParam("videoId") Long videoId) {
        try {
            HlsService.SessionInfo session = hlsService.checkSession(videoId);
            return Response.ok(ApiResponse.success(session)).header("Access-Control-Allow-Origin", "*").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to check session")).header("Access-Control-Allow-Origin", "*").build();
        }
    }

    @GET
    @Path("/{videoId}/init")
    @Produces(MediaType.APPLICATION_JSON)
    public Response initSession(@PathParam("videoId") Long videoId, @QueryParam("start") @DefaultValue("0") double startSeconds) {
        try {
            HlsService.HlsSession session = hlsService.createSession(videoId, startSeconds, null);
            HlsService.SessionInfo info = new HlsService.SessionInfo(session.sessionId, "/api/video/hls/" + session.sessionId + "/playlist.m3u8");
            return Response.ok(ApiResponse.success(info)).header("Access-Control-Allow-Origin", "*").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error(e.getMessage())).header("Access-Control-Allow-Origin", "*").build();
        }
    }

    @GET
    @Path("/{sessionId}/playlist.m3u8")
    @Produces("application/vnd.apple.mpegurl")
    public Response getMasterPlaylist(@PathParam("sessionId") String sessionId) {
        try {
            String playlist = hlsService.getMasterPlaylist(sessionId);
            if (playlist == null) return Response.status(Response.Status.NOT_FOUND).header("Access-Control-Allow-Origin", "*").build();
            return Response.ok(playlist).header("Content-Type", "application/vnd.apple.mpegurl").header("Cache-Control", "no-cache, no-store, must-revalidate").header("Access-Control-Allow-Origin", "*").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).header("Access-Control-Allow-Origin", "*").build();
        }
    }

    @GET
    @Path("/{sessionId}/media.m3u8")
    @Produces("application/vnd.apple.mpegurl")
    public Response getMediaPlaylist(@PathParam("sessionId") String sessionId) {
        try {
            String playlist = hlsService.getMediaPlaylist(sessionId, "main");
            if (playlist == null) return Response.status(Response.Status.NOT_FOUND).entity("#EXTM3U\n#ERROR: Not ready\n").header("Access-Control-Allow-Origin", "*").build();
            return Response.ok(playlist).header("Content-Type", "application/vnd.apple.mpegurl").header("Cache-Control", "no-cache, no-store, must-revalidate").header("Access-Control-Allow-Origin", "*").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).header("Access-Control-Allow-Origin", "*").build();
        }
    }

    @GET
    @Path("/{sessionId}/{segmentName:[^/]+\\.ts}")
    public Response getSegment(@PathParam("sessionId") String sessionId, @PathParam("segmentName") String segmentName) {
        try {
            File segment = hlsService.getSegment(sessionId, "main", segmentName);
            if (segment == null || !segment.exists()) return Response.status(Response.Status.NOT_FOUND).build();
            return Response.ok(new FileInputStream(segment)).header("Content-Type", "video/mp2t").header("Cache-Control", "public, max-age=86400").header("Access-Control-Allow-Origin", "*").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).header("Access-Control-Allow-Origin", "*").build();
        }
    }

    @GET
    @Path("/{sessionId}/segments/{segmentName}")
    public Response getSegmentLegacy(@PathParam("sessionId") String sessionId, @PathParam("segmentName") String segmentName) {
        return getSegment(sessionId, segmentName);
    }
}