package Models.Music;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import Utils.GzipJsonConverter;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper=false)
@Entity
public class SongAnalysis extends PanacheEntity {

    @OneToOne
    @JoinColumn(name = "song_id", referencedColumnName = "id")
    private Song song;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = GzipJsonConverter.class)
    private String beatTimesJson;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = GzipJsonConverter.class)
    private String segmentFeaturesJson;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = GzipJsonConverter.class)
    private String similarBeatsJson;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = GzipJsonConverter.class)
    private String beatMetadataJson;

    private Integer beatCount;
    private Double averageBpm;
    private Long analysisTimestamp;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    private String errorMessage;

    public enum AnalysisStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public double[] getBeatTimesArray() {
        if (beatTimesJson == null || beatTimesJson.isBlank()) {
            return new double[0];
        }
        try {
            return MAPPER.readValue(beatTimesJson, double[].class);
        } catch (Exception e) {
            return new double[0];
        }
    }

    public List<Double> getBeatTimes() {
        double[] arr = getBeatTimesArray();
        if (arr.length == 0) {
            return new ArrayList<>();
        }
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) {
            list.add(v);
        }
        return list;
    }

    public void setBeatTimes(List<Double> times) {
        if (times == null || times.isEmpty()) {
            beatTimesJson = "[]";
            return;
        }
        try {
            beatTimesJson = MAPPER.writeValueAsString(times);
        } catch (Exception e) {
            beatTimesJson = "[]";
        }
    }

    public int findBeatIndexAtTime(double timeSeconds) {
        double[] beats = getBeatTimesArray();
        if (beats.length == 0) {
            return -1;
        }

        int low = 0;
        int high = beats.length - 1;

        while (low <= high) {
            int mid = (low + high) / 2;
            if (beats[mid] < timeSeconds) {
                low = mid + 1;
            } else if (beats[mid] > timeSeconds) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        if (low >= beats.length) {
            return beats.length - 1;
        } else if (low == 0) {
            return 0;
        } else {
            double diffLow = Math.abs(beats[low] - timeSeconds);
            double diffHigh = Math.abs(beats[low - 1] - timeSeconds);
            return diffLow < diffHigh ? low : low - 1;
        }
    }

    public List<Integer> getSimilarBeats(int beatIndex) {
        if (similarBeatsJson == null || similarBeatsJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            java.util.Map<String, List<Integer>> similarMap = MAPPER.readValue(
                similarBeatsJson,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, List<Integer>>>() {});
            return similarMap.getOrDefault(String.valueOf(beatIndex), new ArrayList<>());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public boolean isReady() {
        return status == AnalysisStatus.COMPLETED
            && beatTimesJson != null
            && !beatTimesJson.isBlank()
            && similarBeatsJson != null
            && !similarBeatsJson.isBlank()
            && averageBpm != null;
    }

    public List<BeatInfo> getBeatMetadata() {
        if (beatMetadataJson == null || beatMetadataJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(beatMetadataJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<BeatInfo>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Data
    public static class BeatInfo {
        private int index;
        private double time;
        private int beatInBar;
        private int barNumber;
        private double strength;
        private double relativePosition;
    }
}
