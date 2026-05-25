package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;

public class XtreamVodStream {
    @JsonProperty("num")
    public int num;

    @JsonProperty("name")
    public String name;

    @JsonProperty("stream_type")
    public String streamType = "movie";

    @JsonProperty("stream_id")
    public Long streamId;

    @JsonProperty("stream_icon")
    public String streamIcon;

    @JsonProperty("rating")
    public String rating;

    @JsonProperty("rating_5plus")
    public double rating5plus;

    @JsonProperty("added")
    public String added;

    @JsonProperty("category_id")
    public String categoryId;

    @JsonProperty("container_extension")
    public String containerExtension;

    @JsonProperty("custom_sid")
    public String customSid = "";

    @JsonProperty("direct_source")
    public String directSource = "";
}
