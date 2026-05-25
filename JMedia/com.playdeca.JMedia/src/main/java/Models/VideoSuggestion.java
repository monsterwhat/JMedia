package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
public class VideoSuggestion extends PanacheEntity {

    @ManyToOne
    public Profile profile;

    public String content;

    public LocalDateTime createdAt;

    public VideoSuggestion() {
    }

    public VideoSuggestion(Profile profile, String content) {
        this.profile = profile;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }
}