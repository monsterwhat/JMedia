package Services;

import Models.Video.CollectionEntry;
import Models.Video.ExternalVideo;
import Models.Video.Genre;
import Models.Video.MediaCollection;
import Models.Music.Playlist;
import Models.Settings.Settings;
import Models.Music.Song;
import Models.Music.SongAnalysis;
import Models.Video.SubtitleTrack;
import Models.Settings.SyncLog;
import Models.Settings.SyncServer;
import Models.Video.Video;
import Models.DTOs.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Collectors;

@ApplicationScoped
public class SyncService {

    private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());

    @PersistenceContext
    EntityManager em;

    @PersistenceContext(unitName = "music")
    EntityManager musicEm;

    @PersistenceContext(unitName = "video")
    EntityManager videoEm;

    @Inject
    RemoteJMediaClient remoteClient;

    @Inject
    SettingsService settingsService;

    private volatile boolean syncInProgress = false;
    private volatile String currentSyncType = "";
    private volatile String currentServerName = "";
    private volatile int currentTotalItems = 0;
    private volatile int currentItemsProcessed = 0;
    private volatile SyncLog currentSyncLog = null;

    // ── De-duplication ──────────────────────────────────────────────────────

    private LocalDateTime getLastSyncTime(SyncServer server, String syncType) {
        List<String> types = new ArrayList<>();
        types.add(syncType);
        if (!"ALL".equals(syncType)) {
            types.add("ALL");
        }
        SyncLog lastLog = SyncLog.find(
            "server = ?1 AND syncType IN (?2) AND status = 'SUCCESS' ORDER BY completedAt DESC",
            server, types
        ).firstResult();
        return lastLog != null ? lastLog.completedAt : null;
    }

    @Scheduled(cron = "{sync.schedule}")
    public void scheduledSync() {
        Settings settings = settingsService.getOrCreateSettings();
        if (!settings.getSyncEnabled()) {
            return;
        }
        LOGGER.info("[Sync] Scheduled sync triggered");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(
            () -> syncAllServers("ALL", 0)
        );
    }

    public boolean isSyncInProgress() {
        return syncInProgress;
    }

    public String getCurrentSyncType() {
        return currentSyncType;
    }

    public String getCurrentServerName() {
        return currentServerName;
    }

    public int getCurrentTotalItems() {
        return currentTotalItems;
    }

    public int getCurrentItemsProcessed() {
        return currentItemsProcessed;
    }

    public SyncLog getCurrentSyncLog() {
        return currentSyncLog;
    }

    @Transactional
    public void syncAllServers() {
        syncAllServers("ALL", 0);
    }

    @Transactional
    public void syncAllServers(String syncType, int limit) {
        if (syncInProgress) {
            LOGGER.warning("[Sync] Sync already in progress, skipping");
            return;
        }

        syncInProgress = true;
        try {
            List<SyncServer> servers = SyncServer.list("enabled", true);
            if (servers.isEmpty()) {
                LOGGER.info("[Sync] No enabled sync servers configured");
                return;
            }

            Settings settings = settingsService.getOrCreateSettings();
            int effectiveLimit = limit > 0 ? limit : (settings.getSyncItemLimit() != null && settings.getSyncItemLimit() > 0 ? settings.getSyncItemLimit() : 0);

            for (SyncServer server : servers) {
                syncWithServer(server, settings, syncType, effectiveLimit);
            }
        } finally {
            syncInProgress = false;
            currentSyncType = "";
            currentServerName = "";
            currentTotalItems = 0;
            currentItemsProcessed = 0;
            currentSyncLog = null;
        }
    }

    @Transactional
    void syncWithServer(SyncServer server, Settings settings) {
        syncWithServer(server, settings, "ALL", 0);
    }

    @Transactional
    void syncWithServer(SyncServer server, Settings settings, String syncType, int limit) {
        LOGGER.info("[Sync] Starting " + syncType + " sync with " + server.name + " (" + server.url + ")");

        SyncLog syncLog = new SyncLog();
        syncLog.server = server;
        syncLog.startedAt = LocalDateTime.now();
        syncLog.status = "IN_PROGRESS";
        syncLog.syncType = syncType;
        syncLog.limitCount = limit;
        em.persist(syncLog);

        currentSyncLog = syncLog;
        currentServerName = server.name;
        currentSyncType = syncType;

        try {
            boolean syncAll = "ALL".equals(syncType);

            if (syncAll || "MUSIC_ONLY".equals(syncType)) {
                if (settings.getSyncMusicEnabled()) {
                    syncMusic(server, syncLog, settings, limit);
                } else if (!syncAll) {
                    LOGGER.info("[Sync] Music sync is disabled in settings, skipping");
                }
            }

            if (syncAll || "VIDEO_ONLY".equals(syncType)) {
                if (settings.getSyncVideoEnabled()) {
                    syncVideo(server, syncLog, settings, limit);
                } else if (!syncAll) {
                    LOGGER.info("[Sync] Video sync is disabled in settings, skipping");
                }
            }

            if (syncAll || "SUBTITLES_ONLY".equals(syncType)) {
                if (settings.getSyncSubtitlesEnabled()) {
                    syncSubtitles(server, syncLog, settings, limit);
                } else if (!syncAll) {
                    LOGGER.info("[Sync] Subtitle sync is disabled in settings, skipping");
                }
            }

            if (syncAll || "COLLECTIONS_ONLY".equals(syncType)) {
                syncCollections(server, syncLog, settings, limit);
            }

            if (syncAll || "PLAYLISTS_ONLY".equals(syncType)) {
                if (settings.getSyncPlaylistsEnabled()) {
                    syncPlaylists(server, syncLog, settings, limit);
                } else if (!syncAll) {
                    LOGGER.info("[Sync] Playlist sync is disabled in settings, skipping");
                }
            }

            if (syncAll || "TIMELINES_ONLY".equals(syncType)) {
                if (settings.getSyncTimelinesEnabled()) {
                    syncTimelines(server, syncLog, settings, limit);
                } else if (!syncAll) {
                    LOGGER.info("[Sync] Timeline sync is disabled in settings, skipping");
                }
            }

            syncLog.status = "SUCCESS";
            syncLog.completedAt = LocalDateTime.now();

            server.lastSyncStatus = "SUCCESS";
            server.lastSyncError = null;
            server.lastSyncAt = LocalDateTime.now();
            em.merge(server);

            LOGGER.info("[Sync] Completed " + syncType + " sync with " + server.name
                    + " | songs: sent=" + syncLog.songsSent + " received=" + syncLog.songsReceived
                    + " | videos: sent=" + syncLog.videosSent + " received=" + syncLog.videosReceived
                    + " | subtitles: sent=" + syncLog.subtitlesSent + " received=" + syncLog.subtitlesReceived
                    + " | collections: sent=" + syncLog.collectionsSent + " received=" + syncLog.collectionsReceived);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            if (e instanceof SecurityException) {
                errorMsg = "Authentication failed - invalid API key for remote server";
            } else if (e instanceof java.net.ConnectException) {
                errorMsg = "Connection refused - server is down or unreachable";
            } else if (e instanceof java.net.http.HttpConnectTimeoutException) {
                errorMsg = "Connection timed out - server did not respond within " + RemoteJMediaClient.CONNECT_TIMEOUT_SECONDS + "s";
            } else if (e instanceof java.net.SocketTimeoutException) {
                errorMsg = "Read timed out - sync data transfer took too long";
            } else if (e instanceof java.net.UnknownHostException) {
                errorMsg = "Unknown host - check the server URL";
            } else if (e instanceof java.security.GeneralSecurityException) {
                errorMsg = "SSL/TLS error - check HTTPS configuration";
            } else if (e instanceof jakarta.ws.rs.ProcessingException) {
                errorMsg = "HTTP processing error: " + e.getMessage();
            } else if (errorMsg != null && errorMsg.startsWith("Remote server returned HTTP")) {
                errorMsg = "Remote server error: " + errorMsg;
            }

            syncLog.status = "FAILED";
            syncLog.errorMessage = errorMsg;
            syncLog.completedAt = LocalDateTime.now();

            server.lastSyncStatus = "FAILED";
            server.lastSyncError = errorMsg;
            server.lastSyncAt = LocalDateTime.now();
            em.merge(server);

            LOGGER.log(Level.SEVERE, "[Sync] Sync failed with " + server.name + " - " + errorMsg, e);
        }
    }

    // ── Music Sync ──────────────────────────────────────────────────────────

    private void syncMusic(SyncServer server, SyncLog syncLog, Settings settings, int limit) throws Exception {
        LOGGER.info("[Sync] Syncing music metadata with " + server.name);

        LocalDateTime lastSync = getLastSyncTime(server, "MUSIC_ONLY");
        List<SyncSongData> allSongs = buildSongExchangeData(limit, lastSync);

        if (allSongs == null || allSongs.isEmpty()) {
            LOGGER.info("[Sync] No new/changed music to sync with " + server.name);
            return;
        }

        syncLog.songsSent = allSongs.size();
        syncLog.totalItems += allSongs.size();
        syncLog.syncedItemIds = allSongs.stream()
                .map(s -> "song:" + s.musicbrainzId)
                .collect(Collectors.joining(","));

        int batchSize = 5;
        int totalBatches = (allSongs.size() + batchSize - 1) / batchSize;

        for (int i = 0; i < allSongs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allSongs.size());
            List<SyncSongData> batch = allSongs.subList(i, end);

            SyncExchangeRequest batchRequest = new SyncExchangeRequest();
            batchRequest.songs = batch;
            batchRequest.syncType = "MUSIC_ONLY";

            SyncExchangeResponse response = remoteClient.exchange(server.url, server.apiKey, batchRequest);

            if (response != null) {
                if (response.songs != null && !response.songs.isEmpty()) {
                    syncLog.songsReceived += response.songs.size();
                    applySongUpdates(response);
                }
                if (response.updatedIds != null) {
                    syncLog.songsUpdated += response.updatedIds.size();
                }
                if (response.createdIds != null) {
                    syncLog.songsCreated += response.createdIds.size();
                }
            }

            syncLog.itemsProcessed += batch.size();
            currentItemsProcessed = syncLog.itemsProcessed;
            em.merge(syncLog);

            LOGGER.info("[Sync] Music batch " + ((i / batchSize) + 1) + "/" + totalBatches
                    + " done for " + server.name);
        }
    }

    // ── Video Sync ──────────────────────────────────────────────────────────

    private void syncVideo(SyncServer server, SyncLog syncLog, Settings settings, int limit) throws Exception {
        LOGGER.info("[Sync] Syncing video metadata with " + server.name);

        LocalDateTime lastSync = getLastSyncTime(server, "VIDEO_ONLY");
        List<SyncVideoData> allVideos = buildVideoExchangeData(limit, lastSync);

        if (allVideos == null || allVideos.isEmpty()) {
            LOGGER.info("[Sync] No new/changed videos to sync with " + server.name);
            return;
        }

        syncLog.videosSent = allVideos.size();
        syncLog.totalItems += allVideos.size();
        String videoIds = allVideos.stream()
                .map(v -> "video:" + v.videoId)
                .collect(Collectors.joining(","));
        syncLog.syncedItemIds = syncLog.syncedItemIds != null
                ? syncLog.syncedItemIds + "," + videoIds
                : videoIds;

        int batchSize = 5;
        int totalBatches = (allVideos.size() + batchSize - 1) / batchSize;

        for (int i = 0; i < allVideos.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allVideos.size());
            List<SyncVideoData> batch = allVideos.subList(i, end);

            SyncExchangeRequest batchRequest = new SyncExchangeRequest();
            batchRequest.videos = batch;
            batchRequest.syncType = "VIDEO_ONLY";

            SyncExchangeResponse response = remoteClient.exchange(server.url, server.apiKey, batchRequest);

            if (response != null) {
                if (response.videos != null && !response.videos.isEmpty()) {
                    syncLog.videosReceived += response.videos.size();
                    applyVideoUpdates(response);
                }
                if (response.updatedIds != null) {
                    syncLog.videosUpdated += response.updatedIds.size();
                }
            }

            syncLog.itemsProcessed += batch.size();
            currentItemsProcessed = syncLog.itemsProcessed;
            em.merge(syncLog);

            LOGGER.info("[Sync] Video batch " + ((i / batchSize) + 1) + "/" + totalBatches
                    + " done for " + server.name);
        }
    }

    // ── Collections Sync ────────────────────────────────────────────────────

    private void syncCollections(SyncServer server, SyncLog syncLog, Settings settings, int limit) throws Exception {
        LOGGER.info("[Sync] Syncing collections with " + server.name);

        // Collections have no dateModified field, so always send all
        List<SyncCollectionData> allCollections = buildCollectionExchangeData(limit, null);

        if (allCollections == null || allCollections.isEmpty()) {
            LOGGER.info("[Sync] No collections to sync with " + server.name);
            return;
        }

        syncLog.collectionsSent = allCollections.size();
        syncLog.totalItems += allCollections.size();
        String collIds = allCollections.stream()
                .map(c -> "collection:" + c.collectionId)
                .collect(Collectors.joining(","));
        syncLog.syncedItemIds = syncLog.syncedItemIds != null
                ? syncLog.syncedItemIds + "," + collIds
                : collIds;

        int batchSize = 5;

        for (int i = 0; i < allCollections.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allCollections.size());
            List<SyncCollectionData> batch = allCollections.subList(i, end);

            SyncExchangeRequest batchRequest = new SyncExchangeRequest();
            batchRequest.collections = batch;
            batchRequest.syncType = "COLLECTIONS_ONLY";

            SyncExchangeResponse response = remoteClient.exchange(server.url, server.apiKey, batchRequest);

            if (response != null) {
                if (response.collections != null && !response.collections.isEmpty()) {
                    syncLog.collectionsReceived += response.collections.size();
                }
            }

            syncLog.itemsProcessed += batch.size();
            currentItemsProcessed = syncLog.itemsProcessed;
            em.merge(syncLog);

            LOGGER.info("[Sync] Collections batch " + ((i / batchSize) + 1) + "/"
                    + ((allCollections.size() + batchSize - 1) / batchSize) + " done for " + server.name);
        }
    }

    // ── Playlists Sync (placeholder) ────────────────────────────────────────

    private void syncPlaylists(SyncServer server, SyncLog syncLog, Settings settings, int limit) {
        LOGGER.info("[Sync] Playlist sync with " + server.name + " - not yet implemented");
        syncLog.playlistsSent = 0;
    }

    // ── Timelines Sync (placeholder) ────────────────────────────────────────

    private void syncTimelines(SyncServer server, SyncLog syncLog, Settings settings, int limit) {
        LOGGER.info("[Sync] Timeline sync with " + server.name + " - not yet implemented");
    }

    // ── Subtitles Sync ──────────────────────────────────────────────────────

    private void syncSubtitles(SyncServer server, SyncLog syncLog, Settings settings, int limit) throws Exception {
        LOGGER.info("[Sync] Syncing subtitles with " + server.name);

        // SubtitleTrack has no update timestamp, so always send all active tracks
        List<SyncSubtitleData> allSubtitles = buildSubtitleExchangeData(limit);

        if (allSubtitles == null || allSubtitles.isEmpty()) {
            LOGGER.info("[Sync] No subtitles to sync with " + server.name);
            return;
        }

        syncLog.subtitlesSent = allSubtitles.size();
        syncLog.totalItems += allSubtitles.size();
        String subIds = allSubtitles.stream()
                .map(s -> "subtitle:" + s.trackId)
                .collect(Collectors.joining(","));
        syncLog.syncedItemIds = syncLog.syncedItemIds != null
                ? syncLog.syncedItemIds + "," + subIds
                : subIds;

        int batchSize = 5;

        for (int i = 0; i < allSubtitles.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allSubtitles.size());
            List<SyncSubtitleData> batch = allSubtitles.subList(i, end);

            SyncExchangeRequest batchRequest = new SyncExchangeRequest();
            batchRequest.subtitles = batch;
            batchRequest.syncType = "SUBTITLES_ONLY";

            SyncExchangeResponse response = remoteClient.exchange(server.url, server.apiKey, batchRequest);

            if (response != null && response.subtitles != null && !response.subtitles.isEmpty()) {
                syncLog.subtitlesReceived += response.subtitles.size();
                applySubtitleUpdates(response);
            }

            syncLog.itemsProcessed += batch.size();
            currentItemsProcessed = syncLog.itemsProcessed;
            em.merge(syncLog);

            LOGGER.info("[Sync] Subtitles batch " + ((i / batchSize) + 1) + "/"
                    + ((allSubtitles.size() + batchSize - 1) / batchSize) + " done for " + server.name);
        }
    }

    // ── Data Builders ───────────────────────────────────────────────────────

    private List<SyncSongData> buildSongExchangeData() {
        return buildSongExchangeData(0, null);
    }

    private List<SyncSongData> buildSongExchangeData(int limit, LocalDateTime lastSyncTime) {
        List<Song> songs;
        if (limit > 0 && lastSyncTime != null) {
            songs = Song.find("musicbrainzId is not null AND updatedAt > ?1", lastSyncTime)
                    .range(0, limit - 1).list();
        } else if (limit > 0) {
            songs = Song.find("musicbrainzId is not null").range(0, limit - 1).list();
        } else if (lastSyncTime != null) {
            songs = Song.list("musicbrainzId is not null AND updatedAt > ?1", lastSyncTime);
        } else {
            songs = Song.list("musicbrainzId is not null");
        }
        if (songs == null || songs.isEmpty()) {
            return new ArrayList<>();
        }

        List<SyncSongData> result = new ArrayList<>();
        for (Song song : songs) {
            SyncSongData data = new SyncSongData();
            data.musicbrainzId = song.getMusicbrainzId();
            data.title = song.getTitle();
            data.artist = song.getArtist();
            data.album = song.getAlbum();
            data.albumArtist = song.getAlbumArtist();
            data.trackNumber = song.getTrackNumber();
            data.discNumber = song.getDiscNumber();
            data.date = song.getDate();
            data.releaseDate = song.getReleaseDate();
            data.genre = song.getGenre();
            data.lyrics = null;
            data.explicit = song.isExplicit();
            data.bpm = song.getBpm();
            data.durationSeconds = song.getDurationSeconds();
            data.updatedAt = song.getUpdatedAt();

            SongAnalysis analysis = song.getAnalysis();
            if (analysis != null) {
                data.beatTimes = analysis.getBeatTimes() != null
                        ? new ArrayList<>(analysis.getBeatTimes())
                        : null;
                data.segmentFeaturesJson = analysis.getSegmentFeaturesJson();
                data.similarBeatsJson = analysis.getSimilarBeatsJson();
                data.beatMetadataJson = analysis.getBeatMetadataJson();
                data.beatCount = analysis.getBeatCount();
                data.averageBpm = analysis.getAverageBpm();
                data.analysisTimestamp = analysis.getAnalysisTimestamp();
                data.analysisStatus = analysis.getStatus() != null ? analysis.getStatus().name() : null;
                data.analysisErrorMessage = analysis.getErrorMessage();
            }

            result.add(data);
        }
        return result;
    }

    private List<SyncVideoData> buildVideoExchangeData(int limit, LocalDateTime lastSyncTime) {
        List<Video> videos;
        if (limit > 0 && lastSyncTime != null) {
            videos = Video.find("isActive = true AND dateModified > ?1", lastSyncTime)
                    .range(0, limit - 1).list();
        } else if (limit > 0) {
            videos = Video.find("isActive", true).range(0, limit - 1).list();
        } else if (lastSyncTime != null) {
            videos = Video.list("isActive = true AND dateModified > ?1", lastSyncTime);
        } else {
            videos = Video.list("isActive", true);
        }
        if (videos == null || videos.isEmpty()) {
            return new ArrayList<>();
        }

        List<SyncVideoData> result = new ArrayList<>();
        for (Video video : videos) {
            result.add(SyncVideoData.fromVideo(video));
        }
        return result;
    }

    private List<SyncCollectionData> buildCollectionExchangeData(int limit, LocalDateTime lastSyncTime) {
        List<MediaCollection> collections;
        if (limit > 0 && lastSyncTime != null) {
            // Collections have createdDate but no dateModified — approximate filter
            collections = MediaCollection.find("createdDate > ?1", lastSyncTime)
                    .range(0, limit - 1).list();
        } else if (limit > 0) {
            collections = MediaCollection.findAll().range(0, limit - 1).list();
        } else if (lastSyncTime != null) {
            collections = MediaCollection.list("createdDate > ?1", lastSyncTime);
        } else {
            collections = MediaCollection.listAll();
        }
        if (collections == null || collections.isEmpty()) {
            return new ArrayList<>();
        }

        List<SyncCollectionData> result = new ArrayList<>();
        for (MediaCollection collection : collections) {
            SyncCollectionData data = SyncCollectionData.fromCollection(collection);
            // Attach entries
            List<CollectionEntry> entries = CollectionEntry.list("collection", collection);
            if (entries != null && !entries.isEmpty()) {
                data.entries = new ArrayList<>();
                for (CollectionEntry entry : entries) {
                    SyncCollectionData.SyncCollectionEntryData ed = new SyncCollectionData.SyncCollectionEntryData();
                    ed.videoId = entry.video != null ? entry.video.id : null;
                    ed.externalVideoId = entry.externalVideo != null ? entry.externalVideo.id : null;
                    ed.orderIndex = entry.orderIndex;
                    ed.notes = entry.notes;
                    data.entries.add(ed);
                }
            }
            result.add(data);
        }
        return result;
    }

    // ── Subtitle Builders ───────────────────────────────────────────────────

    private List<SyncSubtitleData> buildSubtitleExchangeData(int limit) {
        List<SubtitleTrack> tracks;
        if (limit > 0) {
            tracks = SubtitleTrack.find("isActive", true).range(0, limit - 1).list();
        } else {
            tracks = SubtitleTrack.list("isActive", true);
        }
        if (tracks == null || tracks.isEmpty()) {
            return new ArrayList<>();
        }

        List<SyncSubtitleData> result = new ArrayList<>();
        for (SubtitleTrack track : tracks) {
            result.add(SyncSubtitleData.fromTrack(track));
        }
        return result;
    }

    // ── Remote Update Appliers ──────────────────────────────────────────────

    private void applySongUpdates(SyncExchangeResponse response) {
        if (response.songs == null) return;

        for (SyncSongData remoteSong : response.songs) {
            if (remoteSong.musicbrainzId == null || remoteSong.musicbrainzId.isBlank()) continue;

            try {
                Song localSong = Song.find("musicbrainzId", remoteSong.musicbrainzId).firstResult();
                if (localSong == null) continue;

                if (remoteSong.updatedAt != null && localSong.getUpdatedAt() != null
                        && !remoteSong.updatedAt.isAfter(localSong.getUpdatedAt())) {
                    continue;
                }
                populateSongFromSyncData(localSong, remoteSong);
                musicEm.merge(localSong);
                if (response.updatedIds != null) {
                    response.updatedIds.add(remoteSong.musicbrainzId);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[Sync] Failed to apply song update for musicbrainzId: "
                        + remoteSong.musicbrainzId, e);
                if (response.errors != null) {
                    response.errors.add(remoteSong.musicbrainzId + ": " + e.getMessage());
                }
            }
        }
    }

    private void applyVideoUpdates(SyncExchangeResponse response) {
        if (response.videos == null) return;

        for (SyncVideoData remoteVideo : response.videos) {
            if (remoteVideo.videoId == null) continue;

            try {
                Video localVideo = Video.findById(remoteVideo.videoId);
                if (localVideo == null) continue;

                if (remoteVideo.dateModified != null && localVideo.dateModified != null
                        && !remoteVideo.dateModified.isAfter(localVideo.dateModified)) {
                    continue;
                }
                remoteVideo.applyTo(localVideo);
                videoEm.merge(localVideo);
                if (response.updatedIds != null) {
                    response.updatedIds.add(String.valueOf(remoteVideo.videoId));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[Sync] Failed to apply video update for id: "
                        + remoteVideo.videoId, e);
            }
        }
    }

    private void applySubtitleUpdates(SyncExchangeResponse response) {
        if (response.subtitles == null) return;

        for (SyncSubtitleData remoteTrack : response.subtitles) {
            if (remoteTrack.trackId == null) continue;

            try {
                SubtitleTrack localTrack = SubtitleTrack.findById(remoteTrack.trackId);
                if (localTrack == null) {
                    // Create new subtitle track from sync data
                    localTrack = new SubtitleTrack();
                    localTrack.filename = remoteTrack.filename;
                    localTrack.fullPath = remoteTrack.fullPath;
                    if (remoteTrack.videoId != null) {
                        localTrack.video = Video.findById(remoteTrack.videoId);
                    }
                }
                remoteTrack.applyTo(localTrack);
                videoEm.merge(localTrack);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[Sync] Failed to apply subtitle update for id: "
                        + remoteTrack.trackId, e);
            }
        }
    }

    private void populateSongFromSyncData(Song song, SyncSongData data) {
        data.applyTo(song);
    }

    public SyncLog getLastSyncLog() {
        return SyncLog.find("ORDER BY startedAt DESC").firstResult();
    }

    public List<SyncLog> getSyncLogs(int limit) {
        return SyncLog.find("ORDER BY startedAt DESC").range(0, limit - 1).list();
    }

    public long getMusicCount() {
        return Song.count("musicbrainzId is not null");
    }

    public long getVideoCount() {
        return Video.count("isActive", true);
    }

    public long getCollectionCount() {
        return MediaCollection.count();
    }

    public long getSubtitleCount() {
        return SubtitleTrack.count("isActive", true);
    }

}
