package Models.DTOs;

import Models.Video;

public class SimpleEpisodeDTO {
    public Long id;
    public String filename;
    public String title;
    public String episodeTitle;

    public SimpleEpisodeDTO(Video v) {
        this.id = v.id;
        this.filename = v.filename;
        this.title = v.title;
        this.episodeTitle = v.episodeTitle;
    }
}
