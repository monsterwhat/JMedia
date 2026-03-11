package Models.DTOs;

import lombok.Data;

@Data
public class SubtitleSearchResult {
    public String id; // OpenSubtitles file_id
    public String filename;
    public String language;
    public String languageCode; // e.g. "eng", "spl"
    public String provider = "OpenSubtitles";
    public Double rating;
    public Integer downloadCount;
    public String url; // Download URL or internal ID
    public boolean isForced;
    public boolean isSDH;
    public String format;
    public String fps;
}
