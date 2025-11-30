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
import java.util.ArrayList;
import java.util.List;

@Transactional
@Path("/api/music/playlists")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PlaylistAPI {

    @Inject
    private PlaybackController playbackController;

    @GET
    @Path("/{profileId}")
    public Response listPlaylists(@PathParam("profileId") Long profileId) { 
        try {
            List<Playlist> playlists = playbackController.getPlaylists();
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
        return Response.ok(ApiResponse.success(requirePlaylist(id))).build();
    }

    @POST
    @Path("/")
    public Response createPlaylist(Playlist playlist) {
        if (playlist == null || playlist.getName() == null || playlist.getName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Name required")).build();
        }
        if (playlist.getSongs() == null) {
            playlist.setSongs(new ArrayList<>());
        }
        playbackController.createPlaylist(playlist);
        return Response.status(Response.Status.CREATED).entity(ApiResponse.success(playlist)).build();
    }

    @PUT
    @Path("/{id}")
    public Response updatePlaylist(@PathParam("id") Long id, Playlist playlist) {
        Playlist e = requirePlaylist(id);
        e.setName(playlist.getName());
        e.setDescription(playlist.getDescription());
        e.setSongs(playlist.getSongs() != null ? playlist.getSongs() : new ArrayList<>());
        playbackController.updatePlaylist(e);
        return Response.ok(ApiResponse.success(e)).build();
    }

    @DELETE
    @Path("/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaylist(@PathParam("id") Long id) {
        Playlist p = requirePlaylist(id);
        playbackController.deletePlaylist(p);
        return Response.ok(ApiResponse.success("deleted"))
                .header("HX-Trigger", "delete-playlist")
                .build();
    }

    @POST
    @Path("/{playlistId}/songs/{songId}/{profileId}")
    public Response addSongToPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid, @PathParam("profileId") Long profileId) {
        Playlist p = requirePlaylist(pid);
        Song s = requireSong(sid);
        if (p.getSongs().stream().noneMatch(song -> song.id.equals(sid))) {
            p.getSongs().add(s);
            playbackController.updatePlaylist(p);
        }
        return Response.ok(ApiResponse.success(p)).build();
    }

    @DELETE
    @Path("/{playlistId}/songs/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response removeSongFromPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        Playlist p = requirePlaylist(pid);
        p.getSongs().removeIf(song -> song.id.equals(sid));
        playbackController.updatePlaylist(p);
        return Response.ok().build();
    }

    @POST
    @Path("/{playlistId}/songs/{songId}/toggle/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response toggleSongInPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid, @PathParam("profileId") Long profileId) {
        Playlist p = requirePlaylist(pid);
        Song s = requireSong(sid);

        boolean songExistsInPlaylist = p.getSongs().stream().anyMatch(song -> song.id.equals(sid));

        if (songExistsInPlaylist) {
            p.getSongs().removeIf(song -> song.id.equals(sid));
        } else {
            p.getSongs().add(s);
        }
        playbackController.updatePlaylist(p); // Assuming updatePlaylist is still global for the Playlist object
        // The PlaybackController.toggleSongInPlaylist(pid, sid, profileId) was for a TODO, so this logic stays here for now.
        return Response.ok().build();
    }

    private Playlist requirePlaylist(Long id) {
        Playlist p = playbackController.findPlaylist(id);
        if (p == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Playlist not found")).build());
        }
        if (p.getSongs() == null) {
            p.setSongs(new ArrayList<>());
        }
        return p;
    }

    private Song requireSong(Long id) {
        Song s = playbackController.findSong(id);
        if (s == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Song not found")).build());
        }
        return s;
    }
}
