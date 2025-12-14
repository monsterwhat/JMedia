package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Profile;
import Models.Song;
import Services.PlaylistService;
import Services.ProfileService;
import Services.SongService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Transactional
@Path("/api/music/playlists")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PlaylistAPI {

    @Inject
    private PlaybackController playbackController;
    
    @Inject
    private PlaylistService playlistService;
    
    @Inject
    private ProfileService profileService;
    
    @Inject private SongService songService;

    @GET
    @Path("/{profileId}")
    public Response listPlaylists(@PathParam("profileId") Long profileId) { 
        try {
            List<Playlist> playlists = getPlaylistsByProfileId(profileId);
            if (playlists == null) {
                playlists = new ArrayList<>();
            }
            return Response.ok(ApiResponse.success(playlists)).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error fetching playlists: " + e.getMessage()); 
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error fetching playlists: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getPlaylist(@PathParam("id") Long id) {
        try {
            Playlist playlist = playlistService.find(id);
            if (playlist == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Playlist not found")).build();
            }
            return Response.ok(ApiResponse.success(playlist)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error fetching playlist: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/")
    public Response createPlaylist(Playlist playlist) {
        try {
            if (playlist == null || playlist.getName() == null || playlist.getName().isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Name required")).build();
            }
            if (playlist.getSongs() == null) {
                playlist.setSongs(new ArrayList<>());
            }
            playbackController.createPlaylist(playlist);
            return Response.status(Response.Status.CREATED).entity(ApiResponse.success(playlist)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error creating playlist: " + e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePlaylist(@PathParam("id") Long id, Playlist playlist) {
        try {
            Playlist existingPlaylist = playlistService.find(id);
            if (existingPlaylist == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Playlist not found")).build();
            }
            existingPlaylist.setName(playlist.getName());
            existingPlaylist.setDescription(playlist.getDescription());
            existingPlaylist.setSongs(playlist.getSongs() != null ? playlist.getSongs() : new ArrayList<>());
            playlistService.save(existingPlaylist);
            return Response.ok(ApiResponse.success(existingPlaylist)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error updating playlist: " + e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaylist(@PathParam("id") Long id) {
        try {
            Playlist playlist = playlistService.find(id);
            if (playlist == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Playlist not found")).build();
            }
            playbackController.deletePlaylist(playlist);
            return Response.ok(ApiResponse.success("deleted"))
                    .header("HX-Trigger", "delete-playlist")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error deleting playlist: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{playlistId}/songs/{songId}/{profileId}")
    public Response addSongToPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid, @PathParam("profileId") Long profileId) {
        try {
            Playlist p = playlistService.find(pid);
            Song s = songService.find(sid);
            if (p.getSongs().stream().noneMatch(song -> song.id.equals(sid))) {
                p.getSongs().add(s);
            }
            playlistService.save(p);
            return Response.ok(ApiResponse.success(p)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error adding song to playlist: " + e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{playlistId}/songs/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response removeSongFromPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        try {
            Playlist p = playlistService.find(pid);
            p.getSongs().removeIf(song -> song.id.equals(sid));
            playlistService.save(p);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error removing song from playlist: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{playlistId}/songs/{songId}/toggle/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response toggleSongInPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid, @PathParam("profileId") Long profileId) {
        try {
            Playlist p = playlistService.find(pid);
            Song s = songService.find(sid);
            
            boolean songExistsInPlaylist = p.getSongs().stream().anyMatch(song -> song.id.equals(sid));
            
            if (songExistsInPlaylist) {
                p.getSongs().removeIf(song -> song.id.equals(sid));
            } else {
                p.getSongs().add(s);
            }
            playlistService.save(p);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error toggling song in playlist: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{playlistId}/toggle-shared")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response togglePlaylistShared(@PathParam("playlistId") Long playlistId, Map<String, Object> request) {
        try {
            Boolean isShared = (Boolean) request.get("isShared");
            Playlist playlist = playlistService.find(playlistId);
            if (playlist != null) {
                playlist.setIsGlobal(isShared);
                playlistService.save(playlist);
                return Response.ok(ApiResponse.success("Playlist shared status updated"))
                        .header("HX-Trigger", "playlist-list-refresh")
                        .build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error updating playlist: " + e.getMessage()))
                    .build();
        }
    }

    private List<Playlist> getPlaylistsByProfileId(Long profileId) {
        if (profileId == null) {
            return new java.util.ArrayList<>();
        }

        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return new java.util.ArrayList<>();
        }

        // Return playlists for this profile (user's playlists + global playlists)
        return playlistService.findAllForProfile(profile);
    }
}