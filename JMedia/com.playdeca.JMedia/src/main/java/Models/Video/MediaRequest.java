package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "media_request", indexes = {
    @Index(name = "idx_media_request_token_status", columnList = "token,status"),
    @Index(name = "idx_media_request_created_at", columnList = "createdAt")
})
public class MediaRequest extends PanacheEntity {

    @Column(nullable = false, length = 64)
    public String token;

    @Column(nullable = false, length = 16)
    public String kind;

    @Column(nullable = false, length = 300)
    public String title;

    // "year" is a reserved word in H2 and breaks the generated SELECT; keep the
    // Java field name but map it to a safe column name.
    @Column(name = "release_year")
    public Integer year;

    @Column(length = 512)
    public String posterUrl;

    @Column(length = 32)
    public String mediaType;

    public Integer tmdbId;

    @Column(length = 128)
    public String requestedBy;

    @Column(nullable = false)
    public Instant createdAt;

    /** PENDING or HANDLED */
    @Column(nullable = false, length = 16)
    public String status;
}
