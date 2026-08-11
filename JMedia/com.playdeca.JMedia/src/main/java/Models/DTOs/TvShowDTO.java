package Models.DTOs;

import Models.Video.Video;
import java.util.List;
import lombok.Data;

@Data
public class TvShowDTO {
    public String seriesTitle;
    public Long seriesId;
    public int episodeCount;
    public int seasonCount;
    public String posterPath;
    public Long representativeId;
    
    public TvShowDTO(String seriesTitle, List<Video> episodes) {
        this.seriesTitle = seriesTitle;
        this.episodeCount = episodes.size();
        this.seasonCount = (int) episodes.stream()
                .map(v -> v.seasonNumber)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        
        if (!episodes.isEmpty()) {
            Video first = episodes.get(0);
            this.representativeId = first.id;
            this.seriesId = first.series != null ? first.series.id : null;
            this.posterPath = first.series != null && first.series.posterPath != null
                    ? first.series.posterPath : first.posterPath;
        }
    }
}
