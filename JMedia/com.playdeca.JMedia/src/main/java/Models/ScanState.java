package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class ScanState extends PanacheEntity {

    public String scanType; // "full" or "incremental"
    
    public String status; // "running", "completed", "failed", "interrupted"
    
    public LocalDateTime startTime;
    
    public LocalDateTime endTime;
    
    public int totalFiles;
    
    public int processedFiles;
    
    public int batchSize;
    
    public String libraryPath;
    
    @ElementCollection
    public List<String> processedPaths = new ArrayList<>();
    
    public String errorMessage;
    
    public static ScanState findLatest() {
        return find("ORDER BY startTime DESC").firstResult();
    }
    
    public static ScanState findRunning() {
        return find("status", "running").firstResult();
    }
    
    public double getProgressPercent() {
        if (totalFiles == 0) return 0;
        return (processedFiles * 100.0) / totalFiles;
    }
}
