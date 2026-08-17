package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "user_per_video_subtitle_preferences")
public class UserPerVideoSubtitlePreference extends PanacheEntity {

    @Column(name = "user_id")
    public Long userId;

    @Column(name = "video_id")
    public Long videoId;

    @Column(name = "track_id")
    public Long trackId;

    public static UserPerVideoSubtitlePreference findByUserAndVideo(Long userId, Long videoId) {
        return find("userId = ?1 and videoId = ?2", userId, videoId).firstResult();
    }
}