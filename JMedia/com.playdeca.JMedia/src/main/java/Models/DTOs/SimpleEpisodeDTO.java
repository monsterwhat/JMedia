package Models.DTOs;

import Models.Video;

public class SimpleEpisodeDTO {
    public Long id;
    public String filename;
    public String title;
    public String episodeTitle;
    public Double introStart;
    public Double introEnd;
    public Double outroStart;
    public Double outroEnd;
    public Double recapStart;
    public Double recapEnd;

    public SimpleEpisodeDTO(Video v) {
        this.id = v.id;
        this.filename = v.filename;
        this.title = v.title;
        this.episodeTitle = v.episodeTitle;
        this.introStart = v.introStart;
        this.introEnd = v.introEnd;
        this.outroStart = v.outroStart;
        this.outroEnd = v.outroEnd;
        this.recapStart = v.recapStart;
        this.recapEnd = v.recapEnd;
    }
}
