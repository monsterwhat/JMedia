import Controllers.PlaybackController;
import Models.Playlist;
import Models.Song;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
 

@Path("/api/music/ui")
@Produces(MediaType.TEXT_HTML)
public class MusicUiApi {

    @Inject
    private PlaybackController playbackController;

    // -------------------------
    // Playlists fragment
    // -------------------------
    @GET
    @Path("/playlists-fragment")
    @Blocking
    public String playlistsFragment() {
        try {
            List<Playlist> playlists = playbackController.getPlaylists();
            if (playlists == null) {
                playlists = new ArrayList<>();
            }

            StringBuilder html = new StringBuilder("<ul>");

            // ---- Add "All Songs" button at the top ----
            html.append("<li><button hx-post='/api/music/ui/playlists/0/select' ")
                    .append("hx-target='#songTable tbody' hx-swap='innerHTML' ")
                    .append("hx-on::after-request='handlePlaylistSelection(0, \"All Songs\")'>")
                    .append("All Songs")
                    .append("</button></li>");

            // ---- Render existing playlists ----
            for (Playlist p : playlists) {
                if (p == null) {
                    continue;
                }
                String name = p.getName() != null ? p.getName() : "Unnamed Playlist";
                html.append("<li><button hx-post='/api/music/ui/playlists/")
                        .append(p.id)
                        .append("/select' ")
                        .append("hx-target='#songTable tbody' hx-swap='innerHTML' ")
                        .append("hx-on::after-request='handlePlaylistSelection(")
                        .append(p.id)
                        .append(", \"")
                        .append(name)
                        .append("\")'>")
                        .append(name)
                        .append("</button></li>");
            }

            html.append("</ul>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<ul></ul>"; // Return empty list on error
        }
    }

    @POST
    @Path("/playlists/{id}/select")
    @Consumes(MediaType.WILDCARD)
    @Blocking
    public String selectPlaylist(@PathParam("id") Long id) {
        if (id == null || id == 0) {
            // Special case: All Songs
            playbackController.selectPlaylistForBrowsing(null); // deselect any playlist
            return songsFragment(); // return all songs
        }

        Playlist playlist = playbackController.findPlaylist(id);
        if (playlist != null) {
            playbackController.selectPlaylistForBrowsing(playlist);
            return playlistSongsFragment(playlist);
        }

        return "<tbody></tbody>";
    }

    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public String playlistSongsFragment(Playlist playlist) {
        try {
            if (playlist == null || playlist.getSongs() == null) {
                return "<tbody></tbody>";
            }

            List<Song> songs = playlist.getSongs();
            Song currentSong = playbackController.getCurrentSong();
            boolean isPlaying = playbackController.getState().isPlaying();

            StringBuilder html = new StringBuilder("<tbody>");

            for (Song s : songs) {
                if (s == null) {
                    continue;
                }

                String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
                String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";
                int duration = s.getDurationSeconds() >= 0 ? s.getDurationSeconds() : 0;

                boolean isCurrent = currentSong != null && s.id.equals(currentSong.id);
                boolean showPause = isCurrent && isPlaying;

                String buttonClass = "button is-success is-rounded is-small" + (showPause ? " is-loading" : "");

                html.append("<tr class='").append(isCurrent ? "has-background-grey" : "").append("'>")
                        .append("<td>").append(title).append("</td>")
                        .append("<td>").append(artist).append("</td>")
                        .append("<td>").append(formatTime(duration)).append("</td>")
                        .append("<td>")
                        .append("<button class='button ").append(buttonClass).append("' ")
                        .append("hx-post='/api/music/playback/select/").append(s.id).append("' ")
                        .append("hx-trigger='click' ")
                        .append("hx-swap='outerHTML' ")
                        .append("hx-on:afterRequest=\"htmx.ajax('GET','/api/music/ui/songs-fragment',{target:'#songTable tbody'})\">")
                        .append(showPause ? "Pause" : "Play")
                        .append("</button>")
                        .append("</td>")
                        .append("</tr>");
            }

            html.append("</tbody>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<tbody></tbody>"; // Return empty table body on error
        }
    }

    // -------------------------
    // Songs fragment
    // -------------------------
    @GET
    @Path("/songs-fragment")
    public String songsFragment() {
        try {
            List<Song> songs = playbackController.getSongs();
            if (songs == null) {
                songs = new ArrayList<>();
            }

            Song currentSong = playbackController.getCurrentSong();
            boolean isPlaying = playbackController.getState().isPlaying(); // get actual playback state

            StringBuilder html = new StringBuilder("<tbody>");

            for (Song s : songs) {
                if (s == null) {
                    continue;
                }

                String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
                String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";
                int duration = s.getDurationSeconds() >= 0 ? s.getDurationSeconds() : 0;

                boolean isCurrent = currentSong != null && s.id.equals(currentSong.id);
                boolean showPause = isCurrent && isPlaying;

                String buttonClass = "button is-success is-rounded is-small" + (showPause ? " is-loading" : "");

                html.append("<tr class='").append(isCurrent ? "has-background-grey" : "").append("'>");
                String imageUrl = s.getArtworkBase64() != null && !s.getArtworkBase64().isEmpty() ? "data:image/jpeg;base64," + s.getArtworkBase64() : "/logo.png";
                html.append("<td style='vertical-align: middle; text-align: center;'><figure class='image is-48x48'><img src='").append(imageUrl).append("'></figure></td>")
                        .append("<td>")
                        .append("<div>").append(title).append("</div>")
                        .append("<div class='has-text-success is-size-7'>").append(artist).append("</div>")
                        .append("</td>")
                        .append("<td>").append(formatTime(duration)).append("</td>")
                        .append("<td>")
                        .append("<button class='button ").append(buttonClass).append("' ")
                        .append("hx-post='/api/music/playback/select/").append(s.id).append("' ")
                        .append("hx-trigger='click' ")
                        .append("hx-swap='outerHTML' ")
                        .append("hx-on:afterRequest=\"htmx.ajax('GET','/api/music/ui/songs-fragment',{target:'#songTable tbody'})\">")
                        .append(showPause ? "Pause" : "Play")
                        .append("</button>")
                        .append("</td>")
                        .append("</tr>");

            }

            html.append("</tbody>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<tbody></tbody>"; // Return empty table body on error
        }
    }

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
                    // Add current song first
                    orderedQueue.add(queue.get(currentSongIndexInQueue));
                    // Add songs after current
                    for (int i = currentSongIndexInQueue + 1; i < queue.size(); i++) {
                        orderedQueue.add(queue.get(i));
                    }
                    // Add songs before current
                    for (int i = 0; i < currentSongIndexInQueue; i++) {
                        orderedQueue.add(queue.get(i));
                    }
                } else {
                    // Current song not found in queue, just use original queue
                    orderedQueue.addAll(queue);
                }
            } else {
                // No current song or empty queue, just use original queue
                orderedQueue.addAll(queue);
            }

            StringBuilder html = new StringBuilder("<tbody>");
            int index = 0;
            for (Song s : orderedQueue) { // Iterate over orderedQueue
                if (s == null) {
                    continue;
                }

                String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
                String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";

                boolean isCurrent = currentSong != null && s.id.equals(currentSong.id);

                // The originalIndex calculation seems to be for reverse order, which is not needed now.
                // The 'index' variable in the loop is the correct one for skip-to/remove actions.

                String imageUrl = s.getArtworkBase64() != null && !s.getArtworkBase64().isEmpty() ? "data:image/jpeg;base64," + s.getArtworkBase64() : "/logo.png";
                html.append("<tr class='").append(isCurrent ? "has-background-grey" : "").append("'>")
                        .append("<td style='vertical-align: middle; text-align: center;'><figure class='image is-24x24'><img src='").append(imageUrl).append("'/></figure></td>")
                        .append("<td class='is-size-7' style='width: 75%;'><span>").append(title).append("</span><br><span class='has-text-success is-size-7'>").append(artist).append("</span></td>")
                        .append("<td class='has-text-right' style='width: 25%;'>")
                        .append("<i class='pi pi-step-forward icon has-text-primary is-clickable' hx-post='/api/music/queue/skip-to/").append(index).append("' hx-trigger='click' hx-swap='none' hx-on::after-request='htmx.trigger(\"body\", \"queueChanged\")'></i>")
                        .append("<i class='pi pi-times icon has-text-danger is-clickable' hx-post='/api/music/queue/remove/").append(index).append("' hx-trigger='click' hx-swap='none' hx-on::after-request='htmx.ajax(\"GET\",\"/api/music/ui/queue-fragment\", {target: \"#songQueueTable tbody\", swap: \"innerHTML\"})' hx-headers='{\"Content-Type\": \"application/json\"}'></i>")
                        .append("</td>")
                        .append("</tr>");
                index++;
            }

            html.append("</tbody>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "<tbody></tbody>"; // Return empty table body on error
        }
    }

    @GET
    @Path("/all-songs-for-playlist-fragment/{playlistId}")
    @Blocking
    public String allSongsForPlaylistFragment(@PathParam("playlistId") Long playlistId) {
        try {
            List<Song> allSongs = playbackController.getSongs();
            if (allSongs == null) {
                allSongs = new ArrayList<>();
            }

            Playlist currentPlaylist = null;
            if (playlistId != null && playlistId != 0) {
                currentPlaylist = playbackController.findPlaylist(playlistId);
            }

            if (currentPlaylist == null) {
                return ""; // Don't show this fragment if no playlist is selected
            }

            StringBuilder html = new StringBuilder("<table class='table is-fullwidth is-hoverable is-striped'>");
            html.append("<thead><tr><th>Title</th><th>Artist</th><th>Duration</th><th>Action</th></tr></thead><tbody>");

            for (Song s : allSongs) {
                if (s == null) {
                    continue;
                }

                String title = s.getTitle() != null ? s.getTitle() : "Unknown Title";
                String artist = s.getArtist() != null ? s.getArtist() : "Unknown Artist";
                int duration = s.getDurationSeconds() >= 0 ? s.getDurationSeconds() : 0;

                html.append("<tr>")
                        .append("<td>").append(title).append("</td>")
                        .append("<td>").append(artist).append("</td>")
                        .append("<td>").append(formatTime(duration)).append("</td>")
                        .append("<td>")
                        .append("<button class='button is-small is-primary' hx-post='/api/music/playback/select/").append(s.id).append("' hx-trigger='click' hx-swap='none'>")
                        .append("<i class='pi pi-play'></i>")
                        .append("</button>");

                if (!currentPlaylist.getSongs().contains(s)) {
                    html.append("<button class='button is-small is-success' hx-post='/api/music/playlists/").append(playlistId).append("/songs/").append(s.id).append("' hx-trigger='click' hx-swap='outerHTML' hx-target='this'>")
                            .append("<i class='pi pi-plus'></i>")
                            .append("</button>");
                }

                html.append("</td>")
                        .append("</tr>");
            }

            html.append("</tbody></table>");
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return ""; // Return empty on error
        }
    }
}
