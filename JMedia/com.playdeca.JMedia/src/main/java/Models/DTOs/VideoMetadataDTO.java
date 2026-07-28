package Models.DTOs;

import Models.Series;
import Models.Video;
import lombok.Data;
import java.util.ArrayList;
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
    public List<String> directors;
    public String originalLanguage;
    public Integer runtimeMins;
    public Boolean favorite;
    public Boolean watched;
    public Series series;
    public String contentType;

    public VideoMetadataDTO(Video video) {
        if (video == null) return;
        Series s = video.series;

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

        // Show-level fields: prefer Series; fallback to Video for standalone movies
        if (s != null) {
            this.logoPath = s.logoPath;
            this.heroPath = s.heroPath;
            this.backdropPath = s.backdropPath;
            this.posterPath = s.posterPath;
            this.originalLanguage = s.originalLanguage;
            this.runtimeMins = s.runtimeMins;
            this.imdbRating = s.imdbRating;
            this.tmdbRating = s.tmdbRating;
            this.releaseYear = s.releaseYear;

            // Text metadata: prefer Series, fallback to Video entity
            this.description = s.description != null ? s.description : video.description;
            this.tagline = s.tagline != null ? s.tagline : video.tagline;
            this.overview = s.overview != null && !s.overview.isBlank() ? s.overview : video.overview;
            this.genres = (s.genres != null && !s.genres.isEmpty())
                    ? new ArrayList<>(s.genres)
                    : (video.genres != null ? new ArrayList<>(video.genres) : null);
            this.networks = (s.networks != null && !s.networks.isEmpty())
                    ? new ArrayList<>(s.networks)
                    : (video.networks != null ? new ArrayList<>(video.networks) : null);
            this.directors = (s.directors != null && !s.directors.isEmpty())
                    ? new ArrayList<>(s.directors)
                    : (video.directors != null ? new ArrayList<>(video.directors) : null);
        } else {
            // Fallback for standalone movies (no series link) — read from Video entity directly
            this.logoPath = video.logoPath;
            this.heroPath = video.heroPath;
            this.description = video.description;
            this.tagline = video.tagline;
            this.overview = video.overview;
            this.genres = video.genres != null ? new ArrayList<>(video.genres) : null;
            this.backdropPath = video.backdropPath;
            this.posterPath = video.posterPath;
            this.networks = video.networks != null ? new ArrayList<>(video.networks) : null;
            this.directors = video.directors != null ? new ArrayList<>(video.directors) : null;
            this.originalLanguage = video.originalLanguage;
            this.runtimeMins = video.runtimeMins;
            this.imdbRating = video.imdbRating;
            this.tmdbRating = video.tmdbRating;
            this.releaseYear = video.releaseYear;
        }

        // Episode-level fields (always from Video)
        this.seriesTitle = video.seriesTitle;
        this.seasonNumber = video.seasonNumber;
        this.episodeNumber = video.episodeNumber;
        this.episodeTitle = video.episodeTitle;
        this.thumbnailPath = video.thumbnailPath;
        this.favorite = video.favorite;
        this.watched = video.watched;
        this.contentType = video.contentType;
        // resumeTime will be set by API layer using VideoState
    }

}
