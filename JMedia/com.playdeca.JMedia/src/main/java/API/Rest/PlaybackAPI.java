package API.Rest;

import API.ApiResponse;
import Models.PlaybackState;
import Models.Profile;
import Controllers.PlaybackController;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Transactional
@Path("/api/music/playback")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PlaybackAPI {

    @Inject
    private PlaybackController playbackController;

    @Inject
    private SettingsService settingsService;

    private Profile getUserProfile(HttpHeaders headers) {
        return settingsService.getActiveProfileFromHeaders(headers);
    }

    @GET
    @Path("/current/{profileId}")
    public Response getCurrentSong(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        var current = playbackController.getCurrentSong(userProfile.id);
        return Response.ok(ApiResponse.success(current)).build();
    }

    @GET
    @Path("/previousSong/{profileId}")
    public Response getPreviousSong(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        var previous = playbackController.getPreviousSong(userProfile.id);
        return Response.ok(ApiResponse.success(previous)).build();
    }

    @GET
    @Path("/nextSong/{profileId}")
    public Response getNextSong(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        var next = playbackController.getNextSong(userProfile.id);
        return Response.ok(ApiResponse.success(next)).build();
    }

    @GET
    @Path("/state/{profileId}")
    public Response getState(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        PlaybackState state = playbackController.getState(userProfile.id);
        return Response.ok(ApiResponse.success(state)).build();
    }

    @POST
    @Path("/toggle/{profileId}")
    public Response togglePlay(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.togglePlay(userProfile.id);
        return Response.ok(ApiResponse.success("Playback toggled")).build();
    }

    @POST
    @Path("/play/{profileId}")
    public Response play(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.togglePlay(userProfile.id);
        return Response.ok(ApiResponse.success("Playback started")).build();
    }

    @POST
    @Path("/pause/{profileId}")
    public Response pause(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.togglePlay(userProfile.id);
        return Response.ok(ApiResponse.success("Playback paused")).build();
    }

    @POST
    @Path("/next/{profileId}")
    public Response next(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.next(userProfile.id);
        return Response.ok(ApiResponse.success("Skipped to next song")).build();
    }

    @POST
    @Path("/previous/{profileId}")
    public Response previous(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.previous(userProfile.id);
        return Response.ok(ApiResponse.success("Skipped to previous song")).build();
    }

    @POST
    @Path("/select/{profileId}/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        try {
            playbackController.selectSong(id, userProfile.id);
            return Response.ok(ApiResponse.success("Song selected")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/shuffle/{profileId}")
    public Response toggleShuffle(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.toggleShuffle(userProfile.id);
        return Response.ok(ApiResponse.success("Shuffle toggled")).build();
    }

    @POST
    @Path("/repeat/{profileId}")
    public Response toggleRepeat(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.toggleRepeat(userProfile.id);
        return Response.ok(ApiResponse.success("Repeat toggled")).build();
    }

    @POST
    @Path("/volume/{profileId}/{level}")
    public Response setVolume(@PathParam("profileId") Long profileId, @PathParam("level") float level, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.changeVolume(level, userProfile.id);
        return Response.ok(ApiResponse.success("Volume changed")).build();
    }

    @POST
    @Path("/position/{profileId}/{seconds}")
    public Response setPosition(@PathParam("profileId") Long profileId, @PathParam("seconds") double seconds, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.setSeconds(seconds, userProfile.id);
        return Response.ok(ApiResponse.success("Position changed")).build();
    }

    @GET
    @Path("/crossfade/{profileId}")
    public Response getCrossfade(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        int crossfade = playbackController.getCrossfadeDuration(userProfile.id);
        return Response.ok(ApiResponse.success(crossfade)).build();
    }

    @POST
    @Path("/crossfade/{profileId}/{seconds}")
    public Response setCrossfade(@PathParam("profileId") Long profileId, @PathParam("seconds") int seconds, @Context HttpHeaders headers) {
        Profile userProfile = getUserProfile(headers);
        if (userProfile == null) return Response.status(401).build();
        playbackController.setCrossfadeDuration(seconds, userProfile.id);
        return Response.ok(ApiResponse.success("Crossfade set to " + seconds + " seconds")).build();
    }
}
