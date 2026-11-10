package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.DTOs.TextPlaylistRequest;
import Models.DTOs.TextPlaylistResponse;
import Models.Music.Playlist;
import Models.Settings.Profile;
import Models.Music.Song;
import Services.PlaylistService;
import Services.ProfileService;
import Services.SongService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Music playlist REST API.
 *
 * This class restores the playlist endpoints that the frontend (MusicIndex.js,
 * MobileApp.js, MobileContextMenu.js, ImportApi.js, PlaylistCreator.js,
 * PlaylistBodyHelper.js, PlaybackApi.js) still calls under /api/music/playlists/*.
 * The original PlaylistAPI.java was replaced in commit d253ef2 by the Xtream
 * /get.php PlaylistApi; the JS contract was never updated, which caused 404s and
 * uncaught JSON.parse errors in the browser. Named MusicPlaylistApi (not
 * PlaylistAPI) to avoid a case-insensitive filename collision with PlaylistApi.java
 * on Windows.
 */
@Path("/api/music/playlists")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class MusicPlaylistApi {

    /** Frontend sends {"name": "...", "profileId": N} (optionally "description"). */
    public record CreatePlaylistRequest(String name, Long profileId, String description) {}

    /** Frontend sends {"name": "..."} (optionally "description") when renaming. */
    public record UpdatePlaylistRequest(String name, String description) {}

    public static class BulkAddRequest { public List<Long> songIds; }

    @Inject
    private PlaybackController playbackController;

    @Inject
    private PlaylistService playlistService;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MusicPlaylistApi.class);

    @Inject
    private ProfileService profileService;

    @Inject
    private SongService songService;

    @GET
    @Path("/{profileId}")
    public Response listPlaylists(@PathParam("profileId") Long profileId) {
        try {
            List<Playlist> playlists = getPlaylistsByProfileId(profileId);
            return Response.ok(ApiResponse.success(playlists)).build();
        } catch (Exception e) {
            System.err.println("[ERROR] Error fetching playlists: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error fetching playlists: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/")
    public Response createPlaylist(CreatePlaylistRequest request) {
        try {
            if (request == null || request.name() == null || request.name().isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Name required"))
                        .build();
            }

            Playlist playlist = new Playlist();
            playlist.setName(request.name().trim());
            playlist.setDescription(request.description());
            playlist.setSongs(new ArrayList<>());

            if (request.profileId() != null) {
                Profile profile = profileService.findById(request.profileId());
                if (profile == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(ApiResponse.error("Profile not found"))
                            .build();
                }
                playlist.setProfileId(profile.id);
            }

            playbackController.createPlaylist(playlist);
            return Response.status(Response.Status.CREATED).entity(ApiResponse.success(playlist)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error creating playlist: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePlaylist(@PathParam("id") Long id, UpdatePlaylistRequest request) {
        try {
            Playlist existingPlaylist = playlistService.find(id);
            if (existingPlaylist == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            // Only update the fields the client actually sent; never touch songs here
            // (the historical implementation wiped the song list on rename).
            if (request != null) {
                if (request.name() != null && !request.name().isBlank()) {
                    existingPlaylist.setName(request.name().trim());
                }
                if (request.description() != null) {
                    existingPlaylist.setDescription(request.description());
                }
            }
            playlistService.save(existingPlaylist);
            return Response.ok(ApiResponse.success(existingPlaylist)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error updating playlist: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaylist(@PathParam("id") Long id) {
        try {
            Playlist playlist = playlistService.find(id);
            if (playlist == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }

            List<Profile> allProfiles = profileService.findAll();
            for (Profile profile : allProfiles) {
                if (profile.isPlaylistHidden(id)) {
                    profile.removeHiddenPlaylist(id);
                    profile.persist();
                }
            }

            playbackController.deletePlaylist(playlist);
            return Response.ok(ApiResponse.success("deleted"))
                    .header("HX-Trigger", "delete-playlist")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error deleting playlist: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/add/{playlistId}/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response addSongToPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        try {
            Playlist p = playlistService.find(pid);
            if (p == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            Song s = songService.find(sid);
            if (s == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Song not found"))
                        .build();
            }
            // find() returns a DETACHED entity, so touching the lazy `songs`
            // collection here throws LazyInitializationException ("no session").
            // The service reloads the playlist inside a transaction and merges it.
            playlistService.addSongsToPlaylist(p, java.util.List.of(s));
            return Response.ok(ApiResponse.success(p)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error adding song to playlist: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{playlistId}/songs/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response addSongToPlaylistByPath(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        return addSongToPlaylist(pid, sid);
    }

    @GET
    @Path("/{playlistId}/song-ids")
    public Response getPlaylistSongIds(@PathParam("playlistId") Long playlistId) {
        try {
            if (playlistService.find(playlistId) == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            List<Long> ids = playlistService.getSongIds(playlistId);
            return Response.ok(Map.of("songIds", ids, "count", ids.size())).build();
        } catch (Exception e) {
            LOG.error("Error loading playlist song ids", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error loading playlist songs: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{playlistId}/songs/bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addSongsToPlaylistBulk(@PathParam("playlistId") Long pid, BulkAddRequest request) {
        try {
            Playlist p = playlistService.find(pid);
            if (p == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            if (request == null || request.songIds == null || request.songIds.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("songIds is required"))
                        .build();
            }
            List<Song> songs = request.songIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .map(songService::find)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (songs.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("songIds is required"))
                        .build();
            }
            playlistService.addSongsToPlaylist(p, songs);
            return Response.ok(ApiResponse.success("Added " + songs.size() + " songs to playlist")).build();
        } catch (Exception e) {
            LOG.error("Error adding songs to playlist in bulk", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error adding songs to playlist: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{playlistId}/songs/{songId}")
    @Consumes(MediaType.WILDCARD)
    public Response removeSongFromPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        try {
            Playlist p = playlistService.find(pid);
            if (p == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            // Detached entity from find(): mutate through the service's transaction.
            playlistService.removeSongFromPlaylist(pid, sid);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error removing song from playlist: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{playlistId}/songs/{songId}/toggle/{profileId}")
    @Consumes(MediaType.WILDCARD)
    public Response toggleSongInPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid,
                                         @PathParam("profileId") Long profileId) {
        try {
            Playlist p = playlistService.find(pid);
            if (p == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            Song s = songService.find(sid);
            if (s == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Song not found"))
                        .build();
            }

            // Detached entity from find(): toggle through the service's transaction.
            playlistService.toggleSongInPlaylist(pid, sid);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error toggling song in playlist: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{playlistId}/toggle-shared")
    public Response togglePlaylistShared(@PathParam("playlistId") Long playlistId, Map<String, Object> request) {
        try {
            Boolean isShared = request != null ? (Boolean) request.get("isShared") : null;
            Playlist playlist = playlistService.find(playlistId);
            if (playlist == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Playlist not found"))
                        .build();
            }
            playlist.setIsGlobal(isShared);
            playlistService.save(playlist);
            return Response.ok(ApiResponse.success("Playlist shared status updated"))
                    .header("HX-Trigger", "playlist-list-refresh")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error updating playlist: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/create-from-text/{profileId}")
    public Response createPlaylistFromText(@PathParam("profileId") Long profileId, TextPlaylistRequest request) {
        try {
            if (request == null || request.getPlaylistName() == null || request.getPlaylistName().isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Playlist name is required"))
                        .build();
            }

            if (request.getTextLines() == null || request.getTextLines().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Text lines are required"))
                        .build();
            }

            Profile profile = profileService.findById(profileId);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Profile not found"))
                        .build();
            }

            // Create new playlist
            Playlist playlist = new Playlist();
            playlist.setName(request.getPlaylistName());
            playlist.setDescription(request.getDescription());
            playlist.setProfileId(profile.id);
            playlist.setIsGlobal(false); // User-specific playlist
            playlist.setSongs(new ArrayList<>());

            List<String> unmatchedLines = new ArrayList<>();
            int matchedSongs = 0;

            // Process each line to find songs
            for (String line : request.getTextLines()) {
                if (line == null || line.trim().isBlank()) {
                    continue;
                }

                String trimmedLine = line.trim();
                Song foundSong = null;

                // Try different parsing strategies
                if (trimmedLine.contains(",")) {
                    // Format: "Song Name, Album, Artist" or "Song Name, Artist"
                    String[] parts = trimmedLine.split(",", -1);
                    if (parts.length >= 2) {
                        String songName = parts[0].trim();
                        String artist = parts[parts.length - 1].trim(); // Last part is usually artist
                        foundSong = songService.findByTitleAndArtist(songName, artist);
                    }
                } else if (trimmedLine.contains(" - ")) {
                    // Format: "Song Name - Artist" or "Artist - Song Name"
                    String[] parts = trimmedLine.split(" - ", -1);
                    if (parts.length == 2) {
                        String part1 = parts[0].trim();
                        String part2 = parts[1].trim();

                        // Try both orders
                        foundSong = songService.findByTitleAndArtist(part1, part2);
                        if (foundSong == null) {
                            foundSong = songService.findByTitleAndArtist(part2, part1);
                        }
                    }
                } else {
                    // Just search by title or artist
                    foundSong = songService.findByTitleAndArtist(trimmedLine, "");
                    if (foundSong == null) {
                        foundSong = songService.findByTitleAndArtist("", trimmedLine);
                    }
                }

                if (foundSong != null) {
                    // Check if song already in playlist to avoid duplicates
                    final var finalFoundSong = foundSong;
                    boolean alreadyInPlaylist = playlist.getSongs().stream()
                            .anyMatch(song -> song.id.equals(finalFoundSong.id));
                    if (!alreadyInPlaylist) {
                        playlist.getSongs().add(foundSong);
                        matchedSongs++;
                    }
                } else {
                    unmatchedLines.add(trimmedLine);
                }
            }

            // Save the playlist
            playlistService.save(playlist);

            // Create response
            TextPlaylistResponse response = new TextPlaylistResponse();
            response.setPlaylist(playlist);
            response.setTotalLines(request.getTextLines().size());
            response.setMatchedSongs(matchedSongs);
            response.setUnmatchedLines(unmatchedLines);

            if (matchedSongs > 0) {
                response.setMessage(String.format("Playlist '%s' created with %d songs out of %d lines",
                        playlist.getName(), matchedSongs, request.getTextLines().size()));
            } else {
                response.setMessage(String.format("No matching songs found for playlist '%s'", playlist.getName()));
            }

            return Response.ok(ApiResponse.success(response)).build();

        } catch (Exception e) {
            System.err.println("[ERROR] Error creating playlist from text: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error creating playlist from text: " + e.getMessage()))
                    .build();
        }
    }

    private List<Playlist> getPlaylistsByProfileId(Long profileId) {
        if (profileId == null) {
            return new ArrayList<>();
        }

        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return new ArrayList<>();
        }

        // Return playlists for this profile (user's playlists + global playlists)
        return playlistService.findAllForProfile(profile);
    }
}
