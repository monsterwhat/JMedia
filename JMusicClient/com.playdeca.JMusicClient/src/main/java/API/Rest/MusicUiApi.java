package API.Rest;
 
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Song;
import Services.PlaylistService;
import Services.SongService;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List; 
import java.util.Set;
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

    private List<Integer> getPaginationNumbers(int currentPage, int totalPages) {
        Set<Integer> pageNumbers = new java.util.LinkedHashSet<>(); // Corrected: declare as Set

        // Always add page 1 and the last page
        pageNumbers.add(1);
        pageNumbers.add(totalPages);

        // Add pages around the current page
        for (int i = currentPage - 2; i <= currentPage + 2; i++) {
            if (i > 0 && i <= totalPages) {
                pageNumbers.add(i);
            }
        }

        List<Integer> sortedPageNumbers = new ArrayList<>(pageNumbers);
        java.util.Collections.sort(sortedPageNumbers);

        List<Integer> result = new ArrayList<>();
        int last = 0;
        for (int number : sortedPageNumbers) {
            if (last != 0 && number - last > 1) {
                result.add(-1); // Ellipsis
            }
            result.add(number);
            last = number;
        }
        return result;
    }

    // -------------------------
    // Queue actions
    // -------------------------
    // DTO for queue fragment response
    public record QueueFragmentResponse(String html, int totalQueueSize) {

    }

    @POST
    @Path("/playback/queue-all/{id}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Change to JSON
    public QueueFragmentResponse queueAllSongsUi(@PathParam("id") Long id) { // Change return type
        // Call the JSON API to do the actual queuing
        Response apiResponse = queueAPI.queueAllSongs(id);

        // Then return the updated queue fragment for HTMX
        List<Song> updatedQueue = playbackController.getQueue();

        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .render();

        return new QueueFragmentResponse(html, updatedQueue.size()); // Return DTO
    }

    @POST
    @Path("/queue/skip-to/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Change to JSON
    public QueueFragmentResponse skipToQueueIndexUi(@PathParam("index") int index) { // Change return type
        queueAPI.skipToQueueIndex(index);

        List<Song> updatedQueue = playbackController.getQueue();
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .render();

        return new QueueFragmentResponse(html, updatedQueue.size()); // Return DTO
    }

    @POST
    @Path("/queue/remove/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Change to JSON
    public QueueFragmentResponse removeFromQueueUi(@PathParam("index") int index) { // Change return type
        queueAPI.removeFromQueue(index);

        List<Song> updatedQueue = playbackController.getQueue();
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .render();

        return new QueueFragmentResponse(html, updatedQueue.size()); // Return DTO
    }

    @POST
    @Path("/queue/clear")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Change to JSON
    public QueueFragmentResponse clearQueueUi() { // Change return type
        queueAPI.clearQueue();

        List<Song> updatedQueue = playbackController.getQueue();
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong())
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .render();

        return new QueueFragmentResponse(html, updatedQueue.size()); // Return DTO
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
                @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
                @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
                @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {
    
            try {
                long playlistId = (id == null) ? 0L : id;
                
                List<Song> paginatedSongs;
                long totalSongs;
    
                if (playlistId == 0) {
                    SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search);
                    paginatedSongs = result.songs();
                    totalSongs = result.totalCount();
                } else {
                    PlaylistService.PaginatedPlaylistSongs result = playbackController.getSongsByPlaylist(playlistId, page, limit, search);
                    paginatedSongs = result.songs();
                    totalSongs = result.totalCount();
                }
    
                if (totalSongs == 0) {
                    return "<tr><td colspan='5' class='has-text-centered'>No songs found.</td></tr>";
                }
                int totalPages = (int) Math.ceil((double) totalSongs / limit);
                int currentPage = Math.max(1, Math.min(page, totalPages));
    
                Song currentSong = playbackController.getCurrentSong();
                boolean isPlaying = playbackController.getState() != null && playbackController.getState().isPlaying();
    
                List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);
    
                return playlistTbody
                        .data("playlistId", playlistId)
                        .data("songs", paginatedSongs)
                        .data("currentSong", currentSong)
                        .data("isPlaying", isPlaying)
                        .data("formatDate", (Function<Object, String>) this::formatDate)
                        .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                        .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                        .data("limit", limit)
                        .data("currentPage", currentPage)
                        .data("totalPages", totalPages)
                        .data("pageNumbers", pageNumbers)
                        .data("search", search) // Pass search term back to template for pagination links
                        .render();
            } catch (Exception e) {
                System.out.println("Error: " + e.getLocalizedMessage());
                return null;
            }
        }
    // Helper record to pass song and its index to the template
    public record SongWithIndex(Song song, int index) {

    }

    @GET
    @Path("/queue-fragment")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public QueueFragmentResponse getQueueFragment(
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {

        PlaybackController.PaginatedQueue paginatedQueue = playbackController.getQueuePage(page, limit);
        List<Song> queuePage = paginatedQueue.songs();
        int totalQueueSize = paginatedQueue.totalSize();

        // The template needs the index of each song *within the full queue*.
        int offset = (page - 1) * limit;
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < queuePage.size(); i++) {
            queueWithIndex.add(new SongWithIndex(queuePage.get(i), offset + i));
        }

        int totalPages = (int) Math.ceil((double) totalQueueSize / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages)); // Sanitize page number
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong())
                .data("totalQueueSize", totalQueueSize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("limit", limit)
                .render();

        return new QueueFragmentResponse(html, totalQueueSize);
    }

    @GET
    @Path("/add-to-playlist-dialog/{songId}")
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    public String getAddToPlaylistDialog(@PathParam("songId") Long songId) {
        List<Playlist> playlists = playbackController.getPlaylistsWithSongStatus(songId);
        return addToPlaylistDialog
                .data("playlists", playlists)
                .data("songId", songId)
                .render();
    }
}
