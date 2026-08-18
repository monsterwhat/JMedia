package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

public class XtreamSeries {
    @JsonProperty("num")
    public int num;

    @JsonProperty("name")
    public String name;

    @JsonProperty("series_id")
    public String seriesId;

    @JsonProperty("cover")
    public String cover;

    @JsonProperty("cover_big")
    public String coverBig;

    @JsonProperty("plot")
    public String plot;

    @JsonProperty("cast")
    public String cast;

    @JsonProperty("director")
    public String director;

    @JsonProperty("genre")
    public String genre;

    @JsonProperty("releaseDate")
    public String releaseDate;

    @JsonProperty("last_modified")
    public String lastModified;

    @JsonProperty("rating")
    public String rating;

    @JsonProperty("rating_5based")
    public double rating5based;

    @JsonProperty("category_id")
    public String categoryId;

    @JsonProperty("category_ids")
    public List<Integer> categoryIds = new ArrayList<>();

    @JsonProperty("year")
    public String year;

    @JsonProperty("backdrop_path")
    public List<String> backdropPath = new ArrayList<>();

    @JsonProperty("youtube_trailer")
    public String youtubeTrailer = "";
}
