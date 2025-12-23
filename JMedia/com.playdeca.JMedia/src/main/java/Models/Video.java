package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
public class Video extends PanacheEntity {

    public String path;
    public String filename;
    public String title;
    public String seriesTitle;
    public String episodeTitle;
    public int seasonNumber;
    public int episodeNumber;
    public long duration;
    public long size;
    public long lastModified;
    public String resolution; // e.g., "1920x1080"
    public String format; // e.g., "mp4", "mkv"
    public String videoCodec;
    public String audioCodec;
    public String thumbnailPath;
    public boolean hasSubtitles;
    public String subtitlePath;
    public double fileSize;
    public String dateAdded;
    public String lastWatched;
    public double watchProgress;
    public boolean watched;
    public String type; // "movie" or "episode"
    public int releaseYear;
    public String genre;
    public String description;
    public String rating;
    public String imdbId;
    public String tmdbId;
}