package API.Rest;

import API.ApiResponse;
import Models.Profile;
import Models.Session;
import Models.User;
import Services.ProfileService;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    SettingsService settingsService;

    private User getCurrentUser(HttpHeaders headers) {
        String sessionId = getSessionId(headers);
        if (sessionId == null) {
            return null;
        }
        Session session = Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return null;
        }
        return User.findById(Long.parseLong(session.userId));
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
    public Response getProfile(@PathParam("id") Long id) {
        Profile profile = Profile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        return Response.ok(profile).build();
    }

    @POST
    @Transactional
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
    @Transactional
    public Response switchProfile(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = Profile.findById(id);
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
    @Transactional
    public Response updateProfile(@PathParam("id") Long id, Profile updatedProfile, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = Profile.findById(id);
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
            Profile existing = Profile.findByName(updatedProfile.name.trim());
            if (existing != null && !existing.id.equals(id)) {
                return Response.status(Response.Status.CONFLICT).entity("Profile with name already exists.").build();
            }
            profile.name = updatedProfile.name.trim();
        }
        
        profile.persist();
        return Response.ok(profile).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteProfile(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = Profile.findById(id);
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
        
        if (otherProfile != null) {
            List<Models.Playlist> playlists = Models.Playlist.list("profile", profile);
            for (Models.Playlist p : playlists) {
                p.setProfile(otherProfile);
                p.persist();
            }
            
            List<Models.PlaybackHistory> history = Models.PlaybackHistory.list("profile", profile);
            for (Models.PlaybackHistory h : history) {
                h.profile = otherProfile;
                h.persist();
            }
            
            Models.PlaybackState state = Models.PlaybackState.find("profile", profile).firstResult();
            if (state != null) {
                state.delete();
            }
        }
        
        profile.delete();
        
        return Response.noContent().build();
    }

    @POST
    @Path("/hidden-playlists/{playlistId}")
    @Transactional
    public Response hidePlaylist(@PathParam("playlistId") Long playlistId, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        Models.Playlist playlist = Models.Playlist.findById(playlistId);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Playlist not found.").build();
        }
        
        profile.addHiddenPlaylist(playlistId);
        profile.persist();
        
        return Response.ok(ApiResponse.success("Playlist hidden")).build();
    }

    @DELETE
    @Path("/hidden-playlists/{playlistId}")
    @Transactional
    public Response unhidePlaylist(@PathParam("playlistId") Long playlistId, @Context HttpHeaders headers) {
        User currentUser = getCurrentUser(headers);
        if (currentUser == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication required.").build();
        }
        
        Profile profile = settingsService.getActiveProfile(currentUser.id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        
        profile.removeHiddenPlaylist(playlistId);
        profile.persist();
        
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
