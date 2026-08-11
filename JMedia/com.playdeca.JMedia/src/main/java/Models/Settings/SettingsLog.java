package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Column;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
public class SettingsLog extends PanacheEntity {

    @Column(name = "settings_id")
    public Long settingsId;

    @Lob
    private String message;

    public LocalDateTime timestamp;

    public SettingsLog() {
        this.timestamp = LocalDateTime.now();
    }
}
