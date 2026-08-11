package API.Rest;

import Models.Video.LiveChannel;
import Models.Settings.User;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Optional;

@Path("/get.php")
public class PlaylistApi {

    @Inject
    AuthService authService;

    @Context
    UriInfo uriInfo;

    @GET
    public Response generatePlaylist(
            @QueryParam("username") String username,
            @QueryParam("password") String password,
            @QueryParam("type") String type,
            @QueryParam("output") String output) {

        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        String playlistType = (type != null && !type.isBlank()) ? type : "m3u";
        String outputFormat = (output != null && !output.isBlank()) ? output : "m3u8";

        List<LiveChannel> channels = LiveChannel.listAll();

        String playlist = buildPlaylist(channels, username, password, playlistType, outputFormat);

        String fileName = "m3u_plus".equals(playlistType) ? "playlist.m3u8" : "playlist.m3u";
        String contentType = "m3u_plus".equals(playlistType)
                ? "application/vnd.apple.mpegurl"
                : "audio/x-mpegurl";

        return Response.ok(playlist, contentType)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    private String buildPlaylist(List<LiveChannel> channels, String username, String password,
                                 String type, String outputFormat) {
        StringBuilder sb = new StringBuilder();
        String epgUrl = getExternalBaseUri() + "xmltv.php?username=" + urlEncode(username) + "&password=" + urlEncode(password);
        sb.append("#EXTM3U x-tvg-url=\"").append(epgUrl).append("\"\n");

        boolean isM3uPlus = "m3u_plus".equals(type);

        for (LiveChannel ch : channels) {
            if ("dead".equals(ch.streamStatus)) continue;
            String displayName = ch.name != null ? ch.name : "Unknown";
            String tvgId = ch.tvgId != null ? ch.tvgId : "";
            String tvgName = ch.tvgName != null ? ch.tvgName : displayName;
            String logoUrl = ch.logoUrl != null ? ch.logoUrl : "";
            String groupTitle = ch.groupTitle != null ? ch.groupTitle : "Uncategorized";

            sb.append("#EXTINF:-1");
            sb.append(" tvg-id=\"").append(escapeAttribute(tvgId)).append("\"");
            sb.append(" tvg-name=\"").append(escapeAttribute(tvgName)).append("\"");
            sb.append(" tvg-logo=\"").append(escapeAttribute(logoUrl)).append("\"");

            if (isM3uPlus && ch.channelNumber != null) {
                sb.append(" tvg-chno=\"").append(ch.channelNumber).append("\"");
            }

            sb.append(" group-title=\"").append(escapeAttribute(groupTitle)).append("\"");
            sb.append(",").append(escapeDisplayName(displayName));
            sb.append("\n");

            sb.append("/live/").append(username).append("/").append(password);
            sb.append("/").append(ch.id).append(".").append(outputFormat);
            sb.append("\n");
        }

        return sb.toString();
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    private String escapeDisplayName(String value) {
        return value.replace("&", "&amp;")
                    .replace(",", "&#44;")
                    .replace("\n", " ")
                    .replace("\r", "");
    }

    private String getExternalBaseUri() {
        if (uriInfo.getBaseUri().getHost().equals("localhost")
                || uriInfo.getBaseUri().getHost().equals("127.0.0.1")) {
            return "http://" + System.getenv().getOrDefault("EXTERNAL_HOST", "localhost")
                    + ":" + uriInfo.getBaseUri().getPort() + "/";
        }
        return uriInfo.getBaseUri().toString();
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
