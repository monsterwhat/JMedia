package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

@Entity
public class VideoSuggestion extends PanacheEntity {

    @Column(name = "profile_id")
    public Long profileId;

    public String content;

    public LocalDateTime createdAt;

    public VideoSuggestion() {
    }

    public VideoSuggestion(Long profileId, String content) {
        this.profileId = profileId;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }
}