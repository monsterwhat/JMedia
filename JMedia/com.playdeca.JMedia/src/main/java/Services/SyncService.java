package Services;

import Models.Settings;
import Models.Song;
import Models.SongAnalysis;
import Models.SyncLog;
import Models.SyncServer;
import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import Models.DTOs.SyncSongData;
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

@ApplicationScoped
public class SyncService {

    private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());

    @PersistenceContext
    EntityManager em;

    @Inject
    RemoteJMediaClient remoteClient;

    @Inject
    SettingsService settingsService;

    private volatile boolean syncInProgress = false;

    @Scheduled(cron = "{sync.schedule}")
    public void scheduledSync() {
        Settings settings = settingsService.getOrCreateSettings();
        if (!settings.getSyncEnabled()) {
            return;
        }
        LOGGER.info("[Sync] Scheduled sync triggered");
        syncAllServers();
    }

    public boolean isSyncInProgress() {
        return syncInProgress;
    }

    public void syncAllServers() {
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

            for (SyncServer server : servers) {
                syncWithServer(server, settings);
            }
        } finally {
            syncInProgress = false;
        }
    }

    @Transactional
    void syncWithServer(SyncServer server, Settings settings) {
        LOGGER.info("[Sync] Starting sync with " + server.name + " (" + server.url + ")");

        SyncLog syncLog = new SyncLog();
        syncLog.server = server;
        syncLog.startedAt = LocalDateTime.now();
        syncLog.status = "IN_PROGRESS";
        em.persist(syncLog);

        try {
            boolean musicEnabled = settings.getSyncMusicEnabled();
            SyncExchangeRequest request = new SyncExchangeRequest();

            if (musicEnabled) {
                request.songs = buildSongExchangeData();
                syncLog.songsSent = request.songs != null ? request.songs.size() : 0;
            }

            SyncExchangeResponse response = remoteClient.exchange(server.url, server.apiKey, request);

            if (response != null) {
                if (musicEnabled && response.songs != null && !response.songs.isEmpty()) {
                    syncLog.songsReceived = response.songs.size();
                    applySongUpdates(response);
                }

                if (response.updatedIds != null) {
                    syncLog.songsUpdated = response.updatedIds.size();
                }
                if (response.createdIds != null) {
                    syncLog.songsCreated = response.createdIds.size();
                }
            }

            syncLog.status = "SUCCESS";
            syncLog.completedAt = LocalDateTime.now();

            server.lastSyncStatus = "SUCCESS";
            server.lastSyncError = null;
            server.lastSyncAt = LocalDateTime.now();
            em.merge(server);

            LOGGER.info("[Sync] Completed sync with " + server.name
                    + " | sent=" + syncLog.songsSent
                    + " received=" + syncLog.songsReceived
                    + " updated=" + syncLog.songsUpdated
                    + " created=" + syncLog.songsCreated);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (e instanceof java.net.ConnectException) {
                errorMsg = "Connection refused — server is down or unreachable";
            } else if (e instanceof java.net.http.HttpConnectTimeoutException) {
                errorMsg = "Connection timed out — server did not respond within " + RemoteJMediaClient.CONNECT_TIMEOUT_SECONDS + "s";
            } else if (e instanceof java.net.SocketTimeoutException) {
                errorMsg = "Read timed out — sync data transfer took too long";
            } else if (e instanceof java.net.UnknownHostException) {
                errorMsg = "Unknown host — check the server URL";
            } else if (e instanceof java.security.GeneralSecurityException) {
                errorMsg = "SSL/TLS error — check HTTPS configuration";
            } else if (e instanceof jakarta.ws.rs.ProcessingException) {
                errorMsg = "HTTP processing error: " + e.getMessage();
            }

            syncLog.status = "FAILED";
            syncLog.errorMessage = errorMsg;
            syncLog.completedAt = LocalDateTime.now();

            server.lastSyncStatus = "FAILED";
            server.lastSyncError = errorMsg;
            server.lastSyncAt = LocalDateTime.now();
            em.merge(server);

            LOGGER.log(Level.SEVERE, "[Sync] Sync failed with " + server.name
                    + " — " + errorMsg);
        }
    }

    private List<SyncSongData> buildSongExchangeData() {
        List<Song> songs = Song.list("musicbrainzId is not null");
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
            data.lyrics = song.getLyrics();
            data.explicit = song.isExplicit();
            data.bpm = song.getBpm();
            data.durationSeconds = song.getDurationSeconds();
            data.artworkBase64 = song.getArtworkBase64();
            data.updatedAt = song.getUpdatedAt();

            SongAnalysis analysis = song.getAnalysis();
            if (analysis != null) {
                data.beatTimes = analysis.getBeatTimes();
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

    private void applySongUpdates(SyncExchangeResponse response) {
        if (response.songs == null) {
            return;
        }

        for (SyncSongData remoteSong : response.songs) {
            if (remoteSong.musicbrainzId == null || remoteSong.musicbrainzId.isBlank()) {
                continue;
            }

            try {
                Song localSong = Song.find("musicbrainzId", remoteSong.musicbrainzId).firstResult();

                if (localSong == null) {
                    // Song not found locally — skip (only sync shared content)
                    continue;
                } else {
                    if (remoteSong.updatedAt != null && localSong.getUpdatedAt() != null
                            && !remoteSong.updatedAt.isAfter(localSong.getUpdatedAt())) {
                        continue;
                    }
                    populateSongFromSyncData(localSong, remoteSong);
                    em.merge(localSong);
                    if (response.updatedIds != null) {
                        response.updatedIds.add(remoteSong.musicbrainzId);
                    }
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

    private void populateSongFromSyncData(Song song, SyncSongData data) {
        data.applyTo(song);
    }

    public SyncLog getLastSyncLog() {
        return SyncLog.find("ORDER BY startedAt DESC").firstResult();
    }

    public List<SyncLog> getSyncLogs(int limit) {
        return SyncLog.find("ORDER BY startedAt DESC").range(0, limit - 1).list();
    }

}
