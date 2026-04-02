package API.Rest;

import Controllers.PlaybackController;
import Models.PlaybackHistory;
import Models.Playlist;
import Models.Profile;
import Models.Song;
import Services.PlaylistService;
import Services.ProfileService;
import Services.SongService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
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

    @Inject
    SongService songService;

    @Inject
    private PlaylistService playlistService;

    @Inject
    private ProfileService profileService;

    @Inject
    Services.PlaybackHistoryService playbackHistoryService;

    // Templates
    @Inject
    Template playlistFragment;
    @Inject
    @io.quarkus.qute.Location("playlistView.html")
    Template playlistView;
    @Inject
    @io.quarkus.qute.Location("playlistTableBodyFragment.html")
    Template playlistTableBodyFragment;

    @Inject
    @io.quarkus.qute.Location("mobilePlaylistTableBodyFragment.html")
    Template mobilePlaylistTableBodyFragment;
    @Inject
    Template queueFragment;
    @Inject
    Template addToPlaylistDialog;
    @Inject
    Template searchSuggestionsFragment;

    @Inject
    Template searchResultsView;

    @Inject
    Template allSongsFragment;

    @Inject
    Template historyFragment;

    @Inject
    @io.quarkus.qute.Location("mobilePlaylistFragment.html")
    Template mobilePlaylistFragment;

    @Inject
    Template mobileQueueFragment;

    @Inject
    @io.quarkus.qute.Location("songDetailFragment.html")
    Template songDetailFragment;

    @Inject
    @io.quarkus.qute.Location("albumArtistFragment.html")
    Template albumArtistFragment;

    @Inject
    @io.quarkus.qute.Location("albumFragment.html")
    Template albumFragment;

    @Inject
    Template mobileHistoryFragment;

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
    public record QueueFragmentResponse(String html, String mobileHtml, int totalQueueSize) {

    }

    // DTO for history fragment response
    public record HistoryFragmentResponse(String html, int totalHistorySize) {

    }

    @POST
    @Path("/playback/queue-all/{profileId}/{id}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse queueAllSongsUi(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) { // Reverted return type
        // Call the JSON API to do the actual queuing
        Response apiResponse = queueAPI.queueAllSongs(profileId, id, headers);

        // Then return the updated queue fragment for HTMX
        List<Song> updatedQueue = playbackController.getQueue(profileId);

        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) updatedQueue.size() / 50); // Assuming limit is 50 for this context
        int currentPage = 1; // After queueing all, we assume we are on the first page
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong(profileId))
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        String mobileHtml = mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        return new QueueFragmentResponse(html, mobileHtml, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/skip-to/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse skipToQueueIndexUi(@PathParam("profileId") Long profileId, @PathParam("index") int index, @Context HttpHeaders headers) { // Reverted return type
        queueAPI.skipToQueueIndex(profileId, index, headers);

        List<Song> updatedQueue = playbackController.getQueue(profileId);
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) updatedQueue.size() / 50); // Assuming limit is 50 for this context
        int currentPage = (index / 50) + 1; // Calculate current page based on skipped index
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong(profileId))
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        String mobileHtml = mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        return new QueueFragmentResponse(html, mobileHtml, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/remove/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse removeFromQueueUi(@PathParam("profileId") Long profileId, @PathParam("index") int index, @Context HttpHeaders headers) { // Reverted return type
        queueAPI.removeFromQueue(profileId, index, headers);

        List<Song> updatedQueue = playbackController.getQueue(profileId);
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) updatedQueue.size() / 50); // Assuming limit is 50 for this context
        int currentPage = (index / 50) + 1; // Calculate current page based on removed index (approximate)
        if (currentPage > totalPages && totalPages > 0) {
            currentPage = totalPages; // Adjust if removed from last page
        } else if (totalPages == 0) {
            currentPage = 1;
        }
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong(profileId))
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        String mobileHtml = mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        return new QueueFragmentResponse(html, mobileHtml, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/clear/{profileId}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse clearQueueUi(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) { // Reverted return type
        queueAPI.clearQueue(profileId, headers);

        List<Song> updatedQueue = playbackController.getQueue(profileId);
        // Create a list of SongWithIndex objects
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < updatedQueue.size(); i++) {
            queueWithIndex.add(new SongWithIndex(updatedQueue.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) updatedQueue.size() / 50); // Assuming limit is 50 for this context
        int currentPage = 1; // After clearing, we are on the first page
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = queueFragment
                .data("queue", queueWithIndex)
                .data("currentSong", playbackController.getCurrentSong(profileId))
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        String mobileHtml = mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", 0)
                .data("limit", 50)
                .data("totalQueueSize", updatedQueue.size())
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .render();

        return new QueueFragmentResponse(html, mobileHtml, updatedQueue.size()); // Reverted to return DTO
    }

    // -------------------------
    // Playlist / TBODY fragments
    // -------------------------
    @GET
    @Path("/playlists-fragment/{profileId}")
    @Blocking
    public String playlistsFragment(@PathParam("profileId") Long profileId) {
        List<Playlist> playlists = getPlaylistsByProfileId(profileId);
        return playlistFragment
                .data("playlists", playlists) // Profile-specific playlists
                .data("profileId", profileId)
                .render();
    }

    @GET
    @Path("/mobile-playlists-fragment/{profileId}")
    @Blocking
    public String mobilePlaylistsFragment(@PathParam("profileId") Long profileId) {
        List<Playlist> playlists = getPlaylistsByProfileId(profileId);
        return mobilePlaylistFragment
                .data("playlists", playlists)
                .data("profileId", profileId)
                .render();
    }

    @GET
    @Path("/playlist-view/{profileId}/{id}")
    @Blocking
    public String getPlaylistView(
            @PathParam("profileId") Long profileId,
            @PathParam("id") Long id,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {

        long playlistId = id == null ? 0L : id;
        System.out.println("DEBUG: Rendering playlist view with profileId=" + profileId + ", playlistId=" + playlistId);

        // For playlist view, we need to check if the user has access to this specific playlist
        Playlist playlist = null;
        String name;
        if (playlistId == 0) {
            // All Songs - always accessible 
            name = "All Songs";
            playlist = null; // No specific playlist for All Songs
        } else {
            // Check access to specific playlist
            playlist = playlistService.find(id);
            if (playlist != null) {
                name = playlist.getName();
                System.err.println("DEBUG: Playlist found - ID: " + id + ", Name: " + name + ", Profile: " + profileId);
            } else {
                name = "Playlist not found";
                // Debug logging
                System.err.println("DEBUG: Playlist not found for ID: " + id + " when requested by profile: " + profileId);
            }
        }

        List<Song> paginatedSongs;
        long totalSongs;

        if (playlistId == 0) {
            SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search, sortBy, sortDirection);
            paginatedSongs = result.songs();
            totalSongs = result.totalCount();
        } else {
            PlaylistService.PaginatedPlaylistSongs result = playbackController.getSongsByPlaylist(playlistId, page, limit, search, sortBy, sortDirection);
            paginatedSongs = result.songs();
            totalSongs = result.totalCount();
        }

        int totalPages = (int) Math.ceil((double) totalSongs / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages));

        Song currentSong = playbackController.getCurrentSong(profileId);
        boolean isPlaying = playbackController.getState(profileId) != null && playbackController.getState(profileId).isPlaying();

        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        return playlistView
                .data("playlistId", String.valueOf(playlistId))
                .data("playlistName", name)
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
                .data("search", search)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("profileId", String.valueOf(profileId))
                .render();
    }

    @GET
    @Path("/tbody/{profileId}/{id}")
    @Blocking
    public String getPlaylistTbody(
            @PathParam("profileId") Long profileId,
            @PathParam("id") Long id,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {

        try {
            long playlistId = (id == null) ? 0L : id;

            List<Song> paginatedSongs;
            long totalSongs;

            if (playlistId == 0) {
                SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search, sortBy, sortDirection);
                paginatedSongs = result.songs();
                totalSongs = result.totalCount();
            } else {
                PlaylistService.PaginatedPlaylistSongs result = playbackController.getSongsByPlaylist(playlistId, page, limit, search, sortBy, sortDirection);
                paginatedSongs = result.songs();
                totalSongs = result.totalCount();
            }

            if (totalSongs == 0) {
                return "<tr><td colspan='6' class='has-text-centered'>No songs found.</td></tr>";
            }
            int totalPages = (int) Math.ceil((double) totalSongs / limit);
            int currentPage = Math.max(1, Math.min(page, totalPages));

            Song currentSong = playbackController.getCurrentSong(profileId);
            boolean isPlaying = playbackController.getState(profileId) != null && playbackController.getState(profileId).isPlaying();

            List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

            return playlistTableBodyFragment
                    .data("playlistId", String.valueOf(playlistId))
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
                    .data("sortBy", sortBy) // Pass sortBy back to template for pagination links
                    .data("sortDirection", sortDirection) // Pass sortDirection back to template for pagination links
                    .data("profileId", String.valueOf(profileId))
                    .render();
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }

    @GET
    @Path("/mobile-tbody/{profileId}/{id}")
    @Blocking
    public String getMobilePlaylistTbody(
            @PathParam("profileId") Long profileId,
            @PathParam("id") Long id,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("24") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {

        try {
            long playlistId = (id == null) ? 0L : id;

            List<Song> paginatedSongs;
            long totalSongs;

            if (playlistId == 0) {
                SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search, sortBy, sortDirection);
                paginatedSongs = result.songs();
                totalSongs = result.totalCount();
            } else {
                PlaylistService.PaginatedPlaylistSongs result = playbackController.getSongsByPlaylist(playlistId, page, limit, search, sortBy, sortDirection);
                paginatedSongs = result.songs();
                totalSongs = result.totalCount();
            }

            if (totalSongs == 0) {
                return "<div class='p-6 has-text-centered opacity-50'><i class='pi pi-info-circle mb-3' style='font-size: 2rem;'></i><p>No songs found in this library.</p></div>";
            }
            int totalPages = (int) Math.ceil((double) totalSongs / limit);
            int currentPage = Math.max(1, Math.min(page, totalPages));

            Song currentSong = playbackController.getCurrentSong(profileId);
            boolean isPlaying = playbackController.getState(profileId) != null && playbackController.getState(profileId).isPlaying();

            List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

            return mobilePlaylistTableBodyFragment
                    .data("playlistId", String.valueOf(playlistId))
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
                    .data("search", search)
                    .data("sortBy", sortBy)
                    .data("sortDirection", sortDirection)
                    .data("profileId", String.valueOf(profileId))
                    .render();
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }

    // Helper record to pass song and its index to the template
    public record SongWithIndex(Song song, int index) {

    }

    // Helper record to pass history entry and its index to the template
    public record HistoryWithIndex(Models.PlaybackHistory history, int index) {

    }

    @GET
    @Path("/queue-fragment/{profileId}")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse getQueueFragment( // Reverted return type
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {

        PlaybackController.PaginatedQueue paginatedQueue = playbackController.getQueuePage(page, limit, profileId, search);
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
                .data("currentSong", playbackController.getCurrentSong(profileId))
                .data("profileId", profileId)
                .data("offset", offset)
                .data("limit", limit)
                .data("totalQueueSize", totalQueueSize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .render();

        String mobileHtml = mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", offset)
                .data("limit", limit)
                .data("totalQueueSize", totalQueueSize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .render();

        return new QueueFragmentResponse(html, mobileHtml, totalQueueSize); // Reverted to return DTO
    }

    @GET
    @Path("/mobile-queue-fragment/{profileId}")
    @Blocking
    public String getMobileQueueFragment(
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {

        PlaybackController.PaginatedQueue paginatedQueue = playbackController.getQueuePage(page, limit, profileId, search);
        List<Song> queuePage = paginatedQueue.songs();
        int totalQueueSize = paginatedQueue.totalSize();

        // The template needs the index of each song *within the full queue*.
        int offset = (page - 1) * limit;
        List<SongWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < queuePage.size(); i++) {
            queueWithIndex.add(new SongWithIndex(queuePage.get(i), offset + i));
        }

        int totalPages = (int) Math.ceil((double) totalQueueSize / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        return mobileQueueFragment
                .data("queue", queueWithIndex)
                .data("profileId", profileId)
                .data("offset", offset)
                .data("limit", limit)
                .data("totalQueueSize", totalQueueSize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .render();
    }

    @GET
    @Path("/add-to-playlist-dialog/{profileId}/{songId}")
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    public String getAddToPlaylistDialog(@PathParam("profileId") Long profileId, @PathParam("songId") Long songId) {
        List<Playlist> playlists = getPlaylistsByProfileId(profileId);
        // Set song status for each playlist
        if (songId != null) {
            List<Long> playlistIdsWithSong = playlistService.findAll().stream()
                    .filter(p -> p.getSongs().stream().anyMatch(s -> s.id.equals(songId)))
                    .map(p -> p.id)
                    .toList();

            for (Playlist p : playlists) {
                p.setContainsSong(playlistIdsWithSong.contains(p.id));
            }
        }
        return addToPlaylistDialog
                .data("playlists", playlists)
                .data("songId", songId)
                .data("profileId", profileId)
                .render();
    }

    @POST
    @Path("/search-suggestions/{profileId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public String getSearchSuggestions(@PathParam("profileId") Long profileId, @FormParam("searchQuery") String searchQuery) {
        try {
            if (searchQuery == null || searchQuery.isBlank()) {
                return ""; // Return empty if no search query
            }

            // Fetch a limited number of suggestions
            SongService.PaginatedSongs result = songService.findAll(1, 5, searchQuery, "title", "asc");
            List<Song> suggestions = result.songs();

            return searchSuggestionsFragment
                    .data("suggestions", suggestions)
                    .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                    .data("profileId", profileId)
                    .render();
        } catch (Exception e) {
            System.err.println("[ERROR] Error fetching search suggestions for query: '" + searchQuery + "' - " + e.getMessage());
            e.printStackTrace();
            // Return an empty string or an error message to the frontend
            return "<div class=\"dropdown-item\">Error fetching suggestions.</div>";
        }
    }

    @GET
    @Path("/search-results/{profileId}")
    @Blocking
    public String getSearchResults(
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {
        return searchResultsView
                .data("searchQuery", search)
                .data("profileId", profileId)
                .render();
    }

    @GET
    @Path("/songs-fragment/{profileId}")
    @Blocking
    public String getSongsFragment(
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {

        SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search, sortBy, sortDirection);
        List<Song> songs = result.songs();
        long totalSongs = result.totalCount();

        if (totalSongs == 0) {
            return "<tr><td colspan='6' class='has-text-centered'>No songs found.</td></tr>";
        }

        int totalPages = (int) Math.ceil((double) totalSongs / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages));

        Song currentSong = playbackController.getCurrentSong(profileId);
        boolean isPlaying = playbackController.getState(profileId) != null && playbackController.getState(profileId).isPlaying();

        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        return allSongsFragment
                .data("songs", songs)
                .data("currentSong", currentSong)
                .data("isPlaying", isPlaying)
                .data("formatDate", (Function<Object, String>) this::formatDate)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("limit", limit)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("profileId", profileId)
                .render();
    }

    // -------------------------
    // History actions
    // -------------------------
    @GET
    @Path("/history-fragment/{profileId}")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public HistoryFragmentResponse getHistoryFragment(
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {

        List<PlaybackHistory> historyPage = playbackHistoryService.getHistory(page, limit, profileId, search);

        // Get total count for pagination
        long totalHistorySize = playbackHistoryService.getHistoryCount(profileId, search);

        // Create a list of HistoryWithIndex objects
        List<HistoryWithIndex> historyWithIndex = new ArrayList<>();
        for (int i = 0; i < historyPage.size(); i++) {
            historyWithIndex.add(new HistoryWithIndex(historyPage.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) totalHistorySize / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages)); // Sanitize page number
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        String html = historyFragment
                .data("history", historyWithIndex)
                .data("profileId", profileId)
                .data("offset", (page - 1) * limit)
                .data("limit", limit)
                .data("totalHistorySize", (int) totalHistorySize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("formatDate", (Function<Object, String>) this::formatDate)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .render();

        return new HistoryFragmentResponse(html, (int) totalHistorySize);
    }

    @GET
    @Path("/mobile-history-fragment/{profileId}")
    @Blocking
    public String getMobileHistoryFragment(
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search) {

        List<PlaybackHistory> historyPage = playbackHistoryService.getHistory(page, limit, profileId, search);

        // Get total count for pagination
        long totalHistorySize = playbackHistoryService.getHistoryCount(profileId, search);

        // Create a list of HistoryWithIndex objects
        List<HistoryWithIndex> historyWithIndex = new ArrayList<>();
        for (int i = 0; i < historyPage.size(); i++) {
            historyWithIndex.add(new HistoryWithIndex(historyPage.get(i), i));
        }

        int totalPages = (int) Math.ceil((double) totalHistorySize / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        return mobileHistoryFragment
                .data("history", historyWithIndex)
                .data("profileId", profileId)
                .data("offset", (page - 1) * limit)
                .data("limit", limit)
                .data("totalHistorySize", (int) totalHistorySize)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("formatDate", (Function<Object, String>) this::formatDate)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("search", search)
                .render();
    }

    // Note: Individual history removal and clearing are no longer supported
// History is now read-only, similar to recently played lists in other music apps
    private List<Playlist> getPlaylistsByProfileId(Long profileId) {
        if (profileId == null) {
            return new java.util.ArrayList<>();
        }

        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return new java.util.ArrayList<>();
        }

        // Get playlists for this specific profile (user's playlists + global playlists)
        List<Playlist> playlists = playlistService.findAllForProfile(profile);
        System.err.println("DEBUG: Found " + playlists.size() + " playlists for profile " + profileId);
        // for (Playlist p : playlists) {
        //     System.err.println("DEBUG: Playlist ID=" + p.id + ", Name=" + p.getName() + ", Global=" + p.getIsGlobal() + ", Profile=" + (p.getProfile() != null ? p.getProfile().id : null));
        // }
        return playlists;
    }

    @GET
    @Path("/genres")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDistinctGenres() {
        List<String> genres = songService.getDistinctGenres();
        return Response.ok(genres).build();
    }

    @GET
    @Path("/song-detail/{profileId}/{songId}")
    @Blocking
    public String getSongDetail(
            @PathParam("profileId") Long profileId,
            @PathParam("songId") Long songId) {
        
        Song song = Song.findById(songId);
        if (song == null) {
            return "<div class='p-6 has-text-centered'><p>Song not found</p></div>";
        }

        List<Song> similarSongs = new ArrayList<>();
        String genre = song.getGenre();
        
        if (genre != null && !genre.isBlank()) {
            String[] genreTokens = genre.toLowerCase().split("\\s+");
            int bpm = song.getBpm();
            int bpmTolerance = 10;
            
            List<Song> allSongs = Song.list("id != ?1", songId);
            similarSongs = allSongs.stream()
                .filter(s -> s.getGenre() != null && !s.getGenre().isEmpty())
                .filter(s -> {
                    String targetGenre = s.getGenre().toLowerCase();
                    for (String token : genreTokens) {
                        if (targetGenre.contains(token)) {
                            return true;
                        }
                    }
                    return false;
                })
                .filter(s -> {
                    if (bpm == 0) return true;
                    int songBpm = s.getBpm();
                    return songBpm > 0 && Math.abs(songBpm - bpm) <= bpmTolerance;
                })
                .limit(7)
                .toList();
        }

        return songDetailFragment
                .data("song", song)
                .data("albumArtist", song.getAlbumArtist())
                .data("similarSongs", similarSongs)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    public record AlbumInfo(String name, String artwork, String year) {}
    
    public record RecommendedArtist(String name, String artwork, int songCount) {}
    
    // Builder for RecommendedArtist since we need to modify songCount during processing
    private static class RecommendedArtistBuilder {
        private String name;
        private String artwork;
        private int songCount;
        
        public RecommendedArtistBuilder(String name, String artwork, int songCount) {
            this.name = name;
            this.artwork = artwork;
            this.songCount = songCount;
        }
        
        public RecommendedArtistBuilder songCount(int songCount) {
            this.songCount = songCount;
            return this;
        }
        
        public RecommendedArtist build() {
            return new RecommendedArtist(name, artwork, songCount);
        }
    }

    @GET
    @Path("/album-artist/{profileId}/{artistName}")
    @Blocking
    public String getAlbumArtistPage(
            @PathParam("profileId") Long profileId,
            @PathParam("artistName") String artistName) {
        
        if (artistName == null || artistName.isBlank()) {
            return "<div class='p-6 has-text-centered'><p>Artist not found</p></div>";
        }

        String normalizedArtist = artistName.trim();
        String searchPattern = "%" + normalizedArtist.toLowerCase() + "%";

        // Find songs where artist OR albumArtist matches (case-insensitive)
        List<Song> allSongs = Song.list(
            "LOWER(artist) LIKE ?1 OR LOWER(albumArtist) LIKE ?1", 
            searchPattern
        );

        if (allSongs.isEmpty()) {
            return "<div class='p-6 has-text-centered'><p>No songs found for artist: " + artistName + "</p></div>";
        }

        // Get unique albums with their artwork (first song's artwork for each album)
        List<AlbumInfo> albums = allSongs.stream()
            .filter(s -> s.getAlbum() != null && !s.getAlbum().isBlank())
            .collect(java.util.stream.Collectors.groupingBy(
                s -> s.getAlbum(),
                java.util.stream.Collectors.minBy((s1, s2) -> {
                    int t1 = s1.getTrackNumber();
                    int t2 = s2.getTrackNumber();
                    return Integer.compare(t1, t2);
                })
            ))
            .entrySet().stream()
            .map(entry -> {
                Song firstSong = entry.getValue().orElse(null);
                return new AlbumInfo(
                    entry.getKey(),
                    firstSong != null ? firstSong.getArtworkBase64() : null,
                    firstSong != null ? firstSong.getReleaseDate() : null
                );
            })
            .sorted((a, b) -> (a.year() != null ? a.year() : "").compareTo(b.year() != null ? b.year() : ""))
            .toList();

        // Get artist artwork from first song
        String artistArtwork = allSongs.stream()
            .filter(s -> s.getArtworkBase64() != null && !s.getArtworkBase64().isBlank())
            .findFirst()
            .map(Song::getArtworkBase64)
            .orElse(null);

        // Get recommended artists (artists with similar genres)
        List<RecommendedArtist> recommendedArtists = new ArrayList<>();
        if (!allSongs.isEmpty()) {
            // Get unique genres from the artist's songs
            Set<String> artistGenres = allSongs.stream()
                .filter(s -> s.getGenre() != null && !s.getGenre().isBlank())
                .map(Song::getGenre)
                .collect(Collectors.toSet());
            
            if (!artistGenres.isEmpty()) {
                // Find other artists with similar genres
                List<Song> otherSongs = Song.list("id NOT IN (:songIds)", 
                    new HashMap<String, Object>() {{
                        put("songIds", allSongs.stream().map(s -> s.id).toList());
                    }});
                
                Map<String, RecommendedArtistBuilder> artistScores = new HashMap<>();
                
                for (Song song : otherSongs) {
                     if (song.getGenre() != null && !song.getGenre().isBlank()) {
                         String songGenre = song.getGenre().toLowerCase();
                         boolean matches = artistGenres.stream()
                             .anyMatch(genre -> songGenre.contains(genre.toLowerCase()) || 
                                          genre.toLowerCase().contains(songGenre));
                     
                         if (matches) {
                             String artistFromSong = song.getArtist();
                             if (artistFromSong != null && !artistFromSong.isBlank()) {
                                 artistScores.computeIfAbsent(artistFromSong, name -> 
                                     new RecommendedArtistBuilder(
                                         name,
                                         song.getArtworkBase64(),
                                         0
                                     )
                                 );
                                 RecommendedArtistBuilder builder = artistScores.get(artistFromSong);
                                 builder.songCount(builder.songCount + 1);
                             }
                         }
                     }
                 }
                
                // Sort by song count and take top 7
                recommendedArtists = artistScores.values().stream()
                    .map(builder -> builder.build())
                    .sorted((a, b) -> Integer.compare(b.songCount(), a.songCount()))
                    .limit(7)
                    .toList();
            }
        }

        return albumArtistFragment
                .data("artistName", normalizedArtist)
                .data("artistArtwork", artistArtwork)
                .data("songs", allSongs)
                .data("albums", albums)
                .data("songCount", allSongs.size())
                .data("albumCount", albums.size())
                .data("recommendedArtists", recommendedArtists)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/album/{profileId}/{albumName}")
    @Blocking
    public String getAlbumPage(
            @PathParam("profileId") Long profileId,
            @PathParam("albumName") String albumName) {
        
        if (albumName == null || albumName.isBlank()) {
            return "<div class='p-6 has-text-centered'><p>Album not found</p></div>";
        }

        String normalizedAlbum = albumName.trim();
        String searchPattern = "%" + normalizedAlbum.toLowerCase() + "%";

        // Find songs where album matches (case-insensitive)
        List<Song> allSongs = Song.list(
            "LOWER(album) LIKE ?1", 
            searchPattern
        );

        if (allSongs.isEmpty()) {
            return "<div class='p-6 has-text-centered'><p>No songs found for album: " + albumName + "</p></div>";
        }

        // Sort by track number
        allSongs = allSongs.stream()
            .sorted((s1, s2) -> {
                int t1 = s1.getTrackNumber();
                int t2 = s2.getTrackNumber();
                if (t1 == 0 && t2 == 0) return 0;
                if (t1 == 0) return 1;
                if (t2 == 0) return -1;
                return Integer.compare(t1, t2);
            })
            .toList();

        // Get artist name from first song
        String artistName = allSongs.stream()
            .filter(s -> s.getArtist() != null && !s.getArtist().isBlank())
            .findFirst()
            .map(Song::getArtist)
            .orElse("Unknown Artist");

        // Get album artwork from first song
        String albumArtwork = allSongs.stream()
            .filter(s -> s.getArtworkBase64() != null && !s.getArtworkBase64().isBlank())
            .findFirst()
            .map(Song::getArtworkBase64)
            .orElse(null);

        // Get year from first song
        String year = allSongs.stream()
            .filter(s -> s.getReleaseDate() != null && !s.getReleaseDate().isBlank())
            .findFirst()
            .map(Song::getReleaseDate)
            .orElse("");

        // Calculate total duration
        int totalSeconds = allSongs.stream()
            .mapToInt(Song::getDurationSeconds)
            .sum();
        String totalDuration = this.formatDuration(totalSeconds);

        // Get first song ID for play button
        Long firstSongId = allSongs.isEmpty() ? null : allSongs.get(0).id;
        // Get recommended artists (artists from other albums by same artist, or similar genres)
        // Get recommended artists (simple approach: just get some other artists)
        List<RecommendedArtist> recommendedArtists = new ArrayList<>();
        if (!allSongs.isEmpty()) {
            // Get the primary artist for this album
            // Get the primary artist for this album/artist
            String primaryArtist = artistName;
            
            // Find other albums by the same artist (simplified - just get some other songs by same artist)
            List<Song> otherArtistSongs = Song.list(
                "LOWER(artist) = ?1 AND LOWER(album) <> ?2",
                primaryArtist.toLowerCase(),
                normalizedAlbum.toLowerCase()
            );
            // Get some other artists (simple approach)
            List<Song> otherSongs = Song.list(
                "LOWER(artist) <> ?1", 
                primaryArtist.toLowerCase()
            );
            
            // If we don't have enough from same artist, get some similar genre artists
            if (otherArtistSongs.size() < 3) {
                // Get genres from this album
                Set<String> albumGenres = allSongs.stream()
                    .filter(s -> s.getGenre() != null && !s.getGenre().isBlank())
                    .map(s -> s.getGenre().toLowerCase())
                    .collect(Collectors.toSet());
                if (!albumGenres.isEmpty()) {
                    // Find songs with similar genres but different artists
                    List<Song> similarGenreSongs = Song.<Song>list(
                        "LOWER(artist) <> ?1",
                        primaryArtist.toLowerCase()
                    ).stream()
                    .filter(s -> s.getGenre() != null && !s.getGenre().isBlank())
                    .filter(song -> {
                        String songGenre = song.getGenre().toLowerCase();
                        return albumGenres.stream()
                            .anyMatch(genre -> songGenre.contains(genre) || 
                                         genre.contains(songGenre));
                    })
                    .limit(20)
                    .toList();
                    
                    // Add these to our recommendation pool
                    otherArtistSongs.addAll(similarGenreSongs);
                }
            }
            
            // Build artist data from other songs
            Map<String, int[]> artistData = new HashMap<>(); // [songCount, hasArtwork]
            
            for (Song song : otherSongs) {
                String artist = song.getArtist();
                String artwork = song.getArtworkBase64();
                
                if (artist != null && !artist.isBlank() && !artist.equalsIgnoreCase(primaryArtist)) {
                    if (!artistData.containsKey(artist)) {
                        artistData.put(artist, new int[]{0, 0}); // [songCount, hasArtwork]
                    }
                    int[] data = artistData.get(artist);
                    data[0]++; // increment songCount
                    // Mark if we have artwork for this artist
                    if (data[1] == 0 && artwork != null && !artwork.isBlank()) {
                        data[1] = 1; // mark that we have artwork
                    }
                }
            }
            
            // Build recommended artists from the collected songs
            if (!otherArtistSongs.isEmpty()) {
                Map<String, int[]> artistData2 = new HashMap<>(); // [songCount, hasArtwork]
                
                for (Song song : otherArtistSongs) {
                    String artist = song.getArtist();
                    String artwork = song.getArtworkBase64();
                    
                    if (artist != null && !artist.isBlank() && !artist.equalsIgnoreCase(primaryArtist)) {
                        if (!artistData2.containsKey(artist)) {
                            artistData2.put(artist, new int[]{0, 0}); // [songCount, hasArtwork]
                        }
                        int[] data = artistData2.get(artist);
                        data[0]++; // increment songCount
                        // Mark if we have artwork for this artist
                        if (data[1] == 0 && artwork != null && !artwork.isBlank()) {
                            data[1] = 1; // mark that we have artwork
                        }
                    }
                }
                
                // Convert to RecommendedArtist objects and sort by song count
                List<RecommendedArtist> tempArtists = new ArrayList<>();
                for (Map.Entry<String, int[]> entry : artistData2.entrySet()) {
                    String artistNameEntry = entry.getKey();
                    int[] data = entry.getValue();
                    // Find artwork for this artist
                    String artwork = null;
                    if (data[1] == 1) {
                        for (Song song : otherArtistSongs) {
                            if (song.getArtist() != null && song.getArtist().equalsIgnoreCase(artistNameEntry)) {
                                artwork = song.getArtworkBase64();
                                break;
                            }
                        }
                    }
                    tempArtists.add(new RecommendedArtist(artistNameEntry, artwork, data[0]));
                }
                
                // Sort by song count descending and take top 7
                recommendedArtists = tempArtists.stream()
                    .sorted((a, b) -> Integer.compare(b.songCount(), a.songCount()))
                    .limit(7)
                    .collect(Collectors.toList());
            } else {
                // Convert to RecommendedArtist objects and sort by song count
                List<RecommendedArtist> tempArtists = new ArrayList<>();
                for (Map.Entry<String, int[]> entry : artistData.entrySet()) {
                    String artistNameEntry = entry.getKey();
                    int[] data = entry.getValue();
                    // Find artwork for this artist
                    String artwork = null;
                    if (data[1] == 1) {
                        for (Song song : otherSongs) {
                            if (song.getArtist() != null && song.getArtist().equalsIgnoreCase(artistNameEntry)) {
                                artwork = song.getArtworkBase64();
                                break;
                            }
                        }
                    }
                    tempArtists.add(new RecommendedArtist(artistNameEntry, artwork, data[0]));
                }
                
                // Sort by song count descending and take top 7
                recommendedArtists = tempArtists.stream()
                    .sorted((a, b) -> Integer.compare(b.songCount(), a.songCount()))
                    .limit(7)
                    .collect(Collectors.toList());
            }
        }

        return albumFragment
                .data("albumName", normalizedAlbum)
                .data("artistName", artistName)
                .data("albumArtwork", albumArtwork)
                .data("year", year)
                .data("songs", allSongs)
                .data("songCount", allSongs.size())
                .data("totalDuration", totalDuration)
                .data("firstSongId", firstSongId)
                .data("recommendedArtists", recommendedArtists)
                .data("artworkUrl", (Function<String, String>) this::artworkUrl)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    /**
     * Helper method to extract artist ID from a song
     * Since we don't have a direct artist entity, we'll use a hash of the artist name
     * as a pseudo-ID for recommendation purposes
     */
    private Long getArtistIdFromSong(Song song) {
        if (song == null || song.getArtist() == null) {
            return null;
        }
        // Simple hash-based ID generation for demonstration
        // In a real implementation, you'd have a proper Artist entity
        return (long) Math.abs(song.getArtist().hashCode());
    }
}
