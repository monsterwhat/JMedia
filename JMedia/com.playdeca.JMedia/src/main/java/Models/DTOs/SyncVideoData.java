package Models.DTOs;

import Models.Series;
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
    public Double metacriticRating;
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
        // Episode-level fields always go to Video
        if (title != null) video.title = title;
        if (seriesTitle != null) video.seriesTitle = seriesTitle;
        if (episodeTitle != null) video.episodeTitle = episodeTitle;
        if (type != null) video.type = type;
        if (seasonNumber != null) video.seasonNumber = seasonNumber;
        if (episodeNumber != null) video.episodeNumber = episodeNumber;
        if (releaseYear != null) video.releaseYear = releaseYear;
        if (userRating != null) video.userRating = userRating;
        if (collectionName != null) video.collectionName = collectionName;
        if (favorite != null) video.favorite = favorite;
        if (dateModified != null) video.dateModified = dateModified;

        // Series-level fields: write to Series when present, Video otherwise
        Series s = video.series;
        if (description != null) {
            if (s != null) s.description = description;
            else video.description = description;
        }
        if (tagline != null) {
            if (s != null) s.tagline = tagline;
            else video.tagline = tagline;
        }
        if (overview != null) {
            if (s != null) s.overview = overview;
            else video.overview = overview;
        }
        if (imdbRating != null) {
            if (s != null) s.imdbRating = imdbRating;
            else video.imdbRating = imdbRating;
        }
        if (tmdbRating != null) {
            if (s != null) s.tmdbRating = tmdbRating;
            else video.tmdbRating = tmdbRating;
        }
        if (metacriticRating != null) {
            if (s != null) s.metacriticRating = metacriticRating;
            else video.metacriticRating = metacriticRating;
        }
        if (mpaaRating != null) {
            if (s != null) s.mpaaRating = mpaaRating;
            else video.mpaaRating = mpaaRating;
        }
        if (genres != null) {
            if (s != null) s.genres = new ArrayList<>(genres);
            else video.genres = new ArrayList<>(genres);
        }
        if (directors != null) {
            if (s != null) s.directors = new ArrayList<>(directors);
            else video.directors = new ArrayList<>(directors);
        }
        if (writers != null) {
            if (s != null) s.writers = new ArrayList<>(writers);
            else video.writers = new ArrayList<>(writers);
        }
        if (cast != null) {
            if (s != null) s.cast = new ArrayList<>(cast);
            else video.cast = new ArrayList<>(cast);
        }
        if (originalLanguage != null) {
            if (s != null) s.originalLanguage = originalLanguage;
            else video.originalLanguage = originalLanguage;
        }
        if (runtimeMins != null) {
            if (s != null) s.runtimeMins = runtimeMins;
            else video.runtimeMins = runtimeMins;
        }
        if (status != null) {
            if (s != null) s.status = status;
            else video.status = status;
        }
        if (imdbId != null) {
            if (s != null) s.imdbId = imdbId;
            else video.imdbId = imdbId;
        }
        if (tmdbId != null) {
            if (s != null) {
                try { s.tmdbId = Integer.parseInt(tmdbId); } catch (NumberFormatException ignored) {}
            }
            else video.tmdbId = tmdbId;
        }
        if (heroPath != null) {
            if (s != null) s.heroPath = heroPath;
            else video.heroPath = heroPath;
        }
    }

    public static SyncVideoData fromVideo(Video video) {
        SyncVideoData data = new SyncVideoData();
        Series s = video.series;

        // Episode-level fields from Video
        data.videoId = video.id;
        data.title = video.title;
        data.seriesTitle = video.seriesTitle;
        data.episodeTitle = video.episodeTitle;
        data.type = video.type;
        data.seasonNumber = video.seasonNumber;
        data.episodeNumber = video.episodeNumber;
        data.userRating = video.userRating;
        data.collectionName = video.collectionName;
        data.favorite = video.favorite;
        data.dateModified = video.dateModified;

        // Series-level fields: read from Series only
        if (s != null) {
            data.description = s.description;
            data.tagline = s.tagline;
            data.overview = s.overview;
            data.imdbRating = s.imdbRating;
            data.tmdbRating = s.tmdbRating;
            data.metacriticRating = s.metacriticRating;
            data.mpaaRating = s.mpaaRating;
            data.genres = s.genres != null ? new ArrayList<>(s.genres) : null;
            data.directors = s.directors != null ? new ArrayList<>(s.directors) : null;
            data.writers = s.writers != null ? new ArrayList<>(s.writers) : null;
            data.cast = s.cast != null ? new ArrayList<>(s.cast) : null;
            data.originalLanguage = s.originalLanguage;
            data.runtimeMins = s.runtimeMins;
            data.status = s.status;
            data.imdbId = s.imdbId;
            data.tmdbId = s.tmdbId != null ? String.valueOf(s.tmdbId) : null;
            data.heroPath = s.heroPath;
            data.releaseYear = s.releaseYear;
        }
        return data;
    }

}
