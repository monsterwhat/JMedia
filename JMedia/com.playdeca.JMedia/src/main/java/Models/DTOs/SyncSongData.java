package Models.DTOs;

import Models.Song;
import Models.SongAnalysis;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SyncSongData {

    public String musicbrainzId;
    public String title;
    public String artist;
    public String album;
    public String albumArtist;
    public int trackNumber;
    public int discNumber;
    public String date;
    public String releaseDate;
    public String genre;
    public String lyrics;
    public boolean explicit;
    public int bpm;
    public int durationSeconds;
    public String artworkBase64;
    public LocalDateTime updatedAt;

    public List<Double> beatTimes;
    public String segmentFeaturesJson;
    public String similarBeatsJson;
    public String beatMetadataJson;
    public Integer beatCount;
    public Double averageBpm;
    public Long analysisTimestamp;
    public String analysisStatus;
    public String analysisErrorMessage;


    public void applyTo(Song song) {
        song.setTitle(title);
        song.setArtist(artist);
        song.setAlbum(album);
        song.setAlbumArtist(albumArtist);
        song.setTrackNumber(trackNumber);
        song.setDiscNumber(discNumber);
        song.setDate(date);
        song.setReleaseDate(releaseDate);
        song.setGenre(genre);
        song.setLyrics(lyrics);
        song.setExplicit(explicit);
        song.setBpm(bpm);
        song.setDurationSeconds(durationSeconds);
        song.setArtworkBase64(artworkBase64);
        song.setUpdatedAt(updatedAt != null ? updatedAt : LocalDateTime.now());

        if (beatTimes != null || averageBpm != null) {
            SongAnalysis analysis = song.getAnalysis();
            boolean isNewAnalysis = false;
            if (analysis == null) {
                analysis = new SongAnalysis();
                analysis.setSong(song);
                isNewAnalysis = true;
            }

            if (beatTimes != null) {
                analysis.setBeatTimes(new ArrayList<>(beatTimes));
            }
            if (segmentFeaturesJson != null) {
                analysis.setSegmentFeaturesJson(segmentFeaturesJson);
            }
            if (similarBeatsJson != null) {
                analysis.setSimilarBeatsJson(similarBeatsJson);
            }
            if (beatMetadataJson != null) {
                analysis.setBeatMetadataJson(beatMetadataJson);
            }
            if (beatCount != null) {
                analysis.setBeatCount(beatCount);
            }
            if (averageBpm != null) {
                analysis.setAverageBpm(averageBpm);
            }
            if (analysisTimestamp != null) {
                analysis.setAnalysisTimestamp(analysisTimestamp);
            }
            if (analysisStatus != null) {
                try {
                    analysis.setStatus(SongAnalysis.AnalysisStatus.valueOf(analysisStatus));
                } catch (IllegalArgumentException e) {
                    analysis.setStatus(SongAnalysis.AnalysisStatus.PENDING);
                }
            }
            if (analysisErrorMessage != null) {
                analysis.setErrorMessage(analysisErrorMessage);
            }

            if (isNewAnalysis) {
                song.setAnalysis(analysis);
            }
        }
    }
}
