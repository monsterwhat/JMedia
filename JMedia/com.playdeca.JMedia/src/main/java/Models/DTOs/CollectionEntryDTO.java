package Models.DTOs;

import Models.Video.CollectionEntry;

public class CollectionEntryDTO {
    public Long id;
    public Long videoId;
    public Long externalVideoId;
    public int orderIndex;
    public String title;
    public String notes;
    public Double watchProgress;
    public boolean watched;
    public Double imdbRating;
    public Double tmdbRating;
    public Integer releaseYear;

    public CollectionEntryDTO() {}

    public CollectionEntryDTO(CollectionEntry e) {
        this.id = e.id;
        this.orderIndex = e.orderIndex;
        this.notes = e.notes;
        if (e.video != null) {
            this.videoId = e.video.id;
            this.title = e.video.title;
            this.imdbRating = e.video.imdbRating;
            this.tmdbRating = e.video.tmdbRating;
            this.releaseYear = e.video.releaseYear;
        } else if (e.externalVideo != null) {
            this.externalVideoId = e.externalVideo.id;
            this.title = e.externalVideo.title;
            this.watchProgress = e.externalVideo.watchProgress;
            this.watched = this.watchProgress != null && this.watchProgress >= 0.95;
        } else if (e.series != null) {
            this.title = e.series.title;
            this.imdbRating = e.series.imdbRating;
            this.tmdbRating = e.series.tmdbRating;
            this.releaseYear = e.series.releaseYear;
        }
    }
}
