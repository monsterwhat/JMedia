package API.Rest;

import API.ApiResponse;
import Models.PlaybackState;
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
    @Path("/current/{profileId}")
    public Response getCurrentSong(@PathParam("profileId") Long profileId) {
        var current = playbackController.getCurrentSong(profileId);
        return Response.ok(ApiResponse.success(current)).build();
    }

    @GET
    @Path("/previousSong/{profileId}")
    public Response getPreviousSong(@PathParam("profileId") Long profileId) {
        var previous = playbackController.getPreviousSong(profileId);
        return Response.ok(ApiResponse.success(previous)).build();
    }

    @GET
    @Path("/nextSong/{profileId}")
    public Response getNextSong(@PathParam("profileId") Long profileId) {
        var next = playbackController.getNextSong(profileId);
        return Response.ok(ApiResponse.success(next)).build();
    }

    @GET
    @Path("/state/{profileId}")
    public Response getState(@PathParam("profileId") Long profileId) {
        PlaybackState state = playbackController.getState(profileId);
        return Response.ok(ApiResponse.success(state)).build();
    }

    @POST
    @Path("/toggle/{profileId}")
    public Response togglePlay(@PathParam("profileId") Long profileId) {
        playbackController.togglePlay(profileId);
        return Response.ok(ApiResponse.success("Playback toggled")).build();
    }

    @POST
    @Path("/play/{profileId}")
    public Response play(@PathParam("profileId") Long profileId) {
        playbackController.togglePlay(profileId); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback started")).build();
    }

    @POST
    @Path("/pause/{profileId}")
    public Response pause(@PathParam("profileId") Long profileId) {
        playbackController.togglePlay(profileId); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback paused")).build();
    }

    @POST
    @Path("/next/{profileId}")
    public Response next(@PathParam("profileId") Long profileId) {
        playbackController.next(profileId);
        return Response.ok(ApiResponse.success("Skipped to next song")).build();
    }

    @POST
    @Path("/previous/{profileId}")
    public Response previous(@PathParam("profileId") Long profileId) {
        playbackController.previous(profileId);
        return Response.ok(ApiResponse.success("Skipped to previous song")).build();
    }

    @POST
    @Path("/select/{profileId}/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id) {
        try {
            playbackController.selectSong(id, profileId);
            return Response.ok(ApiResponse.success("Song selected")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/shuffle/{profileId}")
    public Response toggleShuffle(@PathParam("profileId") Long profileId) {
        playbackController.toggleShuffle(profileId);
        return Response.ok(ApiResponse.success("Shuffle toggled")).build();
    }

    @POST
    @Path("/repeat/{profileId}")
    public Response toggleRepeat(@PathParam("profileId") Long profileId) {
        playbackController.toggleRepeat(profileId);
        return Response.ok(ApiResponse.success("Repeat toggled")).build();
    }

    @POST
    @Path("/volume/{profileId}/{level}")
    public Response setVolume(@PathParam("profileId") Long profileId, @PathParam("level") float level) {
        playbackController.changeVolume(level, profileId);
        return Response.ok(ApiResponse.success("Volume changed")).build();
    }

    @POST
    @Path("/position/{profileId}/{seconds}")
    public Response setPosition(@PathParam("profileId") Long profileId, @PathParam("seconds") double seconds) {
        playbackController.setSeconds(seconds, profileId);
        return Response.ok(ApiResponse.success("Position changed")).build();
    }
}
