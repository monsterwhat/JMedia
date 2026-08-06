package Services;

import Models.MediaCollection;
import Models.Song;
import Models.SongAnalysis;
import Models.SubtitleTrack;
import Models.Video;
import Models.DTOs.SyncCollectionData;
import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import Models.DTOs.SyncSongData;
import Models.DTOs.SyncSubtitleData;
import Models.DTOs.SyncVideoData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class SyncExchangeService {

    private static final Logger LOGGER = Logger.getLogger(SyncExchangeService.class.getName());

    @PersistenceContext
    EntityManager em;

    /**
     * Owns the full transactional exchange: validates the request (null -> empty
     * response), processes songs/videos/subtitles/collections and flushes. The
     * HTTP layer (SyncExchangeAPI) keeps API-key validation, the null-request
     * ApiResponse.success() envelope and the error mapping.
     */
    @Transactional
    public SyncExchangeResponse exchange(SyncExchangeRequest request) {
        if (request == null) {
            return new SyncExchangeResponse();
        }

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
        return response;
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
