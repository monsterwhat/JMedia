package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Settings;
import Models.Song;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Transactional
@Path("/api/music")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MusicApi {

    @Inject
    private PlaybackController playbackController;

    private static final Logger LOGGER = Logger.getLogger(MusicApi.class.getName());

    // -------------------------
    // Streaming endpoint by song ID
    // -------------------------
    @GET
    @Path("/stream/{id}")
    @Produces({"audio/mpeg", "application/octet-stream"})
    public Response streamMusicById(@PathParam("id") Long id, @HeaderParam("Range") String rangeHeader) {
        try {
            Song song = playbackController.findSong(id);
            if (song == null) {
                LOGGER.warning("Stream requested for missing song ID " + id);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String songPath = song.getPath();
            File file = new File(getMusicFolder(), songPath);
            LOGGER.info("Streaming song: " + song.getTitle() + " from path: " + file.getAbsolutePath());

            if (!file.exists() || file.isDirectory()) {
                LOGGER.warning("File not found or is directory: " + file.getAbsolutePath());
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            long len = file.length(), start = 0, end = len - 1;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.replace("bytes=", "").split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warning("Invalid Range header: " + rangeHeader);
                }
            }

            if (start >= len) {
                LOGGER.warning("Requested range start beyond file length: start=" + start + ", len=" + len);
                return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */" + len)
                        .build();
            }

            long contentLength = end - start + 1;
            final long trueStart = start;

            StreamingOutput stream = out -> {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(trueStart);
                    byte[] buf = new byte[16 * 1024];
                    long left = contentLength;
                    int read;
                    while ((read = raf.read(buf)) != -1 && left > 0) {
                        int toWrite = (int) Math.min(read, left);
                        try {
                            out.write(buf, 0, toWrite);
                        } catch (IOException e) {
                            // Client disconnected: just stop streaming, log at fine/debug level
                            LOGGER.fine(() -> "Client disconnected while streaming " + file.getName() + ": " + e.getMessage());
                            break;
                        }
                        left -= toWrite;
                    }
                    // No flush needed: container handles it
                } catch (IOException e) {
                    // Other I/O errors
                    LOGGER.log(Level.WARNING, "Error streaming file " + file.getName(), e);
                }
            };

            LOGGER.info("Streaming response prepared: bytes=" + start + "-" + end + "/" + len);
            return Response.status(rangeHeader != null ? 206 : 200)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + len)
                    .header("Cache-Control", "public,max-age=3600")
                    .entity(stream)
                    .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error streaming music file", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    // -------------------------
    // Playlist endpoints (JSON)
    // -------------------------
    @GET
    @Path("/playlists")
    public Response listPlaylists() {
        List<Playlist> playlists = playbackController.getPlaylists();
        if (playlists == null) {
            playlists = new ArrayList<>();
        }
        return Response.ok(ApiResponse.success(playlists)).build();
    }

    @GET
    @Path("/playlists/{id}")
    public Response getPlaylist(@PathParam("id") Long id) {
        return Response.ok(ApiResponse.success(requirePlaylist(id))).build();
    }

    @POST
    @Path("/playlists")
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
    @Path("/playlists/{id}")
    public Response updatePlaylist(@PathParam("id") Long id, Playlist playlist) {
        Playlist e = requirePlaylist(id);
        e.setName(playlist.getName());
        e.setDescription(playlist.getDescription());
        e.setSongs(playlist.getSongs() != null ? playlist.getSongs() : new ArrayList<>());
        playbackController.updatePlaylist(e);
        return Response.ok(ApiResponse.success(e)).build();
    }

    @DELETE
    @Path("/playlists/{id}")
    public Response deletePlaylist(@PathParam("id") Long id) {
        Playlist p = requirePlaylist(id);
        playbackController.deletePlaylist(p);
        return Response.ok(ApiResponse.success("deleted")).build();
    }

    @POST
    @Path("/playlists/{playlistId}/songs/{songId}")
    public Response addSongToPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        Playlist p = requirePlaylist(pid);
        Song s = requireSong(sid);
        if (p.getSongs().stream().noneMatch(song -> song.id.equals(sid))) {
            p.getSongs().add(s);
            playbackController.updatePlaylist(p);
        }
        return Response.ok(ApiResponse.success(p)).build();
    }

    @DELETE
    @Path("/playlists/{playlistId}/songs/{songId}")
    public Response removeSongFromPlaylist(@PathParam("playlistId") Long pid, @PathParam("songId") Long sid) {
        Playlist p = requirePlaylist(pid);
        p.getSongs().removeIf(song -> song.id.equals(sid));
        playbackController.updatePlaylist(p);
        return Response.ok(ApiResponse.success(p)).build();
    }

    // -------------------------
    // Playback endpoints
    // -------------------------
    @GET
    @Path("/playback/current")
    public Response getCurrentSong() {
        var current = playbackController.getCurrentSong();
        return Response.ok(ApiResponse.success(current)).build();
    }

    @POST
    @Path("/playback/toggle")
    public Response togglePlay() {
        playbackController.togglePlay();
        return Response.ok(ApiResponse.success("Playback toggled")).build();
    }

    @POST
    @Path("/playback/play")
    public Response play() {
        playbackController.togglePlay(); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback started")).build();
    }

    @POST
    @Path("/playback/pause")
    public Response pause() {
        playbackController.togglePlay(); // Assuming togglePlay handles both play and pause
        return Response.ok(ApiResponse.success("Playback paused")).build();
    }

    @POST
    @Path("/playback/next")
    public Response next() {
        playbackController.next();
        return Response.ok(ApiResponse.success("Skipped to next song")).build();
    }

    @POST
    @Path("/playback/previous")
    public Response previous() {
        playbackController.previous();
        return Response.ok(ApiResponse.success("Skipped to previous song")).build();
    }

    @POST
    @Path("/playback/select/{id}")
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
    @Path("/playback/shuffle")
    public Response toggleShuffle() {
        playbackController.toggleShuffle();
        return Response.ok(ApiResponse.success("Shuffle toggled")).build();
    }

    @POST
    @Path("/playback/repeat")
    public Response toggleRepeat() {
        playbackController.toggleRepeat();
        return Response.ok(ApiResponse.success("Repeat toggled")).build();
    }

    @POST
    @Path("/playback/volume/{level}")
    public Response setVolume(@PathParam("level") float level) {
        playbackController.changeVolume(level);
        return Response.ok(ApiResponse.success("Volume changed")).build();
    }

    @POST
    @Path("/playback/position/{seconds}")
    public Response setPosition(@PathParam("seconds") double seconds) {
        playbackController.setSeconds(seconds);
        return Response.ok(ApiResponse.success("Position changed")).build();
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

    private File getMusicFolder() {
        Settings settings = playbackController.getSettings();
        if (settings == null || settings.getLibraryPath() == null) {
            return new File("./"); // fallback
        }
        return new File(settings.getLibraryPath());
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
            Playlist playlist = playbackController.findPlaylist(id);
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

    // -------------------------
    // Queue endpoints
    // -------------------------
    @GET
    @Path("/queue")
    public Response getQueue() {
        return Response.ok(ApiResponse.success(playbackController.getQueue())).build();
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
