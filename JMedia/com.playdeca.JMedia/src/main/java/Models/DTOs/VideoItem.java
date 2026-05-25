package Models.DTOs;

import Models.Video;
import java.util.List;
import java.util.Objects;

public class VideoItem {
    private Long id;
    private String title;
    private String seriesTitle;
    private String type;
    private Integer seasonNumber;
    private Integer episodeNumber;
    private Integer releaseYear;

    public VideoItem(Video v) {
        this.id = v.id;
        this.title = v.title;
        this.seriesTitle = v.seriesTitle;
        this.type = v.type;
        this.seasonNumber = v.seasonNumber;
        this.episodeNumber = v.episodeNumber;
        this.releaseYear = v.releaseYear;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getSeriesTitle() { return seriesTitle; }
    public String getType() { return type; }
    public Integer getSeasonNumber() { return seasonNumber; }
    public Integer getEpisodeNumber() { return episodeNumber; }
    public Integer getReleaseYear() { return releaseYear; }
}
