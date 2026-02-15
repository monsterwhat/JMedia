package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(callSuper = false)
@Table(name = "video_genres", 
       indexes = {
           @Index(name = "idx_video_genre_video_id", columnList = "video_id"),
           @Index(name = "idx_video_genre_genre_id", columnList = "genre_id"),
           @Index(name = "idx_video_genre_relevance", columnList = "relevance"),
           @Index(name = "idx_video_genre_composite", columnList = "video_id, genre_id")
       })
public class VideoGenre extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    public Video video;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id")
    public Genre genre;
    
    public Double relevance = 1.0; // 0.0 to 1.0 for primary vs secondary genres
    public Integer orderIndex = 0; // Primary, secondary, etc.
    
    // Ensure uniqueness of video-genre combination
    @Column(name = "video_id", insertable = false, updatable = false)
    private Long videoIdField;
    
    @Column(name = "genre_id", insertable = false, updatable = false)  
    private Long genreIdField;
}