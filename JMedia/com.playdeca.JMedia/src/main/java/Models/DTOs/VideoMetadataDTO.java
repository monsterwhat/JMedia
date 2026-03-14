package Models.DTOs;

import Models.Video;
import lombok.Data;

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
    public Long resumeTime;
    public Long duration;

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
        this.resumeTime = video.resumeTime;
        this.duration = video.duration;
    }
}
