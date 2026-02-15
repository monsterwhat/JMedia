package Services;

import Models.MediaFile;
import Models.PendingMedia;
import Models.PendingMedia.ProcessingStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MediaPreProcessor {

    @Inject
    LoggingService loggingService;
    
    @Inject
    SmartNamingService smartNamingService;
    
    // Cache to avoid processing the same file multiple times
    private final Map<String, Boolean> processingCache = new ConcurrentHashMap<>();
    
    /**
     * Creates a pending media record for basic processing without making final decisions
     */
    @Transactional
    public PendingMedia createPendingMedia(MediaFile mediaFile, Path videoPath, Path rootPath) {
        String filename = videoPath.getFileName().toString();
        String relativePath = rootPath.relativize(videoPath).toString();
        
        // Check if already processed
        if (processingCache.containsKey(relativePath)) {
            loggingService.addLog("File already being processed: " + relativePath);
            return null;
        }
        processingCache.put(relativePath, true);
        
        // Check if pending media already exists
        PendingMedia existing = PendingMedia.findByMediaFile(mediaFile);
        if (existing != null) {
            loggingService.addLog("PendingMedia already exists for: " + relativePath);
            return existing;
        }
        
        PendingMedia pendingMedia = new PendingMedia();
        pendingMedia.mediaFile = mediaFile;
        pendingMedia.originalFilename = filename;
        pendingMedia.originalPath = relativePath;
        
        // Perform basic, raw detection first
        extractRawDetectionData(pendingMedia, filename, videoPath);
        
        pendingMedia.persist();
        loggingService.addLog("Created PendingMedia for: " + filename + " (Status: " + pendingMedia.status + ")");
        
        return pendingMedia;
    }
    
    /**
     * Extracts raw detection data using simple patterns (Phase 1)
     */
    private void extractRawDetectionData(PendingMedia pendingMedia, String filename, Path videoPath) {
        // Simple episode detection with regex
        Pattern episodePattern = Pattern.compile("(?i)(.*?)[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})(.*)");
        Matcher episodeMatcher = episodePattern.matcher(filename);
        
        if (episodeMatcher.matches()) {
            // Episode detected
            pendingMedia.rawMediaType = "episode";
            pendingMedia.rawSeason = Integer.parseInt(episodeMatcher.group(2));
            pendingMedia.rawEpisode = Integer.parseInt(episodeMatcher.group(3));
            pendingMedia.rawTitle = episodeMatcher.group(4).trim().isEmpty() ? null : episodeMatcher.group(4).trim();
            pendingMedia.rawShowName = inferBasicShowName(videoPath);
        } else {
            // Assume movie
            pendingMedia.rawMediaType = "movie";
            pendingMedia.rawTitle = extractTitleFromFilename(filename);
            pendingMedia.rawYear = extractYearFromFilename(filename);
            pendingMedia.rawShowName = null; // Movies don't have show names
        }
    }
    
    /**
     * Basic show name inference from folder structure (existing logic)
     */
    private String inferBasicShowName(Path videoPath) {
        Path parent = videoPath.getParent();
        if (parent == null) return "Unknown Show";

        String showNameCandidate = parent.getFileName().toString();
        
        // If parent is a season folder, go up one more level
        if (showNameCandidate.matches("(?i)season[s]?[-_.]?\\d{1,3}")) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                showNameCandidate = grandParent.getFileName().toString();
            }
        }
        
        // Basic cleanup (simplified version of existing logic)
        String cleanedShowName = showNameCandidate
                .replaceAll("(?i)\\b(?:s\\d{1,3}|season(?:s)?\\d{1,3})\\b", "")
                .replaceAll("(?i)\\b(?:\\d{3,4}p|\\d{3,4}i)\\b", "")
                .replaceAll("[._-]+", " ")
                .trim();
        
        return cleanedShowName.isEmpty() ? "Unknown Show" : cleanedShowName;
    }
    
    /**
     * Extracts title from filename (removes extensions and common patterns)
     */
    private String extractTitleFromFilename(String filename) {
        // Remove extension
        String title = filename.replaceFirst("\\.[^.]+$", "");
        
        // Remove year patterns
        title = title.replaceAll("\\b(19|20)\\d{2}\\b", "");
        
        // Remove quality indicators
        title = title.replaceAll("(?i)\\b(720p|1080p|4k|bluray|bdrip|dvdrip|web-dl|webrip)\\b", "");
        
        // Remove common separators and clean up
        title = title.replaceAll("[._\\-\\[\\]\\(\\)]+", " ").trim();
        
        return title.isEmpty() ? "Unknown Title" : title;
    }
    
    /**
     * Extracts year from filename
     */
    private Integer extractYearFromFilename(String filename) {
        Pattern yearPattern = Pattern.compile("\\b(19|20)\\d{2}\\b");
        Matcher matcher = yearPattern.matcher(filename);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Processes all pending media with smart detection (Phase 2)
     */
    @Transactional
    public void processPendingMedia() {
        List<PendingMedia> pendingList = PendingMedia.findPendingProcessing();
        loggingService.addLog("MediaProcessor: Starting smart processing for " + pendingList.size() + " pending media items");
        
        int processedCount = 0;
        int successCount = 0;
        int retryCount = 0;
        int failedCount = 0;
        
        for (PendingMedia pending : pendingList) {
            try {
                boolean success = processSinglePendingMediaWithRetry(pending);
                processedCount++;
                if (success) {
                    successCount++;
                } else {
                    failedCount++;
                }
                
                // Log progress every 50 items (like music scanning)
                if (processedCount % 50 == 0) {
                    loggingService.addLog("MediaProcessor: Processed " + processedCount + " / " + pendingList.size() + " pending media items (Success: " + successCount + ", Failed: " + failedCount + ")...");
                }
                
            } catch (Exception e) {
                loggingService.addLog("MediaProcessor: Critical error processing pending media " + pending.originalFilename + " (ID: " + pending.id + "): " + e.getMessage(), e);
                failedCount++;
                processedCount++;
            }
        }
        
        loggingService.addLog("MediaProcessor: Smart processing completed. Total: " + processedCount + 
                           ", Success: " + successCount + ", Failed: " + failedCount + ", Retries attempted: " + retryCount);
    }
    
    /**
     * Process a single pending media item with retry logic (max 2 retries, skip on 3rd failure)
     */ 
    private boolean processSinglePendingMediaWithRetry(PendingMedia pending) {
        int retryCount = 0;
        boolean success = false;
        
        while (retryCount <= 2 && !success) {
            try {
                processSinglePendingMedia(pending);
                success = true;
                loggingService.addLog("MediaProcessor: Successfully processed: " + pending.originalFilename + 
                                   " (type: " + pending.detectedMediaType + ", confidence: " + String.format("%.2f", pending.confidenceScore) + ")");
                
            } catch (Exception e) {
                retryCount++;
                if (retryCount <= 2) {
                    loggingService.addLog("MediaProcessor: Retry " + (retryCount + 1) + "/3 for: " + pending.originalFilename + " (exception: " + e.toString() + ")", e);
                } else {
                    loggingService.addLog("MediaProcessor: Skipping after 3 failures: " + pending.originalFilename + " (final exception: " + e.toString() + ")", e);
                    
                    // Mark as failed after 3 attempts
                    pending.status = ProcessingStatus.FAILED;
                    pending.errorMessage = "Failed after 3 attempts: " + e.getMessage();
                    pending.processedAt = java.time.LocalDateTime.now();
                    pending.persist();
                }
            }
        }
        
        return success;
    }
    
    /**
     * Processes a single pending media item with smart detection
     */
    @Transactional
    public void processSinglePendingMedia(PendingMedia pending) {
        loggingService.addLog("MediaProcessor: Processing pending media: " + pending.originalFilename + " (" + pending.id + ")");
        
        pending.status = ProcessingStatus.PROCESSING;
        pending.persist();
        
        // Use smart naming service for advanced detection
        SmartNamingService.NamingResult result = smartNamingService.detectSmartNames(
            pending.mediaFile,
            pending.originalFilename,
            pending.originalPath,
            pending.rawMediaType,
            pending.rawShowName,
            pending.rawTitle,
            pending.rawSeason,
            pending.rawEpisode,
            pending.rawYear
        );
        
        // Apply smart detection results
        pending.detectedMediaType = result.mediaType;
        pending.detectedShowName = result.showName;
        pending.detectedTitle = result.title;
        pending.detectedSeason = result.season;
        pending.detectedEpisode = result.episode;
        pending.detectedYear = result.year;
        pending.confidenceScore = result.confidence;
        
        // Determine final status
        if (result.confidence >= 0.8) {
            pending.status = ProcessingStatus.COMPLETED;
        } else if (result.confidence >= 0.5) { // Changed from 0.6 to 0.5 to align with entity creation threshold
            pending.status = ProcessingStatus.COMPLETED; 
        } else {
            pending.status = ProcessingStatus.USER_CORRECTION_NEEDED;
        }
        
        pending.processedAt = java.time.LocalDateTime.now();
        pending.persist();
        
        if ("episode".equalsIgnoreCase(result.mediaType)) {
            loggingService.addLog("MediaProcessor: Detected Episode: " + pending.originalFilename + 
                               " S" + String.format("%02d", result.season) + "E" + String.format("%02d", result.episode) + 
                               " (confidence: " + String.format("%.2f", result.confidence) + ")");
        } else if ("movie".equalsIgnoreCase(result.mediaType)) {
            loggingService.addLog("MediaProcessor: Detected Movie: " + pending.originalFilename + 
                               " (confidence: " + String.format("%.2f", result.confidence) + ")");
        } else {
            loggingService.addLog("MediaProcessor: Smart processing completed for " + pending.originalFilename + 
                               ": " + pending.status + " (confidence: " + String.format("%.2f", result.confidence) + ")");
        }
    }
    
    /**
     * Gets statistics about pending media processing
     */
    public ProcessingStats getProcessingStats() {
        ProcessingStats stats = new ProcessingStats();
        
        stats.totalPending = PendingMedia.count("status", ProcessingStatus.PENDING);
        stats.totalProcessing = PendingMedia.count("status", ProcessingStatus.PROCESSING);
        stats.totalCompleted = PendingMedia.count("status", ProcessingStatus.COMPLETED);
        stats.totalFailed = PendingMedia.count("status", ProcessingStatus.FAILED);
        stats.totalUserCorrectionNeeded = PendingMedia.count("status", ProcessingStatus.USER_CORRECTION_NEEDED);
        stats.totalUserApproved = PendingMedia.count("status", ProcessingStatus.USER_APPROVED);
        
        // Count items needing attention
        stats.needingAttention = PendingMedia.findNeedingUserAttention().size();
        
        return stats;
    }
    
    /**
     * Clears the processing cache (useful for testing)
     */
    public void clearCache() {
        processingCache.clear();
    }
    
    /**
     * Statistics class for processing status
     */
    public static class ProcessingStats {
        public long totalPending;
        public long totalProcessing;
        public long totalCompleted;
        public long totalFailed;
        public long totalUserCorrectionNeeded;
        public long totalUserApproved;
        public long needingAttention;
        
        @Override
        public String toString() {
            return String.format(
                "Processing Stats - Pending: %d, Processing: %d, Completed: %d, Failed: %d, User Correction Needed: %d, User Approved: %d, Needing Attention: %d",
                totalPending, totalProcessing, totalCompleted, totalFailed, 
                totalUserCorrectionNeeded, totalUserApproved, needingAttention
            );
        }
    }
}