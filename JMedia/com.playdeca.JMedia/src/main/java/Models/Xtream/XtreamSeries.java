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

    @JsonProperty("rating_5based")
    public double rating5based;

    @JsonProperty("category_id")
    public String categoryId;

    @JsonProperty("stream_type")
    public String streamType = "series";

    @JsonProperty("epg_channel_id")
    public String epgChannelId = "";

    @JsonProperty("tv_archive")
    public int tvArchive = 0;

    @JsonProperty("tv_archive_duration")
    public int tvArchiveDuration = 0;
}
