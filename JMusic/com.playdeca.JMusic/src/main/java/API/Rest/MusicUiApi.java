package API.Rest;

import Controllers.PlaybackController;
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

    @Inject
    SongService songService;

    @Inject
    private PlaylistService playlistService;

    @Inject
    private ProfileService profileService;

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
    Template queueFragment;
    @Inject
    Template addToPlaylistDialog;
    @Inject
    Template searchSuggestionsFragment;

    @Inject
    Template searchResultsView;
    
    @Inject
    Template allSongsFragment;

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
    @Path("/playback/queue-all/{profileId}/{id}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse queueAllSongsUi(@PathParam("profileId") Long profileId, @PathParam("id") Long id) { // Reverted return type
        // Call the JSON API to do the actual queuing
        Response apiResponse = queueAPI.queueAllSongs(profileId, id);

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

        return new QueueFragmentResponse(html, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/skip-to/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse skipToQueueIndexUi(@PathParam("profileId") Long profileId, @PathParam("index") int index) { // Reverted return type
        queueAPI.skipToQueueIndex(profileId, index);

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

        return new QueueFragmentResponse(html, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/remove/{profileId}/{index}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse removeFromQueueUi(@PathParam("profileId") Long profileId, @PathParam("index") int index) { // Reverted return type
        queueAPI.removeFromQueue(profileId, index);

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

        return new QueueFragmentResponse(html, updatedQueue.size()); // Reverted to return DTO
    }

    @POST
    @Path("/queue/clear/{profileId}")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse clearQueueUi(@PathParam("profileId") Long profileId) { // Reverted return type
        queueAPI.clearQueue(profileId);

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

        return new QueueFragmentResponse(html, updatedQueue.size()); // Reverted to return DTO
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
    @Path("/playlist-view/{profileId}/{id}")
    @Blocking
    public String getPlaylistView(
            @PathParam("profileId") Long profileId,
            @PathParam("id") Long id,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {

        long playlistId = id == null ? 0L : id;
        
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
                .data("playlistId", playlistId)
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
                .data("profileId", profileId)
                .render();
    }

    @GET
    @Path("/tbody/{profileId}/{id}")
    @Blocking
    public String getPlaylistTbody(
            @PathParam("profileId") Long profileId,
            @PathParam("id") Long id,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
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
                return "<tr><td colspan='5' class='has-text-centered'>No songs found.</td></tr>";
            }
            int totalPages = (int) Math.ceil((double) totalSongs / limit);
            int currentPage = Math.max(1, Math.min(page, totalPages));

            Song currentSong = playbackController.getCurrentSong(profileId);
            boolean isPlaying = playbackController.getState(profileId) != null && playbackController.getState(profileId).isPlaying();

            List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

            return playlistTableBodyFragment
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
                    .data("sortBy", sortBy) // Pass sortBy back to template for pagination links
                    .data("sortDirection", sortDirection) // Pass sortDirection back to template for pagination links
                    .data("profileId", profileId)
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
    @Path("/queue-fragment/{profileId}")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON) // Reverted to APPLICATION_JSON
    public QueueFragmentResponse getQueueFragment( // Reverted return type
            @PathParam("profileId") Long profileId,
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {

        PlaybackController.PaginatedQueue paginatedQueue = playbackController.getQueuePage(page, limit, profileId);
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
                .render();

        return new QueueFragmentResponse(html, totalQueueSize); // Reverted to return DTO
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
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
            @jakarta.ws.rs.QueryParam("search") @jakarta.ws.rs.DefaultValue("") String search,
            @jakarta.ws.rs.QueryParam("sortBy") @jakarta.ws.rs.DefaultValue("title") String sortBy,
            @jakarta.ws.rs.QueryParam("sortDirection") @jakarta.ws.rs.DefaultValue("asc") String sortDirection) {
        
        SongService.PaginatedSongs result = playbackController.getSongs(page, limit, search, sortBy, sortDirection);
        List<Song> songs = result.songs();
        long totalSongs = result.totalCount();
        
        if (totalSongs == 0) {
            return "<tr><td colspan='5' class='has-text-centered'>No songs found.</td></tr>";
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
        for (Playlist p : playlists) {
            System.err.println("DEBUG: Playlist ID=" + p.id + ", Name=" + p.getName() + ", Global=" + p.getIsGlobal() + ", Profile=" + (p.getProfile() != null ? p.getProfile().id : null));
        }
        return playlists;
    }
}
