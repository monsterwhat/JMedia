package API.Rest;

import Models.Settings.User;
import Models.Video.Video;
import Models.Video.LiveChannel;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@Path("/get.php")
@Produces("audio/x-mpegurl")
public class GetPhpApi {

    private static final Logger log = Logger.getLogger(GetPhpApi.class);

    @Inject
    AuthService authService;

    @Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    @GET
    public Response generatePlaylist(
            @QueryParam("username") String username,
            @QueryParam("password") String password,
            @QueryParam("type") String type,
            @QueryParam("output") String output) {

        if (username == null || password == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        boolean isM3uPlus = "m3u_plus".equalsIgnoreCase(type);
        String ext = "m3u8".equalsIgnoreCase(output) ? "m3u8" : "ts";
        String serverUrl = getExternalBaseUri();

        StringBuilder m3u = new StringBuilder();
        m3u.append("#EXTM3U\n");

        // Add Live Channels
        List<LiveChannel> channels = LiveChannel.<LiveChannel>listAll().stream()
                .filter(ch -> !"dead".equals(ch.streamStatus))
                .toList();
        for (LiveChannel ch : channels) {
            if (ch.streamUrl == null || ch.streamUrl.isBlank()) continue;

            if (isM3uPlus) {
                m3u.append(String.format("#EXTINF:-1 tvg-id=\"%s\" tvg-name=\"%s\" tvg-logo=\"%s\" group-title=\"%s\",%s\n",
                        ch.tvgId != null ? ch.tvgId : "",
                        ch.tvgName != null ? ch.tvgName : (ch.name != null ? ch.name : ""),
                        getLiveChannelLogo(ch),
                        ch.groupTitle != null && !ch.groupTitle.isBlank() ? ch.groupTitle : (ch.playlist != null ? ch.playlist.name : "Live"),
                        ch.name != null ? ch.name : "Unknown"));
            } else {
                m3u.append(String.format("#EXTINF:-1,%s\n", ch.name != null ? ch.name : "Unknown"));
            }
            m3u.append(String.format("%splayer_api.php/live/%s/%s/%d.%s\n",
                    serverUrl,
                    username,
                    password,
                    ch.id,
                    ext));
        }

        // Add VOD Movies
        List<Video> movies = Video.find("type = 'movie'").list();
        for (Video v : movies) {
            String genre = (v.genres != null && !v.genres.isEmpty()) ? v.genres.get(0) : "";
            String containerExt = v.container != null ? v.container : "mp4";

            if (isM3uPlus) {
                m3u.append(String.format("#EXTINF:-1 tvg-id=\"%s\" tvg-name=\"%s\" tvg-logo=\"%s\" group-title=\"%s\",%s\n",
                        v.tmdbId != null ? v.tmdbId : "",
                        v.title != null ? v.title : "",
                        getImageUrl(v),
                        genre,
                        v.title != null ? v.title : "Unknown"));
            } else {
                m3u.append(String.format("#EXTINF:-1,%s\n", v.title != null ? v.title : "Unknown"));
            }
            m3u.append(String.format("%splayer_api.php/movie/%s/%s/%d.%s\n",
                    serverUrl,
                    username,
                    password,
                    v.id,
                    containerExt));
        }

        // Add Series Episodes
        List<Video> episodes = Video.find("type = 'episode'").list();
        for (Video ep : episodes) {
            String containerExt = ep.container != null ? ep.container : "mp4";
            String groupTitle = ep.seriesTitle != null ? ep.seriesTitle : "Series";

            if (isM3uPlus) {
                m3u.append(String.format("#EXTINF:-1 tvg-id=\"%s\" tvg-name=\"%s\" tvg-logo=\"%s\" group-title=\"%s\",%s\n",
                        ep.tmdbId != null ? ep.tmdbId : "",
                        ep.title != null ? ep.title : "",
                        getImageUrl(ep),
                        groupTitle,
                        ep.title != null ? ep.title : "Unknown"));
            } else {
                m3u.append(String.format("#EXTINF:-1,%s\n", ep.title != null ? ep.title : "Unknown"));
            }
            m3u.append(String.format("%splayer_api.php/series/%s/%s/%d.%s\n",
                    serverUrl,
                    username,
                    password,
                    ep.id,
                    containerExt));
        }

        log.infof("Generated M3U playlist for user=%s, type=%s, output=%s", username, type, output);
        return Response.ok(m3u.toString(), "audio/x-mpegurl")
                .header("Content-Disposition", "attachment; filename=\"playlist.m3u\"")
                .build();
    }

    private String getExternalBaseUri() {
        if (uriInfo.getBaseUri().getHost().equals("localhost") || uriInfo.getBaseUri().getHost().equals("127.0.0.1")) {
            return "http://" + System.getenv().getOrDefault("EXTERNAL_HOST", "localhost") + ":" + uriInfo.getBaseUri().getPort() + "/";
        }
        return uriInfo.getBaseUri().toString();
    }

    private String getImageUrl(Video v) {
        if (v.tmdbId != null && !v.tmdbId.isEmpty() && v.posterPath != null && !v.posterPath.isEmpty()) {
            return "https://image.tmdb.org/t/p/w500" + v.posterPath;
        }
        if (v.posterPath != null && !v.posterPath.isBlank()) {
            if (v.posterPath.startsWith("http")) {
                return v.posterPath;
            }
            return getExternalBaseUri() + "api/video/thumbnail/" + v.id;
        }
        return "";
    }

    private String getLiveChannelLogo(LiveChannel ch) {
        if (ch.logoUrl != null && !ch.logoUrl.isBlank()) {
            if (ch.logoUrl.startsWith("http")) {
                return ch.logoUrl;
            }
            return getExternalBaseUri() + "api/video/thumbnail/live/" + ch.id;
        }
        return "";
    }
}
