package Models.DTOs;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class DjSettingsDTO {
    private List<String> genrePool = new ArrayList<>();
    private Integer songsPerGenre = 0;
    private Integer crossfade = -1;
    private String strictness = "MEDIUM";
    private Integer bpmMin = 0;
    private Integer bpmMax = 0;
    private Integer maxConsecutiveByArtist = 0;
    private Integer skipsBeforeGenreChange = 1;
    private Integer yearMin = 0;
    private Integer yearMax = 0;
    private boolean enabled;
}
