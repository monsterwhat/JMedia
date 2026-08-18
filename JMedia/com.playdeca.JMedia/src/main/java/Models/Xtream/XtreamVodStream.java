package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

public class XtreamVodStream {
    @JsonProperty("num")
    public int num;

    @JsonProperty("name")
    public String name;

    @JsonProperty("stream_id")
    public Long streamId;

    @JsonProperty("stream_icon")
    public String streamIcon;

    @JsonProperty("rating")
    public String rating;

    @JsonProperty("rating_5based")
    public double rating5based;

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

    @JsonProperty("stream_type")
    public String streamType;

    @JsonProperty("category_ids")
    public List<Integer> categoryIds = new ArrayList<>();
}
