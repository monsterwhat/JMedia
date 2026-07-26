package Models.DTOs;

import Models.Video;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SyncVideoData {

    public Long videoId;
    public String title;
    public String seriesTitle;
    public String episodeTitle;
    public String type;
    public Integer seasonNumber;
    public Integer episodeNumber;
    public Integer releaseYear;
    public String description;
    public String tagline;
    public String overview;
    public Double imdbRating;
    public Double tmdbRating;
    public Integer metacriticRating;
    public Double userRating;
    public String mpaaRating;
    public List<String> genres;
    public List<String> directors;
    public List<String> writers;
    public List<String> cast;
    public String originalLanguage;
    public Integer runtimeMins;
    public String status;
    public String imdbId;
    public String tmdbId;
    public String heroPath;
    public String collectionName;
    public Boolean favorite;
    public LocalDateTime dateModified;

    public void applyTo(Video video) {
        if (title != null) video.title = title;
        if (seriesTitle != null) video.seriesTitle = seriesTitle;
        if (episodeTitle != null) video.episodeTitle = episodeTitle;
        if (type != null) video.type = type;
        if (seasonNumber != null) video.seasonNumber = seasonNumber;
        if (episodeNumber != null) video.episodeNumber = episodeNumber;
        if (releaseYear != null) video.releaseYear = releaseYear;
        if (description != null) video.description = description;
        if (tagline != null) video.tagline = tagline;
        if (overview != null) video.overview = overview;
        if (imdbRating != null) video.imdbRating = imdbRating;
        if (tmdbRating != null) video.tmdbRating = tmdbRating;
        if (metacriticRating != null) video.metacriticRating = metacriticRating;
        if (userRating != null) video.userRating = userRating;
        if (mpaaRating != null) video.mpaaRating = mpaaRating;
        if (genres != null) video.genres = new ArrayList<>(genres);
        if (directors != null) video.directors = new ArrayList<>(directors);
        if (writers != null) video.writers = new ArrayList<>(writers);
        if (cast != null) video.cast = new ArrayList<>(cast);
        if (originalLanguage != null) video.originalLanguage = originalLanguage;
        if (runtimeMins != null) video.runtimeMins = runtimeMins;
        if (status != null) video.status = status;
        if (imdbId != null) video.imdbId = imdbId;
        if (tmdbId != null) video.tmdbId = tmdbId;
        if (heroPath != null) video.heroPath = heroPath;
        if (collectionName != null) video.collectionName = collectionName;
        if (favorite != null) video.favorite = favorite;
        if (dateModified != null) video.dateModified = dateModified;
    }

    public static SyncVideoData fromVideo(Video video) {
        SyncVideoData data = new SyncVideoData();
        data.videoId = video.id;
        data.title = video.title;
        data.seriesTitle = video.seriesTitle;
        data.episodeTitle = video.episodeTitle;
        data.type = video.type;
        data.seasonNumber = video.seasonNumber;
        data.episodeNumber = video.episodeNumber;
        data.releaseYear = video.releaseYear;
        data.description = video.description;
        data.tagline = video.tagline;
        data.overview = video.overview;
        data.imdbRating = video.imdbRating;
        data.tmdbRating = video.tmdbRating;
        data.metacriticRating = video.metacriticRating;
        data.userRating = video.userRating;
        data.mpaaRating = video.mpaaRating;
        data.genres = video.genres != null ? new ArrayList<>(video.genres) : null;
        data.directors = video.directors != null ? new ArrayList<>(video.directors) : null;
        data.writers = video.writers != null ? new ArrayList<>(video.writers) : null;
        data.cast = video.cast != null ? new ArrayList<>(video.cast) : null;
        data.originalLanguage = video.originalLanguage;
        data.runtimeMins = video.runtimeMins;
        data.status = video.status;
        data.imdbId = video.imdbId;
        data.tmdbId = video.tmdbId;
        data.heroPath = video.heroPath;
        data.collectionName = video.collectionName;
        data.favorite = video.favorite;
        data.dateModified = video.dateModified;
        return data;
    }

}
