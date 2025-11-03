
import Controllers.PlaybackController;
import Models.Playlist;
import Models.Song;
import Services.PlaylistService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Path("/api/music/ui")
@Produces(MediaType.TEXT_HTML)
public class MusicUiApi {

    @Inject
    private PlaybackController playbackController;

    @Inject
    private PlaylistService playlistService;

    // Returns the list of playlists for the sidebar
    @GET
    @Path("/playlists-fragment")
    @Blocking
    public String playlistsFragment() {
        try {
            List<Playlist> playlists = playbackController.getPlaylists();
            if (playlists == null) {
                playlists = new ArrayList<>();
            }

            StringBuilder html = new StringBuilder("<div id=\"sidebarPlaylistListContainer\">");
            html.append("<table class=\"table is-fullwidth is-hoverable is-striped\">");
            html.append("<tbody>");

            // "All Songs" entry
            html.append("<tr><td style=\"vertical-align: middle;\"><button class=\"button is-ghost is-fullwidth has-text-left\" hx-get='/api/music/ui/playlist-view/0' hx-target='#playlistView' hx-swap='outerHTML'>\nAll Songs</button></td><td style=\"vertical-align: middle;\"></td></tr>"); // Empty action column for "All Songs"

            for (Playlist p : playlists) {
                if (p == null) {
                    continue;
                }
                String name = p.getName() != null ? p.getName() : "Unnamed Playlist";
                html.append("<tr><td style=\"vertical-align: middle;\"><button class=\"button is-ghost is-fullwidth has-text-left\" hx-get='/api/music/ui/playlist-view/").append(p.id).append("' hx-target='#playlistView' hx-swap='outerHTML'>\n").append(name).append("</button></td>");
                html.append("<td style=\"vertical-align: middle;\"><div class=\"has-text-right\"><i class=\"pi pi-trash has-text-danger is-clickable\" hx-delete=\"/api/music/playlists/").append(p.id).append("\" hx-confirm=\"Are you sure you want to delete playlist '").append(name).append("'?\" hx-target=\"closest tr\" hx-swap=\"outerHTML\" hx-on::after-request=\"htmx.ajax('GET','/api/music/ui/playlists-fragment',{target:'#sidebarPlaylistList', swap:'innerHTML'});\"></i></div></td></tr>");
            }

            html.append("</tbody>");
            html.append("</table>");
            html.append("</div>"); // Close sidebarPlaylistListContainer
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<div></div>"; // Return an empty div on error
        }
    }    // Returns the main view component for a selected playlist

    @GET
    @Path("/playlist-view/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public String getPlaylistView(@PathParam("id") Long id) {
        String playlistName;
        long playlistId = (id == null) ? 0L : id;

        if (playlistId == 0) {
            playlistName = "All Songs";
        } else {
            Playlist playlist = playbackController.findPlaylist(playlistId);
            playlistName = (playlist != null) ? playlist.getName() : "Playlist not found";
        }

        String html = "<div class='column is-9 is-full-mobile' id='playlistView' data-playlist-id='" + playlistId + "'>"
                + "<div class='card has-background-grey-darker has-text-white'>"
                + "<header class='card-header'>"
                + "<p id='playlistTitle' class='card-header-title has-text-white is-4'>" + playlistName + "</p>"
                + "<button class='is-small is-rounded has-text-success ml-2' hx-post='/api/music/playback/queue-all/" + playlistId + "' hx-trigger='click' hx-swap='none' hx-on::after-request=\"htmx.ajax('GET','/api/music/ui/queue-fragment',{target:'#songQueueTable tbody', swap:'innerHTML'}); htmx.ajax('GET','/api/music/ui/tbody/" + playlistId + "',{target:'#songTable tbody', swap:'innerHTML'});\">"
                + "<i class='pi pi-play'></i> Play All"
                + "</button>"
                + "<button class='card-header-icon' aria-label='more options' id='toggleAllSongsBtn'>"
                + "<span class='icon'><i class='pi pi-angle-down' aria-hidden='true'></i></span>"
                + "</button>"
                + "</header>"
                + "<div class='card-content m-0 p-0' id='allSongsContent'>"
                + "<div id='songListContainer' style='max-height: calc(100vh - 320px); overflow-y: auto;'>"
                + "<table id='songTable' class='table is-fullwidth is-hoverable is-striped'>"
                + "<thead><tr><th></th><th>Title / Artist</th><th>Date Added</th><th>Duration</th><th>Action</th></tr></thead>"
                + "<tbody hx-get='/api/music/ui/tbody/" + playlistId + "' hx-trigger='load' hx-swap='innerHTML'>"
                + "</tbody>"
                + "</table>"
                + "</div>"
                + "</div>"
                + "</div>"
                + "</div>";

        return html;
    }

    // Returns the TBODY content for a given playlist
    @GET
    @Path("/tbody/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public String getPlaylistTbody(@PathParam("id") Long id) {
        List<Song> songs;
        long playlistId = (id == null) ? 0L : id;

        if (playlistId == 0) {
            songs = playbackController.getSongs();
        } else {
            Playlist playlist = playbackController.findPlaylist(playlistId);
            songs = (playlist != null) ? playlist.getSongs() : new ArrayList<>();
        }
        
        if (songs == null) {
            songs = new ArrayList<>();
        }

        Song currentSong = playbackController.getCurrentSong();
        boolean isPlaying = playbackController.getState().isPlaying();
        StringBuilder songRows = new StringBuilder();
        for (Song s : songs) {
            if (s == null) continue;

            boolean isCurrent = currentSong != null && s.id.equals(currentSong.id);
            boolean showPause = isCurrent && isPlaying;
            String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
            String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";
            String dateAdded = s.getDateAdded() != null ? s.getDateAdded().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "Unknown";
            int duration = s.getDurationSeconds();
            String imageUrl = s.getArtworkBase64() != null && !s.getArtworkBase64().isEmpty() ? "data:image/jpeg;base64," + s.getArtworkBase64() : "/logo.png";
            
            songRows.append("<tr class='").append(isCurrent ? "has-background-grey" : "").append("'>")
                .append("<td style='vertical-align: middle; text-align: center; width: 5%;'><figure class='image is-48x48'><img src='").append(imageUrl).append("'/></figure></td>")
                .append("<td style='vertical-align: middle;'><div>").append(title).append("</div><div class='has-text-success is-size-7'>").append(artist).append("</div></td>")
                .append("<td style='vertical-align: middle;'>").append(dateAdded).append("</td>")
                .append("<td style='vertical-align: middle;'>").append(formatTime(duration)).append("</td>")
                .append("<td style='vertical-align: middle;'><div class=\"is-flex is-align-items-center is-justify-content-center\">"); // Added div with flex classes

            String refreshScript = "htmx.ajax('GET','/api/music/ui/tbody/" + playlistId + "',{target:'#songTable tbody', swap:'innerHTML'})";

            if (playlistId == 0) { // All Songs view
                songRows.append("<button class='button is-success is-rounded is-small' hx-post='/api/music/playback/select/").append(s.id).append("' hx-trigger='click' hx-swap='none' hx-on:after-request=\"").append(refreshScript).append("\">").append(showPause ? "Pause" : "Play").append("</button>")
                    .append("<button class='has-text-info is-small ml-1' hx-get='/api/music/ui/add-to-playlist-dialog/").append(s.id).append("' hx-target='#addToPlaylistModalContent' hx-trigger='click' hx-on::after-request=\"document.getElementById('addToPlaylistModal').classList.add('is-active')\">")
                    .append("<i class='pi pi-plus-circle'></i></button>");
            } else { // Playlist view
                songRows.append("<button class='button is-success is-rounded is-small' hx-post='/api/music/playback/select/").append(s.id).append("' hx-trigger='click' hx-swap='none' hx-on:after-request=\"").append(refreshScript).append("\">").append(showPause ? "Pause" : "Play").append("</button>")
                    .append("<button class='has-text-danger is-small ml-1' hx-delete='/api/music/playlists/").append(playlistId).append("/songs/").append(s.id).append("' hx-trigger='click' hx-swap='outerHTML' hx-target='closest tr'>")
                    .append("<i class='pi pi-minus-circle'></i></button>");
            }
            songRows.append("</div></td></tr>");
        }
        return songRows.toString();
    }

    // -------------------------
    // Other fragments (Queue, etc.)
    // -------------------------
    private String formatTime(double seconds) {
        int m = (int) (seconds / 60);
        int s = (int) (seconds % 60);
        return String.format("%d:%02d", m, s);
    }

    @GET
    @Path("/queue-fragment")
    @Blocking
    public String queueFragment() {
        try {
            List<Song> queue = playbackController.getQueue();
            if (queue == null) {
                queue = new ArrayList<>();
            }

            Song currentSong = playbackController.getCurrentSong();
            List<Song> orderedQueue = new ArrayList<>();

            if (currentSong != null && !queue.isEmpty()) {
                int currentSongIndexInQueue = -1;
                for (int i = 0; i < queue.size(); i++) {
                    if (queue.get(i).id.equals(currentSong.id)) {
                        currentSongIndexInQueue = i;
                        break;
                    }
                }

                if (currentSongIndexInQueue != -1) {
                    orderedQueue.add(queue.get(currentSongIndexInQueue));
                    for (int i = currentSongIndexInQueue + 1; i < queue.size(); i++) {
                        orderedQueue.add(queue.get(i));
                    }
                    for (int i = 0; i < currentSongIndexInQueue; i++) {
                        orderedQueue.add(queue.get(i));
                    }
                } else {
                    orderedQueue.addAll(queue);
                }
            } else {
                orderedQueue.addAll(queue);
            }

            StringBuilder html = new StringBuilder("<tbody>");
            int index = 0;
            for (Song s : orderedQueue) {
                if (s == null) {
                    continue;
                }

                String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
                String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";
                boolean isCurrent = currentSong != null && s.id.equals(currentSong.id);
                int originalIndex = queue.indexOf(s);

                String imageUrl = s.getArtworkBase64() != null && !s.getArtworkBase64().isEmpty() ? "data:image/jpeg;base64," + s.getArtworkBase64() : "/logo.png";
                html.append("<tr class='").append(isCurrent ? "has-background-grey" : "").append("' >")
                        .append("<td style='vertical-align: middle; text-align: center;'><figure class='image is-24x24'><img src='").append(imageUrl).append("'/></figure></td>")
                        .append("<td class='is-size-7' style='width: 75%;'><span>").append(title).append("</span><br><span class='has-text-success is-size-7'>").append(artist).append("</span></td>")
                        .append("<td class='has-text-right' style='width: 25%;'>")
                        .append("<i class='pi pi-step-forward icon has-text-primary is-clickable' hx-post='/api/music/queue/skip-to/").append(originalIndex).append("' hx-trigger='click' hx-swap='none' hx-on::after-request='htmx.trigger(\"body\", \"queueChanged\")'></i>")
                        .append("<i class='pi pi-times icon has-text-danger is-clickable' hx-post='/api/music/queue/remove/").append(originalIndex).append("' hx-trigger='click' hx-swap='none' hx-on::after-request='htmx.ajax(\"GET\",\"/api/music/ui/queue-fragment\", {target: \"#songQueueTable tbody\", swap: \"innerHTML\"})' hx-headers='{\"Content-Type\": \"application/json\"}'></i>")
                        .append("</td>")
                        .append("</tr>");
                index++;
            }

            html.append("</tbody>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<tbody></tbody>";
        }
    }

    @GET
    @Path("/add-to-playlist-dialog/{songId}")
    @Blocking
    public String addToPlaylistDialog(@PathParam("songId") Long songId) {
        try {
            List<Playlist> allPlaylists = playbackController.getPlaylists();
            if (allPlaylists == null) {
                allPlaylists = new ArrayList<>();
            }

            StringBuilder html = new StringBuilder("<div class='modal-card' style='width: 480px;'>");
            html.append("<header class='modal-card-head'>");
            html.append("<p class='modal-card-title'>Add to Playlist</p>");
            html.append("<button class='delete' aria-label='close' onclick='document.getElementById(\"addToPlaylistModal\").classList.remove(\"is-active\")'></button>");
            html.append("</header>");
            html.append("<section class='modal-card-body'>");
            html.append("<div class='field'>");

            for (Playlist p : allPlaylists) {
                if (p == null) {
                    continue;
                }
                String name = p.getName() != null ? p.getName() : "Unnamed Playlist";
                boolean isSongInPlaylist = p.getSongs() != null && p.getSongs().stream().anyMatch(s -> s.id.equals(songId));
                String checked = isSongInPlaylist ? "checked" : "";

                html.append("<div class='control'>");
                html.append("<label class='checkbox'>");
                html.append("<input type='checkbox' ").append(checked).append(" ");
                html.append("hx-post='/api/music/ui/playlists/").append(p.id).append("/songs/").append(songId).append("/toggle' ");
                html.append("hx-trigger='change' hx-swap='none'>");
                html.append(" ").append(name);
                html.append("</label>");
                html.append("</div>");
            }

            html.append("</div>");
            html.append("</section>");
            html.append("<footer class='modal-card-foot'>");
            html.append("<button class='button' onclick='document.getElementById(\"addToPlaylistModal\").classList.remove(\"is-active\")'>Close</button>");
            html.append("</footer>");
            html.append("</div>");

            return html.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "<div class='modal-card'><header class='modal-card-head'><p class='modal-card-title'>Error</p></header><section class='modal-card-body'><p>Could not load playlists.</p></section><footer class='modal-card-foot'><button class='button' onclick='document.getElementById(\"addToPlaylistModal\").classList.remove(\"is-active\")'>Close</button></footer></div>";
        }
    }

    @POST
    @Path("/playlists/{playlistId}/songs/{songId}/toggle")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public void toggleSongInPlaylist(@PathParam("playlistId") Long playlistId, @PathParam("songId") Long songId) {
        playlistService.toggleSongInPlaylist(playlistId, songId);
    }
}
