package API.Rest;

import Models.LiveChannel;
import Models.M3uPlaylist;
import Models.Profile;
import Models.DTOs.M3uImportRequest;
import Models.DTOs.M3uImportResponse;
import Services.M3uParserService;
import Services.M3uService;
import Services.StreamCheckerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.ExecutorService;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/video/m3u")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class M3uImportApi {

    @Inject
    M3uParserService m3uParserService;

    @Inject
    StreamCheckerService streamCheckerService;

    @Inject
    M3uService m3uService;

    @Inject
    ExecutorService executor;

    private final ObjectMapper mapper = new ObjectMapper();

    @POST
    @Path("/import")
    public Response importPlaylist(JsonNode body) {
        String playlistUrl = body.has("url") ? body.get("url").asText(null) : null;
        String rawText = body.has("rawText") ? body.get("rawText").asText(null) : null;
        String playlistName = body.has("name") ? body.get("name").asText(null) : null;
        Long profileId = body.has("profileId") ? body.get("profileId").asLong(1L) : 1L;
        String importType = body.has("type") ? body.get("type").asText("LIVE_TV") : "LIVE_TV";

        if ((playlistUrl == null || playlistUrl.isBlank()) && (rawText == null || rawText.isBlank())) {
            return badRequest("Either playlistUrl or rawText is required");
        }

        Profile profile = m3uService.findProfile(profileId);
        if (profile == null) {
            return badRequest("Profile not found: " + profileId);
        }

        // Fetch or use raw content
        String m3uContent;
        try {
            if (playlistUrl != null && !playlistUrl.isBlank()) {
                m3uContent = m3uParserService.fetchM3uContent(playlistUrl);
                if (playlistName == null || playlistName.isBlank()) {
                    playlistName = extractNameFromUrl(playlistUrl);
                }
            } else {
                m3uContent = rawText;
            }
        } catch (Exception e) {
            return badRequest("Failed to fetch M3U content: " + e.getMessage());
        }

        // Parse entries
        List<M3uParserService.M3uEntry> entries = m3uParserService.parse(m3uContent);

        M3uImportResponse response = m3uService.createPlaylistAndImportChannels(
                profileId, playlistUrl, playlistName, importType, entries);
        if (response == null) {
            return badRequest("Profile not found: " + profileId);
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(response));
        return Response.ok(root).build();
    }

    @GET
    @Path("/playlists")
    public Response listPlaylists(@QueryParam("profileId") Long profileId) {
        if (profileId == null) profileId = 1L;

        List<M3uPlaylist> playlists = M3uPlaylist.find("profile.id = ?1 order by createdAt desc", profileId).list();
        ArrayNode arr = mapper.createArrayNode();
        for (M3uPlaylist p : playlists) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", p.id);
            node.put("name", p.name);
            node.put("url", p.url);
            node.put("type", p.type);
            node.put("channelCount", p.channelCount);
            node.put("isActive", p.isActive);
            node.put("lastRefreshed", p.lastRefreshed != null ? p.lastRefreshed.toString() : null);
            node.put("createdAt", p.createdAt != null ? p.createdAt.toString() : null);
            arr.add(node);
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", arr);
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deletePlaylist(@PathParam("id") Long id) {
        if (!m3uService.deletePlaylist(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Playlist not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        return Response.ok(root).build();
    }

    @PUT
    @Path("/{id}")
    public Response updatePlaylist(@PathParam("id") Long id, JsonNode body) {
        String name = body.has("name") && !body.get("name").isNull() ? body.get("name").asText(null) : null;
        String url = body.has("url") && !body.get("url").isNull() ? body.get("url").asText(null) : null;
        String type = body.has("type") && !body.get("type").isNull() ? body.get("type").asText(null) : null;

        M3uPlaylist playlist = m3uService.updatePlaylist(id, name, url, type);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Playlist not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(toJson(playlist)));
        return Response.ok(root).build();
    }

    @GET
    @Path("/channels")
    public Response listChannels(
            @QueryParam("profileId") Long profileId,
            @QueryParam("playlistId") Long playlistId,
            @QueryParam("group") String group,
            @QueryParam("search") String search,
            @QueryParam("status") String status,
            @QueryParam("favorites") Boolean favorites,
            @QueryParam("page") Integer page,
            @QueryParam("limit") Integer limit) {
        if (profileId == null) profileId = 1L;
        if (page == null || page < 1) page = 1;
        if (limit == null || limit <= 0) limit = 100;

        var paramList = new java.util.ArrayList<Object>();
        paramList.add(profileId);
        int idx = 1;

        StringBuilder whereClause = new StringBuilder("profile.id = ?1");

        if (playlistId != null) {
            whereClause.append(" and playlist.id = ?").append(++idx);
            paramList.add(playlistId);
        }
        if (group != null && !group.isBlank()) {
            whereClause.append(" and groupTitle = ?").append(++idx);
            paramList.add(group);
        }
        if (search != null && !search.isBlank()) {
            String searchPattern = "%" + search.toLowerCase() + "%";
            whereClause.append(" and (lower(name) like ?").append(++idx)
                 .append(" or lower(tvgName) like ?").append(++idx).append(")");
            paramList.add(searchPattern);
            paramList.add(searchPattern);
        }
        if (status != null && !status.isBlank()) {
            if ("unchecked".equals(status)) {
                whereClause.append(" and streamStatus is null");
            } else if ("working".equals(status) || "dead".equals(status)) {
                whereClause.append(" and streamStatus = ?").append(++idx);
                paramList.add(status);
            }
        }
        if (Boolean.TRUE.equals(favorites)) {
            whereClause.append(" and isFavorite = true");
        }

        Object[] params = paramList.toArray();

        String countQuery = "SELECT COUNT(*) FROM LiveChannel WHERE " + whereClause.toString();
        long totalCount = LiveChannel.count(countQuery, params);

        String orderedQuery = whereClause.toString() + " order by channelNumber asc nulls last, name asc";
        List<LiveChannel> channels = LiveChannel.find(orderedQuery, params).page((int)(page - 1), (int)limit).list();

        ArrayNode arr = mapper.createArrayNode();
        for (LiveChannel ch : channels) {
            arr.add(toJson(ch));
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.put("totalCount", totalCount);
        root.put("hasMore", (long)page * limit < totalCount);
        root.put("nextPage", page + 1);
        root.set("data", arr);
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/channels/{id}")
    public Response deleteChannel(@PathParam("id") Long id) {
        if (!m3uService.deleteChannel(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Channel not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        return Response.ok(root).build();
    }

    @POST
    @Path("/channels/{id}/status")
    public Response updateChannelStatus(@PathParam("id") Long id, JsonNode body) {
        String status = body.has("status") ? body.get("status").asText(null) : null;

        M3uService.ChannelStatusUpdate result = m3uService.updateChannelStatus(id, status);
        if (result == M3uService.ChannelStatusUpdate.CHANNEL_NOT_FOUND) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Channel not found"))
                    .build();
        }
        if (result == M3uService.ChannelStatusUpdate.INVALID_STATUS) {
            return badRequest("Status must be 'working' or 'dead'");
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        return Response.ok(root).build();
    }

    @POST
    @Path("/channels/{id}/favorite")
    public Response toggleFavorite(@PathParam("id") Long id) {
        Boolean isFavorite = m3uService.toggleFavorite(id);
        if (isFavorite == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Channel not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.put("isFavorite", isFavorite);
        return Response.ok(root).build();
    }

    @POST
    @Path("/check-status")
    public Response checkAllChannelStatus() {
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }
            try {
                streamCheckerService.checkAllChannels();
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.put("message", "Stream check started in background");
        return Response.ok(root).build();
    }

    @GET
    @Path("/groups")
    public Response listGroups(@QueryParam("profileId") Long profileId) {
        if (profileId == null) profileId = 1L;

        List<String> groups = LiveChannel.find(
                "select distinct groupTitle from LiveChannel where profile.id = ?1 and groupTitle is not null order by groupTitle",
                profileId
        ).project(String.class).list();

        ArrayNode arr = mapper.createArrayNode();
        for (String group : groups) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", group);
            long count = LiveChannel.count("profile.id = ?1 and groupTitle = ?2", profileId, group);
            node.put("count", count);
            arr.add(node);
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", arr);
        return Response.ok(root).build();
    }

    @GET
    @Path("/stream/{id}")
    public Response streamChannel(@PathParam("id") Long id) {
        LiveChannel channel = LiveChannel.findById(id);
        if (channel == null || channel.streamUrl == null || channel.streamUrl.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Channel not found"))
                    .build();
        }
        return Response.temporaryRedirect(java.net.URI.create(channel.streamUrl)).build();
    }

    @POST
    @Path("/refresh/{id}")
    public Response refreshPlaylist(@PathParam("id") Long id) {
        M3uPlaylist playlist = M3uPlaylist.findById(id);
        if (playlist == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Playlist not found"))
                    .build();
        }

        if (playlist.url == null || playlist.url.isBlank()) {
            return badRequest("Playlist has no URL to refresh from");
        }

        // Fetch fresh content
        String m3uContent;
        try {
            m3uContent = m3uParserService.fetchM3uContent(playlist.url);
        } catch (Exception e) {
            return badRequest("Failed to fetch M3U content: " + e.getMessage());
        }

        // Delete existing channels and re-import
        List<M3uParserService.M3uEntry> entries = m3uParserService.parse(m3uContent);
        M3uImportResponse response = m3uService.refreshPlaylist(id, entries);
        if (response == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapper.createObjectNode().put("success", false).put("error", "Playlist not found"))
                    .build();
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.set("data", mapper.valueToTree(response));
        return Response.ok(root).build();
    }

    // --- Private helpers ---

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapper.createObjectNode().put("success", false).put("error", message))
                .build();
    }

    private String extractNameFromUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String segment = path.substring(path.lastIndexOf('/') + 1);
            if (!segment.isEmpty()) {
                int dot = segment.lastIndexOf('.');
                if (dot > 0) segment = segment.substring(0, dot);
                return segment.replace('-', ' ').replace('_', ' ');
            }
        } catch (Exception e) {
            // ignore
        }
        return "Imported Playlist";
    }

    private ObjectNode toJson(LiveChannel ch) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ch.id);
        node.put("name", ch.name != null ? ch.name : "");
        node.put("streamUrl", ch.streamUrl != null ? ch.streamUrl : "");
        node.put("logoUrl", ch.logoUrl != null ? ch.logoUrl : "");
        node.put("groupTitle", ch.groupTitle != null ? ch.groupTitle : "");
        node.put("tvgId", ch.tvgId != null ? ch.tvgId : "");
        node.put("tvgName", ch.tvgName != null ? ch.tvgName : "");
        node.put("country", ch.country != null ? ch.country : "");
        node.put("channelNumber", ch.channelNumber);
        node.put("isFavorite", ch.isFavorite);
        node.put("playlistId", ch.playlist != null ? ch.playlist.id : null);
        node.put("streamStatus", ch.streamStatus != null ? ch.streamStatus : "unchecked");
        node.put("lastChecked", ch.lastChecked != null ? ch.lastChecked.toString() : null);
        node.put("lastWatched", ch.lastWatched != null ? ch.lastWatched.toString() : null);
        node.put("createdAt", ch.createdAt != null ? ch.createdAt.toString() : null);
        return node;
    }

    private ObjectNode toJson(M3uPlaylist p) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", p.id);
        node.put("name", p.name != null ? p.name : "");
        node.put("url", p.url != null ? p.url : "");
        node.put("type", p.type != null ? p.type : "");
        node.put("channelCount", p.channelCount);
        node.put("isActive", p.isActive);
        node.put("lastRefreshed", p.lastRefreshed != null ? p.lastRefreshed.toString() : null);
        node.put("createdAt", p.createdAt != null ? p.createdAt.toString() : null);
        return node;
    }
}
