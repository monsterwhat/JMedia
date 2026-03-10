package Models.DTOs;

import Models.Video;
import java.util.List;
import lombok.Data;

@Data
public class TvShowDTO {
    public String seriesTitle;
    public int episodeCount;
    public int seasonCount;
    public String posterPath;
    public Long representativeId; // ID of one episode to show thumbnail
    
    public TvShowDTO(String seriesTitle, List<Video> episodes) {
        this.seriesTitle = seriesTitle;
        this.episodeCount = episodes.size();
        this.seasonCount = (int) episodes.stream()
                .map(v -> v.seasonNumber)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        
        // Use the poster/thumbnail from the first episode if available
        if (!episodes.isEmpty()) {
            Video first = episodes.get(0);
            this.representativeId = first.id;
            this.posterPath = first.posterPath;
        }
    }
}
