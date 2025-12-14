package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class MediaFile extends PanacheEntity {

    public String path;
    public long size;
    public long lastModified;
    public String type; // "video" or "subtitle"
    
    public int durationSeconds;
    public int width;
    public int height;
}
