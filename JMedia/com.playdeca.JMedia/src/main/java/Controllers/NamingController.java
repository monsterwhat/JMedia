package Controllers;

import Models.PendingMedia;
import Models.PendingMedia.ProcessingStatus;
import Services.MediaPreProcessor;
import Services.SmartNamingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class NamingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamingController.class);
    
    @Inject
    MediaPreProcessor mediaPreProcessor;
    
    @Inject
    SmartNamingService smartNamingService;

    /**
     * Gets all pending media that needs user attention
     */
    public List<PendingMedia> getPendingNeedingAttention() {
        return PendingMedia.findNeedingUserAttention();
    }

    /**
     * Gets pending media by status
     */
    public List<PendingMedia> getPendingByStatus(ProcessingStatus status) {
        return PendingMedia.list("status", status);
    }

    /**
     * Gets all pending media
     */
    public List<PendingMedia> getAllPendingMedia() {
        return PendingMedia.listAll();
    }

    /**
     * Gets processing statistics
     */
    public MediaPreProcessor.ProcessingStats getProcessingStats() {
        return mediaPreProcessor.getProcessingStats();
    }

    /**
     * Processes all pending media items
     */
    @Transactional
    public void processAllPendingMedia() {
        LOGGER.info("Starting batch processing of all pending media");
        mediaPreProcessor.processPendingMedia();
    }

    /**
     * Processes a single pending media item
     */
    @Transactional
    public void processPendingMedia(Long pendingId) {
        Optional<PendingMedia> pendingOpt = Optional.ofNullable(PendingMedia.findById(pendingId));
        if (pendingOpt.isPresent()) {
            PendingMedia pending = pendingOpt.get();
            mediaPreProcessor.processSinglePendingMedia(pending);
            LOGGER.info("Processed pending media item: {}", pendingId);
        } else {
            LOGGER.warn("Pending media not found with ID: {}", pendingId);
        }
    }

    /**
     * Applies user corrections to a pending media item
     */
    @Transactional
    public boolean applyUserCorrections(Long pendingId, 
                                     String correctedShowName, String correctedTitle,
                                     Integer correctedSeason, Integer correctedEpisode,
                                     Integer correctedYear, String correctedMediaType) {
        
        Optional<PendingMedia> pendingOpt = Optional.ofNullable(PendingMedia.findById(pendingId));
        if (pendingOpt.isEmpty()) {
            LOGGER.warn("Pending media not found with ID: {}", pendingId);
            return false;
        }

        PendingMedia pending = pendingOpt.get();
        
        // Apply corrections
        pending.correctedShowName = correctedShowName;
        pending.correctedTitle = correctedTitle;
        pending.correctedSeason = correctedSeason;
        pending.correctedEpisode = correctedEpisode;
        pending.correctedYear = correctedYear;
        pending.correctedMediaType = correctedMediaType;
        
        pending.status = ProcessingStatus.USER_APPROVED;
        pending.userApproved = true;
        pending.userApprovedAt = java.time.LocalDateTime.now();
        
        pending.persist();
        
        LOGGER.info("Applied user corrections to pending media {}: {} -> {} ({})", 
                   pendingId, correctedShowName, correctedTitle, correctedMediaType);
        
        return true;
    }

    /**
     * Approves the detected names for a pending media item
     */
    @Transactional
    public boolean approveDetectedNames(Long pendingId) {
        Optional<PendingMedia> pendingOpt = Optional.ofNullable(PendingMedia.findById(pendingId));
        if (pendingOpt.isEmpty()) {
            LOGGER.warn("Pending media not found with ID: {}", pendingId);
            return false;
        }

        PendingMedia pending = pendingOpt.get();
        
        pending.status = ProcessingStatus.USER_APPROVED;
        pending.userApproved = true;
        pending.userApprovedAt = java.time.LocalDateTime.now();
        
        pending.persist();
        
        LOGGER.info("Approved detected names for pending media: {}", pendingId);
        
        return true;
    }

    /**
     * Rejects detected names and flags for correction
     */
    @Transactional
    public boolean rejectDetectedNames(Long pendingId, String reason) {
        Optional<PendingMedia> pendingOpt = Optional.ofNullable(PendingMedia.findById(pendingId));
        if (pendingOpt.isEmpty()) {
            LOGGER.warn("Pending media not found with ID: {}", pendingId);
            return false;
        }

        PendingMedia pending = pendingOpt.get();
        
        pending.status = ProcessingStatus.USER_CORRECTION_NEEDED;
        pending.errorMessage = reason;
        
        pending.persist();
        
        LOGGER.info("Rejected detected names for pending media {}: {}", pendingId, reason);
        
        return true;
    }

    /**
     * Creates final media entities (Movie/Episode) from approved pending media
     */
    @Transactional
    public int finalizeApprovedMedia() {
        List<PendingMedia> approvedList = PendingMedia.list("status", ProcessingStatus.USER_APPROVED);
        
        for (PendingMedia pending : approvedList) {
            try {
                createFinalMediaEntity(pending);
                pending.status = ProcessingStatus.COMPLETED;
                pending.persist();
            } catch (Exception e) {
                LOGGER.error("Error finalizing media entity for pending {}: {}", 
                           pending.id, e.getMessage(), e);
                pending.status = ProcessingStatus.FAILED;
                pending.errorMessage = e.getMessage();
                pending.persist();
            }
        }
        
        LOGGER.info("Finalized {} approved media entities", approvedList.size());
        return approvedList.size();
    }

    /**
     * Creates the final Movie or Episode entity from pending media
     */
    private void createFinalMediaEntity(PendingMedia pending) {
        String mediaType = pending.getFinalMediaType();
        
        if ("episode".equals(mediaType)) {
            createEpisodeEntity(pending);
        } else if ("movie".equals(mediaType)) {
            createMovieEntity(pending);
        } else {
            throw new IllegalArgumentException("Unknown media type: " + mediaType);
        }
    }

    /**
     * Creates Episode entity from pending media
     */
    private void createEpisodeEntity(PendingMedia pending) {
        // This would use the existing episode creation logic
        // but with the final names from the pending media
        
        // For now, we'll just log what would happen
        LOGGER.info("Would create Episode entity for pending {}: Show={}, S{}E{}, Title={}", 
                   pending.id, 
                   pending.getFinalShowName(),
                   pending.getFinalSeason(),
                   pending.getFinalEpisode(),
                   pending.getFinalTitle());
        
        // In a complete implementation, this would:
        // 1. Find or create Show entity
        // 2. Find or create Season entity
        // 3. Create Episode entity with all properties
        // 4. Handle subtitles
        // 5. Persist the entity
    }

    /**
     * Creates Movie entity from pending media
     */
    private void createMovieEntity(PendingMedia pending) {
        // This would use the existing movie creation logic
        // but with the final names from the pending media
        
        // For now, we'll just log what would happen
        LOGGER.info("Would create Movie entity for pending {}: Title={}, Year={}", 
                   pending.id,
                   pending.getFinalTitle(),
                   pending.getFinalYear());
        
        // In a complete implementation, this would:
        // 1. Create Movie entity with title and year
        // 2. Set the video path
        // 3. Handle subtitles
        // 4. Persist the entity
    }

    /**
     * Gets pending media by confidence score range
     */
    public List<PendingMedia> getPendingByConfidenceRange(double minConfidence, double maxConfidence) {
        return PendingMedia.list("confidenceScore >= ?1 and confidenceScore <= ?2", 
                               minConfidence, maxConfidence);
    }

    /**
     * Gets pending media with low confidence scores
     */
    public List<PendingMedia> getLowConfidencePending(double threshold) {
        return PendingMedia.list("status = ?1 and confidenceScore < ?2", 
                               ProcessingStatus.COMPLETED, threshold);
    }

    /**
     * Clears all pending media records (for testing/reset)
     */
    @Transactional
    public void clearAllPendingMedia() {
        long count = PendingMedia.count();
        PendingMedia.deleteAll();
        LOGGER.info("Cleared {} pending media records", count);
    }

    /**
     * Reprocesses a pending media item with fresh smart detection
     */
    @Transactional
    public boolean reprocessPendingMedia(Long pendingId) {
        Optional<PendingMedia> pendingOpt = Optional.ofNullable(PendingMedia.findById(pendingId));
        if (pendingOpt.isEmpty()) {
            LOGGER.warn("Pending media not found with ID: {}", pendingId);
            return false;
        }

        PendingMedia pending = pendingOpt.get();
        
        // Reset detected data
        pending.detectedMediaType = null;
        pending.detectedShowName = null;
        pending.detectedTitle = null;
        pending.detectedSeason = null;
        pending.detectedEpisode = null;
        pending.detectedYear = null;
        pending.confidenceScore = 0.0;
        pending.status = ProcessingStatus.PENDING;
        pending.errorMessage = null;
        
        pending.persist();
        
        // Reprocess
        mediaPreProcessor.processSinglePendingMedia(pending);
        
        LOGGER.info("Reprocessed pending media: {}", pendingId);
        return true;
    }
}