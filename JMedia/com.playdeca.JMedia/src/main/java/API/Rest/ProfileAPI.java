package API.Rest;

import API.ApiResponse;
import Models.Settings.Profile;
import Models.Settings.Session;
import Models.Settings.User;
import Services.ProfileService;
import Services.ProfileMusicDataService;
import Services.SessionService;
import Services.UserService;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/profiles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProfileAPI {

    @Inject
    ProfileService profileService;

    @Inject
    ProfileMusicDataService profileMusicDataService;

    @Inject
    SessionService sessionService;

    @Inject
    UserService userService;

    @Inject
    SettingsService settingsService;

    private User getCurrentUser(HttpHeaders headers) {
        String sessionId = getSessionId(headers);
        if (sessionId == null) {
            return null;
        }
        Session session = sessionService.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return null;
        }
        return userService.findById(Long.parseLong(session.userId));
    }

    private String getSessionId(HttpHeaders headers) {
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            return headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        return null;
    }

    @GET
    public Response getAllProfiles(@Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        List<Profile> profiles = profileService.findByUserId(currentUser.id);
        return Response.ok(profiles).build();
    }

    @GET
    @Path("/current")
    public Response getCurrentProfile(@Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        return Response.ok(profile).build();
    }

    @GET
    @Path("/{id}")
    public Response getProfile(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = profileService.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        if (profile.userId != null && !profile.userId.equals(currentUser.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Profile does not belong to current user.").build();
        }
        
        return Response.ok(profile).build();
    }

    @POST
    public Response createProfile(ProfileRequest request, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        if (request.name == null || request.name.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Profile name cannot be empty.").build();
        }
        try {
            Profile profile = profileService.createProfile(request.name.trim(), currentUser.id);
            return Response.ok(profile).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/switch/{id}")
    public Response switchProfile(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = profileService.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        if (profile.userId != null && !profile.userId.equals(currentUser.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Profile does not belong to current user.").build();
        }
        
        settingsService.setActiveProfile(profile, currentUser.id);
        return Response.ok(profile).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateProfile(@PathParam("id") Long id, Profile updatedProfile, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = profileService.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        if (profile.userId != null && !profile.userId.equals(currentUser.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Profile does not belong to current user.").build();
        }
        
        if (profile.isMainProfile) {
            return Response.status(Response.Status.FORBIDDEN).entity("Cannot update the main profile name.").build();
        }
        
        if (updatedProfile.name != null && !updatedProfile.name.trim().isEmpty()) {
            Profile existing = Profile.findByNameAndUser(updatedProfile.name.trim(), currentUser.id);
            if (existing != null && !existing.id.equals(id)) {
                return Response.status(Response.Status.CONFLICT).entity("Profile with name already exists.").build();
            }
            profile.name = updatedProfile.name.trim();
        }

        Profile saved = profileService.renameProfile(profile.id, profile.name);
        if (saved == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        return Response.ok(saved).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteProfile(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = profileService.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        if (profile.userId != null && !profile.userId.equals(currentUser.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Profile does not belong to current user.").build();
        }
        
        if (profile.isMainProfile) {
            return Response.status(Response.Status.FORBIDDEN).entity("Cannot delete the main profile.").build();
        }
        
        Profile otherProfile = Profile.find("id != ?1 and userId = ?2", id, currentUser.id).firstResult();

        // Two persistence units are involved and a JTA transaction cannot enlist both:
        // the profile row is in settings, its playlists/history/state are in music.
        // Move the music-side data first, in its own transaction, then delete the row
        // in a second one. If the move fails the profile survives and can be retried.
        if (otherProfile != null) {
            profileMusicDataService.reassignProfileData(profile.id, otherProfile.id);
        }

        if (!profileService.deleteProfile(profile.id)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }

        return Response.noContent().build();
    }

    @POST
    @Path("/hidden-playlists/{playlistId}")
    public Response hidePlaylist(@PathParam("playlistId") Long playlistId, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        Models.Music.Playlist playlist = Models.Music.Playlist.findById(playlistId);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Playlist not found.").build();
        }
        
        if (!profileService.setPlaylistHidden(profile.id, playlistId, true)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }

        return Response.ok(ApiResponse.success("Playlist hidden")).build();
    }

    @DELETE
    @Path("/hidden-playlists/{playlistId}")
    public Response unhidePlaylist(@PathParam("playlistId") Long playlistId, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        if (!profileService.setPlaylistHidden(profile.id, playlistId, false)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }

        return Response.ok(ApiResponse.success("Playlist unhidden")).build();
    }

    @GET
    @Path("/hidden-playlists")
    public Response getHiddenPlaylists(@Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        return Response.ok(profile.getHiddenPlaylistIds()).build();
    }
    
    public static class ProfileRequest {
        public String name;
    }
}
