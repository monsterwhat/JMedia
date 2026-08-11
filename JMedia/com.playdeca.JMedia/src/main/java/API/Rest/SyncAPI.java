package API.Rest;

import API.ApiResponse;
import Models.Settings.SyncLog;
import Models.Settings.SyncServer;
import Services.RemoteJMediaClient;
import Services.SettingsService;
import Services.SyncServerService;
import Services.SyncService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import Services.LoggingService;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyncAPI {

    @Inject
    LoggingService log;

    @Inject
    SyncService syncService;

    @Inject
    SyncServerService syncServerService;

    @Inject
    SettingsService settingsService;

    @Inject
    RemoteJMediaClient remoteClient;

    @POST
    @Path("/trigger")
    public Response triggerSync(Map<String, Object> data) {
        if (syncService.isSyncInProgress()) {
            return Response.ok(ApiResponse.success(Map.of(
                    "message", "Sync already in progress"
            ))).build();
        }

        String syncType = "ALL";
        int limit = 0;

        if (data != null) {
            if (data.containsKey("type")) {
                syncType = ((String) data.get("type")).toUpperCase();
            }
            if (data.containsKey("limit")) {
                limit = ((Number) data.get("limit")).intValue();
            }
        }

        final String finalSyncType = syncType;
        final int finalLimit = limit;

        new Thread(() ->
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(
                () -> syncService.syncAllServers(finalSyncType, finalLimit)
            ),
            "SyncTriggerThread"
        ).start();

        return Response.ok(ApiResponse.success(Map.of(
                "message", "Sync started",
                "type", syncType,
                "limit", limit
        ))).build();
    }

    @POST
    @Path("/trigger/{type}")
    public Response triggerSyncByType(@PathParam("type") String type) {
        return triggerSync(Map.of("type", type));
    }

    @GET
    @Path("/status")
    public Response getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("inProgress", syncService.isSyncInProgress());

        if (syncService.isSyncInProgress()) {
            status.put("currentSync", Map.of(
                    "type", syncService.getCurrentSyncType(),
                    "serverName", syncService.getCurrentServerName(),
                    "totalItems", syncService.getCurrentTotalItems(),
                    "itemsProcessed", syncService.getCurrentItemsProcessed()
            ));
        }

        SyncLog lastLog = syncService.getLastSyncLog();
        if (lastLog != null) {
            Map<String, Object> lastSync = new HashMap<>();
            lastSync.put("startedAt", lastLog.startedAt);
            lastSync.put("completedAt", lastLog.completedAt);
            lastSync.put("status", lastLog.status);
            lastSync.put("syncType", lastLog.syncType);
            lastSync.put("songsSent", lastLog.songsSent);
            lastSync.put("songsReceived", lastLog.songsReceived);
            lastSync.put("songsUpdated", lastLog.songsUpdated);
            lastSync.put("songsCreated", lastLog.songsCreated);
            lastSync.put("videosSent", lastLog.videosSent);
            lastSync.put("videosReceived", lastLog.videosReceived);
            lastSync.put("videosUpdated", lastLog.videosUpdated);
            lastSync.put("collectionsSent", lastLog.collectionsSent);
            lastSync.put("collectionsReceived", lastLog.collectionsReceived);
            lastSync.put("playlistsSent", lastLog.playlistsSent);
            lastSync.put("playlistsReceived", lastLog.playlistsReceived);
            lastSync.put("subtitlesSent", lastLog.subtitlesSent);
            lastSync.put("subtitlesReceived", lastLog.subtitlesReceived);
            lastSync.put("totalItems", lastLog.totalItems);
            lastSync.put("itemsProcessed", lastLog.itemsProcessed);
            lastSync.put("error", lastLog.errorMessage);
            status.put("lastSync", lastSync);
        }

        // Item counts
        status.put("counts", Map.of(
                "music", syncService.getMusicCount(),
                "videos", syncService.getVideoCount(),
                "collections", syncService.getCollectionCount(),
                "subtitles", syncService.getSubtitleCount()
        ));

        return Response.ok(ApiResponse.success(status)).build();
    }

    @GET
    @Path("/settings")
    public Response getSyncSettings() {
        Models.Settings.Settings settings = settingsService.getOrCreateSettings();
        Map<String, Object> syncSettings = new HashMap<>();
        syncSettings.put("syncEnabled", settings.getSyncEnabled());
        syncSettings.put("syncSchedule", settings.getSyncSchedule());
        syncSettings.put("syncMusicEnabled", settings.getSyncMusicEnabled());
        syncSettings.put("syncVideoEnabled", settings.getSyncVideoEnabled());
        syncSettings.put("syncTimelinesEnabled", settings.getSyncTimelinesEnabled());
        syncSettings.put("syncPlaylistsEnabled", settings.getSyncPlaylistsEnabled());
        syncSettings.put("syncSubtitlesEnabled", settings.getSyncSubtitlesEnabled());
        syncSettings.put("syncApiKey", settings.getSyncApiKey());
        syncSettings.put("syncItemLimit", settings.getSyncItemLimit());
        return Response.ok(ApiResponse.success(syncSettings)).build();
    }

    @PUT
    @Path("/settings")
    public Response updateSyncSettings(Map<String, Object> data) {
        Models.Settings.Settings settings = settingsService.getOrCreateSettings();

        if (data.containsKey("syncEnabled")) {
            settings.setSyncEnabled(((Boolean) data.get("syncEnabled")));
        }
        if (data.containsKey("syncSchedule")) {
            settings.setSyncSchedule((String) data.get("syncSchedule"));
        }
        if (data.containsKey("syncMusicEnabled")) {
            settings.setSyncMusicEnabled(((Boolean) data.get("syncMusicEnabled")));
        }
        if (data.containsKey("syncVideoEnabled")) {
            settings.setSyncVideoEnabled(((Boolean) data.get("syncVideoEnabled")));
        }
        if (data.containsKey("syncTimelinesEnabled")) {
            settings.setSyncTimelinesEnabled(((Boolean) data.get("syncTimelinesEnabled")));
        }
        if (data.containsKey("syncPlaylistsEnabled")) {
            settings.setSyncPlaylistsEnabled(((Boolean) data.get("syncPlaylistsEnabled")));
        }
        if (data.containsKey("syncSubtitlesEnabled")) {
            settings.setSyncSubtitlesEnabled(((Boolean) data.get("syncSubtitlesEnabled")));
        }
        if (data.containsKey("syncApiKey")) {
            settings.setSyncApiKey((String) data.get("syncApiKey"));
        }
        if (data.containsKey("syncItemLimit")) {
            settings.setSyncItemLimit(((Number) data.get("syncItemLimit")).intValue());
        }

        settingsService.save(settings);

        Map<String, Object> syncSettings = new HashMap<>();
        syncSettings.put("syncEnabled", settings.getSyncEnabled());
        syncSettings.put("syncSchedule", settings.getSyncSchedule());
        syncSettings.put("syncMusicEnabled", settings.getSyncMusicEnabled());
        syncSettings.put("syncVideoEnabled", settings.getSyncVideoEnabled());
        syncSettings.put("syncTimelinesEnabled", settings.getSyncTimelinesEnabled());
        syncSettings.put("syncPlaylistsEnabled", settings.getSyncPlaylistsEnabled());
        syncSettings.put("syncSubtitlesEnabled", settings.getSyncSubtitlesEnabled());
        syncSettings.put("syncApiKey", settings.getSyncApiKey());
        syncSettings.put("syncItemLimit", settings.getSyncItemLimit());
        return Response.ok(ApiResponse.success(syncSettings)).build();
    }

    @POST
    @Path("/settings/generate-api-key")
    public Response generateApiKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String newKey = "jm_" + sb;

        Models.Settings.Settings settings = settingsService.getOrCreateSettings();
        settings.setSyncApiKey(newKey);
        settingsService.save(settings);

        return Response.ok(ApiResponse.success(Map.of(
                "apiKey", newKey
        ))).build();
    }

    @GET
    @Path("/logs")
    public Response getSyncLogs(@QueryParam("limit") @DefaultValue("20") int limit) {
        List<SyncLog> logs = syncService.getSyncLogs(limit);
        return Response.ok(ApiResponse.success(logs)).build();
    }

    @GET
    @Path("/servers")
    public Response listServers() {
        List<SyncServer> servers = SyncServer.listAll();
        return Response.ok(ApiResponse.success(servers)).build();
    }

    @POST
    @Path("/servers")
    public Response addServer(SyncServer server) {
        if (server.name == null || server.name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server name is required")).build();
        }
        if (server.url == null || server.url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server URL is required")).build();
        }
        server.url = SyncServerService.normalizeUrl(server.url);
        if (server.url.length() > 500) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server URL must not exceed 500 characters")).build();
        }
        try {
            new java.net.URI(server.url);
        } catch (java.net.URISyntaxException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid server URL format: " + e.getMessage())).build();
        }
        if (server.apiKey == null || server.apiKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("API key is required")).build();
        }
        if (server.apiKey.length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("API key must not exceed 255 characters")).build();
        }
        if (server.name != null && server.name.length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server name must not exceed 255 characters")).build();
        }

        server = syncServerService.addServer(server);
        return Response.ok(ApiResponse.success(server)).build();
    }

    @PUT
    @Path("/servers/{id}")
    public Response updateServer(@PathParam("id") Long id, SyncServer updated) {
        if (updated.apiKey != null && updated.apiKey.length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("API key must not exceed 255 characters")).build();
        }
        if (updated.name != null && updated.name.length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server name must not exceed 255 characters")).build();
        }
        if (updated.url != null) {
            updated.url = SyncServerService.normalizeUrl(updated.url);
            if (updated.url.length() > 500) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Server URL must not exceed 500 characters")).build();
            }
        }

        SyncServer server = syncServerService.updateServer(id, updated);
        if (server == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Server not found")).build();
        }
        return Response.ok(ApiResponse.success(server)).build();
    }

    @POST
    @Path("/servers/test-connection")
    public Response testConnection(Map<String, String> data) {
        String url = data.get("url");
        String apiKey = data.get("apiKey");

        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Server URL is required")).build();
        }
        url = SyncServerService.normalizeUrl(url);
        if (apiKey == null || apiKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("API key is required")).build();
        }

        log.addLog("Testing connection to sync server: " + url);
        boolean reachable = remoteClient.checkConnection(url, apiKey);
        if (reachable) {
            log.addLog("Connection test succeeded for " + url);
            return Response.ok(ApiResponse.success(Map.of(
                    "reachable", true,
                    "message", "Connection successful"
            ))).build();
        } else {
            log.addLog("Connection test failed for " + url);
            return Response.ok(ApiResponse.success(Map.of(
                    "reachable", false,
                    "message", "Server is unreachable — check URL, API key, and that the remote server is running"
            ))).build();
        }
    }

    @DELETE
    @Path("/servers/{id}")
    public Response deleteServer(@PathParam("id") Long id) {
        if (!syncServerService.deleteServer(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Server not found")).build();
        }
        return Response.ok(ApiResponse.success("Server deleted")).build();
    }

}
