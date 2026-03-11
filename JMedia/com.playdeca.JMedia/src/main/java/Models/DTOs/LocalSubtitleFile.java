package Models.DTOs;

public class LocalSubtitleFile {
    public String filename;
    public String fullPath;
    public String languageName;
    public String format;
    public long fileSize;

    public LocalSubtitleFile() {}

    public LocalSubtitleFile(String filename, String fullPath, String languageName, String format, long fileSize) {
        this.filename = filename;
        this.fullPath = fullPath;
        this.languageName = languageName;
        this.format = format;
        this.fileSize = fileSize;
    }
}
