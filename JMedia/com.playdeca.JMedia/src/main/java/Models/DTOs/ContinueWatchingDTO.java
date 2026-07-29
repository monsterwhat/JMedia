package Models.DTOs;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContinueWatchingDTO {
    public Long id;
    public String title;
    public String type;
    public String seriesTitle;
    public String episodeTitle;
    public Integer seasonNumber;
    public Integer episodeNumber;
    public String description;
    public String overview;
    public Integer releaseYear;
    public Double imdbRating;
    public Long duration;
    public String thumbnailPath;
    public String backdropPath;
    public String posterPath;
    public String logoPath;
    public List<String> genres;
    public Double watchProgress;
    public Integer watchProgressPercent;
    public Boolean watched;
}
