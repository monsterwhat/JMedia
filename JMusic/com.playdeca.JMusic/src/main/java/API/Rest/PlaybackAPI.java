package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Transactional
@Path("/api/music/playback")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PlaybackAPI {

    @Inject
    private PlaybackController playbackController;

    @GET
    @Path("/current")
    public Response getCurrentSong() {
        var current = playbackController.getCurrentSong();
        return Response.ok(ApiResponse.success(current)).build();
    }

    @GET
    @Path("/previousSong")
    public Response getPreviousSong() {
        var previous = playbackController.getPreviousSong();
        return Response.ok(ApiResponse.success(previous)).build();
    }

    @GET
    @Path("/nextSong")
    public Response getNextSong() {
        var next = playbackController.getNextSong();
        return Response.ok(ApiResponse.success(next)).build();
    }

    @POST
    @Path("/toggle")
    public Response togglePlay() {
        playbackController.togglePlay();
        return Response.ok(ApiResponse.success("Playback toggled")).build();
    }

    @POST
    @Path("/play")
    public Response play() {
        playbackController.togglePlay(); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback started")).build();
    }

    @POST
    @Path("/pause")
    public Response pause() {
        playbackController.togglePlay(); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback paused")).build();
    }

    @POST
    @Path("/next")
    public Response next() {
        playbackController.next();
        return Response.ok(ApiResponse.success("Skipped to next song")).build();
    }

    @POST
    @Path("/previous")
    public Response previous() {
        playbackController.previous();
        return Response.ok(ApiResponse.success("Skipped to previous song")).build();
    }

    @POST
    @Path("/select/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectSong(@PathParam("id") Long id) {
        try {
            playbackController.selectSong(id);
            return Response.ok(ApiResponse.success("Song selected")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/shuffle")
    public Response toggleShuffle() {
        playbackController.toggleShuffle();
        return Response.ok(ApiResponse.success("Shuffle toggled")).build();
    }

    @POST
    @Path("/repeat")
    public Response toggleRepeat() {
        playbackController.toggleRepeat();
        return Response.ok(ApiResponse.success("Repeat toggled")).build();
    }

    @POST
    @Path("/volume/{level}")
    public Response setVolume(@PathParam("level") float level) {
        playbackController.changeVolume(level);
        return Response.ok(ApiResponse.success("Volume changed")).build();
    }

    @POST
    @Path("/position/{seconds}")
    public Response setPosition(@PathParam("seconds") double seconds) {
        playbackController.setSeconds(seconds);
        return Response.ok(ApiResponse.success("Position changed")).build();
    }
}
