
import API.Rest.QueueAPI;
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Song;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Path("/api/music/ui")
@Produces(MediaType.TEXT_HTML)
public class MusicUiApi {

    @Inject
    private PlaybackController playbackController;

    @Inject
    private QueueAPI queueAPI; // Inject the Queue API

    // Templates
    @Inject
    Template playlistFragment;
    @Inject
    Template playlistView;
    @Inject
    Template playlistTbody;
    @Inject
    Template queueFragment;
    @Inject
    Template addToPlaylistDialog;

    private String formatDate(Object date) {
        if (date == null) {
            return "Unknown";
        }
        if (date instanceof java.time.LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return date.toString();
    }

    private String formatDuration(Integer seconds) {
        if (seconds == null) {
            return "0:00";
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    private String artworkUrl(String artworkBase64) {
        if (artworkBase64 != null && !artworkBase64.isEmpty()) {
            return "data:image/jpeg;base64," + artworkBase64;
        }
        return "/logo.png";
    }

    // -------------------------
    // Queue actions
    // -------------------------
    @POST
    @Path("/playback/queue-all/{id}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public TemplateInstance queueAllSongsUi(@PathParam("id") Long id) {
        // Call the JSON API to do the actual queuing
        Response apiResponse = queueAPI.queueAllSongs(id);

        // Then return the updated queue fragment for HTMX
        List<Song> updatedQueue = playbackController.getQueue();

        return queueFragment
                .data("queue", updatedQueue)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl);
    }

    @POST
    @Path("/queue/skip-to/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public TemplateInstance skipToQueueIndexUi(@PathParam("index") int index) {
        queueAPI.skipToQueueIndex(index);

        List<Song> updatedQueue = playbackController.getQueue();
        return queueFragment
                .data("queue", updatedQueue)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl);
    }

    @POST
    @Path("/queue/remove/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public TemplateInstance removeFromQueueUi(@PathParam("index") int index) {
        queueAPI.removeFromQueue(index);

        List<Song> updatedQueue = playbackController.getQueue();
        return queueFragment
                .data("queue", updatedQueue)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl);
    }

    @POST
    @Path("/queue/clear")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public TemplateInstance clearQueueUi() {
        queueAPI.clearQueue();

        List<Song> updatedQueue = playbackController.getQueue();
        return queueFragment
                .data("queue", updatedQueue)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl);
    }

    // -------------------------
    // Playlist / TBODY fragments
    // -------------------------
    @GET
    @Path("/playlists-fragment")
    @Blocking
    public String playlistsFragment() {
        return playlistFragment
                .data("playlists", playbackController.getPlaylists())
                .render();
    }

    @GET
    @Path("/playlist-view/{id}")
    @Blocking
    public String getPlaylistView(@PathParam("id") Long id) {
        long playlistId = id == null ? 0L : id;
        Playlist playlist = playbackController.findPlaylist(playlistId);
        String name = playlistId == 0 ? "All Songs" : (playlist != null ? playlist.getName() : "Playlist not found");

        return playlistView
                .data("playlistId", playlistId)
                .data("playlistName", name)
                .render();
    }

    @GET
    @Path("/tbody/{id}")
    @Blocking
    public String getPlaylistTbody(
            @PathParam("id") Long id,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {

        try {
            long playlistId = (id == null) ? 0L : id;
            List<Song> allSongs = (playlistId == 0)
                    ? playbackController.getSongs()
                    : Optional.ofNullable(playbackController.findPlaylist(playlistId))
                            .map(Playlist::getSongs)
                            .orElse(new ArrayList<>());

            if (offset == null || offset < 0) {
                offset = 0;
            }
            if (limit == null || limit <= 0) {
                limit = 20;
            }

            if (offset > allSongs.size()) {
                offset = Math.max(allSongs.size() - limit, 0);
            }

            int toIndex = Math.min(offset + limit, allSongs.size());
            List<Song> page = allSongs.isEmpty() ? new ArrayList<>() : allSongs.subList(offset, toIndex);

            int prevOffset = Math.max(offset - limit, 0);
            int nextOffset = offset + limit;
            int totalPages = (int) Math.ceil(allSongs.size() / (double) limit);
            int currentPage = (allSongs.isEmpty()) ? 1 : offset / limit + 1;

            Song currentSong = playbackController.getCurrentSong();
            boolean isPlaying = playbackController.getState() != null && playbackController.getState().isPlaying();

            return playlistTbody
                    .data("playlistId", playlistId)
                    .data("songs", page)
                    .data("currentSong", currentSong)
                    .data("isPlaying", isPlaying)
                    .data("offset", offset)
                    .data("limit", limit)
                    .data("totalSongs", allSongs.size())
                    .data("prevOffset", prevOffset)
                    .data("nextOffset", nextOffset)
                    .data("currentPage", currentPage)
                    .data("totalPages", totalPages)
                    .data("formatDate", (Function<Object, String>) this::formatDate)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                    .render();
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }

    }

    @GET
    @Path("/queue-fragment")
    @Blocking
    public String getQueueFragment(@QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        if (offset == null) {
            offset = 0;
        }
        if (limit == null) {
            limit = 50;
        }

        List<Song> queue = playbackController.getQueue();
        int toIndex = Math.min(offset + limit, queue.size());
        List<Song> page = queue.subList(offset, toIndex);

        return queueFragment
                .data("queue", page)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", offset)
                .data("limit", limit)
                .data("totalQueueSize", queue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .render();
    }
}
