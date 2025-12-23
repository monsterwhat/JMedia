package Models;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.time.LocalDateTime;

@Entity
public class PendingMedia extends PanacheEntity {

    @ManyToOne
    public MediaFile mediaFile;
    
    public String originalFilename;
    public String originalPath;
    
    // Raw detection results (before smart processing)
    public String rawShowName;
    public String rawTitle;
    public Integer rawSeason;
    public Integer rawEpisode;
    public Integer rawYear;
    public String rawMediaType; // "episode" or "movie"
    
    // Smart processing results
    public String detectedShowName;
    public String detectedTitle;
    public Integer detectedSeason;
    public Integer detectedEpisode;
    public Integer detectedYear;
    public String detectedMediaType; // "episode" or "movie"
    public Double confidenceScore;
    
    // Processing status
    public ProcessingStatus status;
    public LocalDateTime createdAt;
    public LocalDateTime processedAt;
    public String errorMessage;
    
    // User corrections
    public String correctedShowName;
    public String correctedTitle;
    public Integer correctedSeason;
    public Integer correctedEpisode;
    public Integer correctedYear;
    public String correctedMediaType;
    public Boolean userApproved;
    public LocalDateTime userApprovedAt;
    
    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        USER_CORRECTION_NEEDED,
        USER_APPROVED
    }
    
    public PendingMedia() {
        this.status = ProcessingStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.confidenceScore = 0.0;
        this.userApproved = false;
    }
    
    /**
     * Gets the final show name to use, prioritizing user corrections
     */
    public String getFinalShowName() {
        if (correctedShowName != null && !correctedShowName.trim().isEmpty()) {
            return correctedShowName;
        }
        if (detectedShowName != null && !detectedShowName.trim().isEmpty()) {
            return detectedShowName;
        }
        return rawShowName;
    }
    
    /**
     * Gets the final title to use, prioritizing user corrections
     */
    public String getFinalTitle() {
        if (correctedTitle != null && !correctedTitle.trim().isEmpty()) {
            return correctedTitle;
        }
        if (detectedTitle != null && !detectedTitle.trim().isEmpty()) {
            return detectedTitle;
        }
        return rawTitle;
    }
    
    /**
     * Gets the final media type to use, prioritizing user corrections
     */
    public String getFinalMediaType() {
        if (correctedMediaType != null && !correctedMediaType.trim().isEmpty()) {
            return correctedMediaType;
        }
        if (detectedMediaType != null && !detectedMediaType.trim().isEmpty()) {
            return detectedMediaType;
        }
        return rawMediaType;
    }
    
    /**
     * Gets the final season number, prioritizing user corrections
     */
    public Integer getFinalSeason() {
        if (correctedSeason != null) {
            return correctedSeason;
        }
        if (detectedSeason != null) {
            return detectedSeason;
        }
        return rawSeason;
    }
    
    /**
     * Gets the final episode number, prioritizing user corrections
     */
    public Integer getFinalEpisode() {
        if (correctedEpisode != null) {
            return correctedEpisode;
        }
        if (detectedEpisode != null) {
            return detectedEpisode;
        }
        return rawEpisode;
    }
    
    /**
     * Gets the final release year, prioritizing user corrections
     */
    public Integer getFinalYear() {
        if (correctedYear != null) {
            return correctedYear;
        }
        if (detectedYear != null) {
            return detectedYear;
        }
        return rawYear;
    }
    
    /**
     * Checks if this pending media needs user attention
     */
    public boolean needsUserAttention() {
        return status == ProcessingStatus.USER_CORRECTION_NEEDED ||
               (status == ProcessingStatus.COMPLETED && confidenceScore < 0.7);
    }
    
    /**
     * Finds pending media that needs processing
     */
    public static java.util.List<PendingMedia> findPendingProcessing() {
        return list("status", ProcessingStatus.PENDING);
    }
    
    /**
     * Finds pending media that needs user attention
     */
    public static java.util.List<PendingMedia> findNeedingUserAttention() {
        return list("status = ?1 OR (status = ?2 AND confidenceScore < ?3)", 
                   ProcessingStatus.USER_CORRECTION_NEEDED, 
                   ProcessingStatus.COMPLETED, 
                   0.7);
    }
    
    /**
     * Finds pending media by media file
     */
    public static PendingMedia findByMediaFile(MediaFile mediaFile) {
        return find("mediaFile", mediaFile).firstResult();
    }
}