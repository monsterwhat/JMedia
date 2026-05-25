package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;

public class XtreamSeries {
    @JsonProperty("num")
    public int num;

    @JsonProperty("name")
    public String name;

    @JsonProperty("series_id")
    public String seriesId;

    @JsonProperty("cover")
    public String cover;

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

    @JsonProperty("rating_5plus")
    public double rating5plus;

    @JsonProperty("category_id")
    public String categoryId;
}
