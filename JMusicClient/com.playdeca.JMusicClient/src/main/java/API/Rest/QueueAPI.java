package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Song; 
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
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

    @GET
    @Path("/queue")
    public Response getQueue() {
        return Response.ok(ApiResponse.success(playbackController.getQueue())).build();
    }

    @POST
    @Path("/playback/queue-all/{id}") 
    @Consumes(MediaType.WILDCARD)
    public Response queueAllSongs(@PathParam("id") Long id) {
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

        playbackController.clearQueue();
        List<Long> songIds = songsToQueue.stream().map(s -> s.id).toList();
        playbackController.addToQueue(songIds, false);

        // Start playback with the first song
        playbackController.selectSong(songIds.get(0));

        return Response.ok(ApiResponse.success("All songs queued and playback started")).build();
    }

    @POST
    @Path("/queue/add/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response addSongToQueue(@PathParam("songId") Long songId) {
        if (songId == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Song ID cannot be null")).build();
        }
        playbackController.addToQueue(List.of(songId), false); // Add to end of queue
        return Response.ok(ApiResponse.success("Song added to queue")).build();
    }

    @POST
    @Path("/queue/skip-to/{index}")
    @Consumes(MediaType.WILDCARD)
    public Response skipToQueueIndex(@PathParam("index") int index) {
        playbackController.skipToQueueIndex(index);
        return Response.ok(ApiResponse.success("Skipped to song in queue")).build();
    }

    @POST
    @Path("/queue/remove/{index}")
    @Consumes(MediaType.WILDCARD)
    public Response removeFromQueue(@PathParam("index") int index) {
        playbackController.removeFromQueue(index);
        return Response.ok(ApiResponse.success("Removed song from queue")).build();
    }

    @POST
    @Path("/queue/clear")
    @Consumes(MediaType.WILDCARD)
    public Response clearQueue() {
        playbackController.clearQueue();
        return Response.ok(ApiResponse.success("Queue cleared")).build();
    }

    @GET
    @Path("/history")
    public Response getHistory() {
        return Response.ok(ApiResponse.success(playbackController.getHistory())).build();
    }
}
