package Models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubRelease {
    private String url;
    private String htmlUrl;
    private String id;
    private String tagName;
    private String name;
    private boolean draft;
    private boolean prerelease;
    private Instant createdAt;
    private Instant publishedAt;
    private String body;
    private List<Asset> assets;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Asset {
        private String name;
        private String contentType;
        private long size;
        private String browserDownloadUrl;
        private String downloadCount;
        private Instant createdAt;
        private Instant updatedAt;
    }
}