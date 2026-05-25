package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;

public class XtreamCategory {
    @JsonProperty("category_id")
    public String categoryId;

    @JsonProperty("category_name")
    public String categoryName;

    @JsonProperty("parent_id")
    public int parentId = 0;

    public XtreamCategory(String id, String name) {
        this.categoryId = id;
        this.categoryName = name;
    }
}
