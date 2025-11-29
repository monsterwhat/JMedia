package API.Rest;

import Models.Profile;
import Services.ProfileService;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
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

    @GET
    public List<Profile> getAllProfiles() {
        return Profile.listAll();
    }

    @GET
    @Path("/current")
    public Profile getCurrentProfile() {
        return settingsService.getActiveProfile();
    }

    @POST
    @Transactional
    @Consumes(MediaType.TEXT_PLAIN) // Add this annotation
    public Response createProfile(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Profile name cannot be empty.").build();
        }
        try {
            Profile profile = profileService.createProfile(name.trim());
            return Response.ok(profile).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/switch/{id}")
    @Transactional
    public Response switchProfile(@PathParam("id") Long id) {
        Profile profile = Profile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        settingsService.setActiveProfile(profile);
        return Response.ok(profile).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteProfile(@PathParam("id") Long id) {
        Profile profile = Profile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Profile not found.").build();
        }
        if (profile.isMainProfile) {
            return Response.status(Response.Status.FORBIDDEN).entity("Cannot delete the main profile.").build();
        }
        
        // Before deleting a profile, decide what to do with their playlists and history.
        // For now, let's reassign them to the main profile.
        Profile mainProfile = profileService.getMainProfile();
        
        // Reassign playlists
        List<Models.Playlist> playlists = Models.Playlist.list("profile", profile);
        for (Models.Playlist p : playlists) {
            p.setProfile(mainProfile);
            p.persist();
        }
        
        // Reassign playback history
        List<Models.PlaybackHistory> history = Models.PlaybackHistory.list("profile", profile);
        for (Models.PlaybackHistory h : history) {
            h.profile = mainProfile;
            h.persist();
        }
        
        // Delete playback state
        Models.PlaybackState state = Models.PlaybackState.find("profile", profile).firstResult();
        if (state != null) {
            state.delete();
        }
        
        profile.delete();
        
        return Response.noContent().build();
    }
}
