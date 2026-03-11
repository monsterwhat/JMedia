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
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getBrowserDownloadUrl() { return browserDownloadUrl; }
        public void setBrowserDownloadUrl(String browserDownloadUrl) { this.browserDownloadUrl = browserDownloadUrl; }
        public String getDownloadCount() { return downloadCount; }
        public void setDownloadCount(String downloadCount) { this.downloadCount = downloadCount; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }
    
    // Explicit getters for fields used by UpdateService
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }
    public boolean isPrerelease() { return prerelease; }
    public void setPrerelease(boolean prerelease) { this.prerelease = prerelease; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public List<Asset> getAssets() { return assets; }
    public void setAssets(List<Asset> assets) { this.assets = assets; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}