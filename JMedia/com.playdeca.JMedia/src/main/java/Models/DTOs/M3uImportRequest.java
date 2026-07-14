package Models.DTOs;

public class M3uImportRequest {
    public String playlistUrl;
    public String rawText;
    public String playlistName;
    public Long profileId;
    public String importType; // "live", "vod", or "mixed" (default: "live")
}
