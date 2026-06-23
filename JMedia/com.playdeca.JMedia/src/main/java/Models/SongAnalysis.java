package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores audio analysis data for EternalJukebox-style infinite mixing
 * Contains beat positions, segment features, and similar beat mappings
 */
@Data
@Entity
public class SongAnalysis extends PanacheEntity {

    @OneToOne
    @JoinColumn(name = "song_id", referencedColumnName = "id")
    private Song song;
    
    // Beat timestamps in seconds (sorted ascending)
    @ElementCollection
    @OrderBy
    @CollectionTable(name = "song_analysis_beats", joinColumns = @JoinColumn(name = "song_analysis_id"))
    @Column(name = "beat_time")
    private List<Double> beatTimes = new ArrayList<>();
    
    // Segment features for similarity matching (JSON serialized)
    // Each entry corresponds to a beat, containing timbre/pitch/loudness vectors
    @Column(length = Integer.MAX_VALUE)
    private String segmentFeaturesJson;
    
    // Similar beat mappings - JSON: {"beatIndex": [similarBeatIndex1, similarBeatIndex2, ...]}
    @Column(length = Integer.MAX_VALUE)
    private String similarBeatsJson;
    
    // Beat metadata for cross-song matching - JSON array of per-beat objects:
    // [{"index":0, "time":0.0, "beatInBar":1, "barNumber":0, "strength":1.0, "relativePosition":0.0}, ...]
    @Column(length = Integer.MAX_VALUE)
    private String beatMetadataJson;
    
    // Analysis metadata
    private Integer beatCount;
    private Double averageBpm;
    private Long analysisTimestamp;
    
    // Status: PENDING, COMPLETED, FAILED
    @Enumerated(EnumType.STRING)
    private AnalysisStatus status = AnalysisStatus.PENDING;
    
    // Error message if analysis failed
    private String errorMessage;
    
    public enum AnalysisStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
    
    /**
     * Get beat times as primitive array for efficient processing
     */
    public double[] getBeatTimesArray() {
        if (beatTimes == null || beatTimes.isEmpty()) {
            return new double[0];
        }
        double[] result = new double[beatTimes.size()];
        for (int i = 0; i < beatTimes.size(); i++) {
            result[i] = beatTimes.get(i);
        }
        return result;
    }
    
    /**
     * Find the beat index closest to a given time
     */
    public int findBeatIndexAtTime(double timeSeconds) {
        if (beatTimes == null || beatTimes.isEmpty()) {
            return -1;
        }
        
        int low = 0;
        int high = beatTimes.size() - 1;
        
        while (low <= high) {
            int mid = (low + high) / 2;
            double beatTime = beatTimes.get(mid);
            
            if (beatTime < timeSeconds) {
                low = mid + 1;
            } else if (beatTime > timeSeconds) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        
        // Return closest beat
        if (low >= beatTimes.size()) {
            return beatTimes.size() - 1;
        } else if (low == 0) {
            return 0;
        } else {
            // Return whichever is closer
            double diffLow = Math.abs(beatTimes.get(low) - timeSeconds);
            double diffHigh = Math.abs(beatTimes.get(low - 1) - timeSeconds);
            return diffLow < diffHigh ? low : low - 1;
        }
    }
    
    /**
     * Get similar beat indices for a given beat index
     */
    public List<Integer> getSimilarBeats(int beatIndex) {
        if (similarBeatsJson == null || similarBeatsJson.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, List<Integer>> similarMap = mapper.readValue(
                similarBeatsJson, 
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, List<Integer>>>() {}
            );
            
            String key = String.valueOf(beatIndex);
            return similarMap.getOrDefault(key, new ArrayList<>());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    /**
     * Check if analysis is ready for playback
     */
    public boolean isReady() {
        return status == AnalysisStatus.COMPLETED 
            && beatTimes != null 
            && !beatTimes.isEmpty()
            && similarBeatsJson != null 
            && !similarBeatsJson.isEmpty()
            && averageBpm != null;
    }
    
    /**
     * Get beat metadata for cross-song matching.
     * Returns list of BeatInfo objects parsed from JSON.
     */
    public List<BeatInfo> getBeatMetadata() {
        if (beatMetadataJson == null || beatMetadataJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(beatMetadataJson, 
                new com.fasterxml.jackson.core.type.TypeReference<List<BeatInfo>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    /**
     * Per-beat metadata for cross-song transition matching
     */
    @Data
    public static class BeatInfo {
        private int index;
        private double time;
        private int beatInBar;       // 1-4 (1 = downbeat)
        private int barNumber;
        private double strength;     // 0.0-1.0 (onset strength)
        private double relativePosition; // 0.0-1.0 (position in song)
    }
}