package API.Rest;

import API.ApiResponse;
import Models.MediaCollection;
import Models.Settings;
import Models.Song;
import Models.SongAnalysis;
import Models.SubtitleTrack;
import Models.Video;
import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import Models.DTOs.SyncSongData;
import Models.DTOs.SyncVideoData;
import Models.DTOs.SyncCollectionData;
import Models.DTOs.SyncSubtitleData;
import Services.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

@Path("/api/sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyncExchangeAPI {

    private static final Logger LOGGER = Logger.getLogger(SyncExchangeAPI.class.getName());

    @PersistenceContext
    EntityManager em;

    @Inject
    SettingsService settingsService;

    /**
     * Catches any exception that escapes the exchange() try-catch (e.g. Transactional
     * interceptor commit failures after the method returns) and returns a proper
     * ApiResponse.error() instead of Quarkus's generic {"details":"Error id ..."} response.
     */
    @ServerExceptionMapper
    public Response handleUnhandledException(Throwable t) {
        LOGGER.log(Level.SEVERE, "[SyncExchange] Unhandled exception — " + t.getMessage(), t);
        return Response.serverError()
                .entity(ApiResponse.error("Sync internal error: " +
                        (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())))
                .build();
    }

    @GET
    @Path("/ping")
    public Response ping(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!validateApiKey(headers)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Invalid API key")).build();
        }
        return Response.ok(ApiResponse.success("pong")).build();
    }

    @POST
    @Path("/exchange")
    @Transactional
    public Response exchange(SyncExchangeRequest request,
                             @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        LOGGER.info("[SyncExchange] exchange() entered — songs="
                + (request != null && request.songs != null ? request.songs.size() : "null")
                + " videos=" + (request != null && request.videos != null ? request.videos.size() : "null")
                + " collections=" + (request != null && request.collections != null ? request.collections.size() : "null")
                + " type=" + (request != null ? request.syncType : "null"));

        if (!validateApiKey(headers)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Invalid API key")).build();
        }

        if (request == null) {
            SyncExchangeResponse empty = new SyncExchangeResponse();
            return Response.ok(ApiResponse.success(empty)).build();
        }

        try {
            SyncExchangeResponse response = new SyncExchangeResponse();

            if (request.songs != null && !request.songs.isEmpty()) {
                processSongExchange(request.songs, response);
            }

            if (request.videos != null && !request.videos.isEmpty()) {
                processVideoExchange(request.videos, response);
            }

            if (request.subtitles != null && !request.subtitles.isEmpty()) {
                processSubtitleExchange(request.subtitles, response);
            }

            if (request.collections != null && !request.collections.isEmpty()) {
                processCollectionExchange(request.collections, response);
            }

            em.flush();
            return Response.ok(response).build();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[SyncExchange] exchange() failed — " + e.getMessage(), e);
            return Response.serverError()
                    .entity(ApiResponse.error("Sync processing error: " + e.getMessage()))
                    .build();
        }
    }

    private void processSongExchange(List<SyncSongData> songs, SyncExchangeResponse response) {
        List<SyncSongData> newerSongs = new ArrayList<>();

        for (SyncSongData remoteSong : songs) {
            if (remoteSong.musicbrainzId == null || remoteSong.musicbrainzId.isBlank()) {
                continue;
            }

            try {
                Song localSong = Song.find("musicbrainzId", remoteSong.musicbrainzId).firstResult();

                if (localSong == null) {
                    if (response.errors != null) {
                        response.errors.add(remoteSong.musicbrainzId + ": skipped (not found locally)");
                    }
                    continue;
                } else {
                    if (remoteSong.updatedAt != null && localSong.getUpdatedAt() != null
                            && remoteSong.updatedAt.isAfter(localSong.getUpdatedAt())) {
                        updateSongFromSyncData(localSong, remoteSong);
                        em.merge(localSong);
                        if (response.updatedIds != null) {
                            response.updatedIds.add(remoteSong.musicbrainzId);
                        }
                    } else if (remoteSong.updatedAt == null || localSong.getUpdatedAt() == null
                            || localSong.getUpdatedAt().isAfter(remoteSong.updatedAt)) {
                        SyncSongData localData = buildSyncDataFromSong(localSong);
                        newerSongs.add(localData);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[SyncExchange] Failed to process song: "
                        + remoteSong.musicbrainzId, e);
                if (response.errors != null) {
                    response.errors.add(remoteSong.musicbrainzId + ": " + e.getMessage());
                }
            }
        }

        response.songs = newerSongs;
    }

    private void processVideoExchange(List<SyncVideoData> videos, SyncExchangeResponse response) {
        List<SyncVideoData> newerVideos = new ArrayList<>();

        for (SyncVideoData remoteVideo : videos) {
            if (remoteVideo.videoId == null) continue;

            try {
                Video localVideo = Video.findById(remoteVideo.videoId);

                if (localVideo == null) {
                    LOGGER.fine("[SyncExchange] Video " + remoteVideo.videoId + " not found locally, skipping");
                    continue;
                }

                // Only update if remote is newer
                if (remoteVideo.dateModified != null && localVideo.dateModified != null
                        && remoteVideo.dateModified.isAfter(localVideo.dateModified)) {
                    remoteVideo.applyTo(localVideo);
                    em.merge(localVideo);
                    if (response.updatedIds != null) {
                        response.updatedIds.add(String.valueOf(remoteVideo.videoId));
                    }
                } else if (remoteVideo.dateModified == null || localVideo.dateModified == null
                        || localVideo.dateModified.isAfter(remoteVideo.dateModified)) {
                    // Local is newer — send it back
                    newerVideos.add(SyncVideoData.fromVideo(localVideo));
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[SyncExchange] Failed to process video: "
                        + remoteVideo.videoId, e);
            }
        }

        response.videos = newerVideos;
    }

    private void processCollectionExchange(List<SyncCollectionData> collections, SyncExchangeResponse response) {
        List<SyncCollectionData> newerCollections = new ArrayList<>();

        for (SyncCollectionData remoteCollection : collections) {
            if (remoteCollection.collectionId == null) continue;

            try {
                MediaCollection localCollection = MediaCollection.findById(remoteCollection.collectionId);

                if (localCollection == null) {
                    LOGGER.fine("[SyncExchange] Collection " + remoteCollection.collectionId + " not found locally, skipping");
                    continue;
                }

                    if (remoteCollection.name != null) localCollection.name = remoteCollection.name;
                if (remoteCollection.description != null) localCollection.description = remoteCollection.description;
                if (remoteCollection.isPublic != null) localCollection.isPublic = remoteCollection.isPublic;
                if (remoteCollection.coverVideoId != null) localCollection.coverVideoId = remoteCollection.coverVideoId;
                localCollection.sortOrder = remoteCollection.sortOrder;
                em.merge(localCollection);
                if (response.updatedIds != null) {
                    response.updatedIds.add(String.valueOf(remoteCollection.collectionId));
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[SyncExchange] Failed to process collection: "
                        + remoteCollection.collectionId, e);
            }
        }

        response.collections = newerCollections;
    }

    private void processSubtitleExchange(List<SyncSubtitleData> subtitles, SyncExchangeResponse response) {
        List<SyncSubtitleData> newerSubtitles = new ArrayList<>();

        for (SyncSubtitleData remoteTrack : subtitles) {
            if (remoteTrack.trackId == null) continue;

            try {
                SubtitleTrack localTrack = SubtitleTrack.findById(remoteTrack.trackId);

                if (localTrack == null) {
                    localTrack = createSubtitleFromSyncData(remoteTrack);
                }

                remoteTrack.applyTo(localTrack);
                em.merge(localTrack);
                if (response.updatedIds != null) {
                    response.updatedIds.add(String.valueOf(remoteTrack.trackId));
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[SyncExchange] Failed to process subtitle track: "
                        + remoteTrack.trackId, e);
            }
        }

        response.subtitles = newerSubtitles;
    }

    private SubtitleTrack createSubtitleFromSyncData(SyncSubtitleData data) {
        SubtitleTrack track = new SubtitleTrack();
        track.filename = data.filename;
        track.fullPath = data.fullPath;
        track.format = data.format;
        track.fileSize = data.fileSize;
        if (data.videoId != null) {
            track.video = Video.findById(data.videoId);
        }
        return track;
    }

    private boolean validateApiKey(jakarta.ws.rs.core.HttpHeaders headers) {
        Settings settings = settingsService.getOrCreateSettings();
        String localApiKey = settings.getSyncApiKey();
        if (localApiKey == null || localApiKey.isBlank()) {
            return false;
        }
        String requestKey = headers.getHeaderString("X-JMedia-Sync-Key");
        return localApiKey.equals(requestKey);
    }

    private SyncSongData buildSyncDataFromSong(Song song) {
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
        data.lyrics = null; // Excluded from sync — too large, receiver regenerates from file
        data.explicit = song.isExplicit();
        data.bpm = song.getBpm();
        data.durationSeconds = song.getDurationSeconds();
        data.artworkBase64 = null; // Excluded from sync — too large, receiver regenerates from file
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
        return data;
    }

    private void updateSongFromSyncData(Song song, SyncSongData data) {
        data.applyTo(song);
    }

}
