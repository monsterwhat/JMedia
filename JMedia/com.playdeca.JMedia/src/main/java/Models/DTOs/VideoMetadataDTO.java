package Models.DTOs;

import Models.Video;
import lombok.Data;
import java.util.List;

@Data
public class VideoMetadataDTO {
    public Long id;
    public String title;
    public String type;
    public Double introStart;
    public Double introEnd;
    public Double outroStart;
    public Double outroEnd;
    public Double recapStart;
    public Double recapEnd;
    public Long duration;
    public Long fileSize; // bytes
    public Double resumeTime; // Per-profile resume time (seconds), set by API
    public String logoPath;
    public String heroPath;
    public String description;
    public String tagline;
    public String overview;
    public List<String> genres;
    public Double imdbRating;
    public Double tmdbRating;
    public Integer releaseYear;
    public String seriesTitle;
    public Integer seasonNumber;
    public Integer episodeNumber;
    public String episodeTitle;
    public String backdropPath;
    public String posterPath;
    public String thumbnailPath;
    public List<String> networks;
    public String originalLanguage;
    public Integer runtimeMins;
    public Boolean favorite;
    public Boolean watched;

    public VideoMetadataDTO(Video video) {
        if (video == null) return;
        this.id = video.id;
        this.title = video.title;
        this.type = video.type;
        this.introStart = video.introStart;
        this.introEnd = video.introEnd;
        this.outroStart = video.outroStart;
        this.outroEnd = video.outroEnd;
        this.recapStart = video.recapStart;
        this.recapEnd = video.recapEnd;
        this.duration = video.duration;
        this.fileSize = video.size != null && video.size > 0 ? video.size : video.fileSize;
        this.logoPath = video.logoPath;
        this.heroPath = video.heroPath;
        this.description = video.description;
        this.tagline = video.tagline;
        this.overview = video.overview;
        this.genres = video.genres;
        this.imdbRating = video.imdbRating;
        this.tmdbRating = video.tmdbRating;
        this.releaseYear = video.releaseYear;
        this.seriesTitle = video.seriesTitle;
        this.seasonNumber = video.seasonNumber;
        this.episodeNumber = video.episodeNumber;
        this.episodeTitle = video.episodeTitle;
        this.backdropPath = video.backdropPath;
        this.posterPath = video.posterPath;
        this.thumbnailPath = video.thumbnailPath;
        this.networks = video.networks;
        this.originalLanguage = video.originalLanguage;
        this.runtimeMins = video.runtimeMins;
        this.favorite = video.favorite;
        this.watched = video.watched;
        // resumeTime will be set by API layer using VideoState
    }
}
