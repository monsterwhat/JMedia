package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Profile;
import Models.Song; 
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response; 
import java.util.List; 

@Transactional
@Path("/api/music") // This path will be combined with method paths
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class QueueAPI {

    @Inject
    private PlaybackController playbackController;

    @Inject
    private SettingsService settingsService;

    private Profile getUserProfile(HttpHeaders headers) {
        return settingsService.getActiveProfileFromHeaders(headers);
    }

    @GET
    @Path("/queue/{profileId}")
    public Response getQueue(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        return Response.ok(ApiResponse.success(playbackController.getQueue(userProfile.id))).build();
    }

    @POST
    @Path("/playback/queue-all/{profileId}/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response queueAllSongs(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        
        List<Song> songsToQueue;
        if (id == null || id == 0) {
            // Queue all songs
            songsToQueue = playbackController.getSongs();
        } else {
            // Queue songs from a specific playlist
            Playlist playlist = playbackController.findPlaylistWithSongs(id);
            if (playlist == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Playlist not found")).build();
            }
            songsToQueue = playlist.getSongs();
        }

        if (songsToQueue == null || songsToQueue.isEmpty()) {
            return Response.ok(ApiResponse.success("No songs to queue")).build();
        }

        playbackController.clearQueue(userProfile.id);
        List<Long> songIds = songsToQueue.stream().map(s -> s.id).toList();
        playbackController.addToQueue(songIds, false, userProfile.id);

        // Start playback with the first song
        playbackController.selectSong(songIds.get(0), userProfile.id);

        return Response.ok(ApiResponse.success("All songs queued and playback started")).build();
    }
        
    @POST
    @Path("/queue/add/{profileId}/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response addSongToQueue(@PathParam("profileId") Long profileId, @PathParam("songId") Long songId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        if (songId == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Song ID cannot be null")).build();
        }
        playbackController.addToQueue(List.of(songId), false, userProfile.id);
        return Response.ok(ApiResponse.success("Song added to queue")).build();
    }

    @POST
    @Path("/queue/skip-to/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    public Response skipToQueueIndex(@PathParam("profileId") Long profileId, @PathParam("index") int index, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.skipToQueueIndex(index, userProfile.id);
        return Response.ok(ApiResponse.success("Skipped to song in queue")).build();
    }

    @POST
    @Path("/queue/remove/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    public Response removeFromQueue(@PathParam("profileId") Long profileId, @PathParam("index") int index, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.removeFromQueue(index, userProfile.id);
        return Response.ok(ApiResponse.success("Removed song from queue")).build();
    }

    @POST
    @Path("/queue/clear/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response clearQueue(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.clearQueue(userProfile.id);
        return Response.ok(ApiResponse.success("Queue cleared")).build();
    }

    @GET
    @Path("/history/{profileId}")
    public Response getHistory(
            @PathParam("profileId") Long profileId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        return Response.ok(ApiResponse.success(playbackController.getHistory(page, limit, userProfile.id))).build();
    }
}
