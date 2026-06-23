package API.Rest;

import API.ApiResponse;
import Models.Settings;
import Models.Song;
import Models.SongAnalysis;
import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import Models.DTOs.SyncSongData;
import Services.SettingsService;
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

@Path("/api/sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyncExchangeAPI {

    private static final Logger LOGGER = Logger.getLogger(SyncExchangeAPI.class.getName());

    @PersistenceContext
    EntityManager em;

    @Inject
    SettingsService settingsService;

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
        if (!validateApiKey(headers)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Invalid API key")).build();
        }

        if (request == null || request.songs == null || request.songs.isEmpty()) {
            SyncExchangeResponse empty = new SyncExchangeResponse();
            return Response.ok(ApiResponse.success(empty)).build();
        }

        SyncExchangeResponse response = new SyncExchangeResponse();
        List<SyncSongData> newerSongs = new ArrayList<>();

        for (SyncSongData remoteSong : request.songs) {
            if (remoteSong.musicbrainzId == null || remoteSong.musicbrainzId.isBlank()) {
                continue;
            }

            try {
                Song localSong = Song.find("musicbrainzId", remoteSong.musicbrainzId).firstResult();

                if (localSong == null) {
                    // Song doesn't exist locally — skip (only sync shared content)
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
        em.flush();
        return Response.ok(response).build();
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
        return data;
    }

    private void updateSongFromSyncData(Song song, SyncSongData data) {
        data.applyTo(song);
    }

}
