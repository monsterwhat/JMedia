package Services;

import Models.MediaFile;
import Models.PendingMedia;
import Models.PendingMedia.ProcessingStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MediaPreProcessor {

    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    private static final Pattern EPISODE_PATTERN_SXXEXX = 
        Pattern.compile("(?i)(.*?)[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})(.*)");
    private static final Pattern EPISODE_PATTERN_XXY = 
        Pattern.compile("(?i)(.*?)(\\d{1,2})[x×](\\d{1,3})(.*)");
    private static final Pattern YEAR_PATTERN = 
        Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern QUALITY_PATTERN = 
        Pattern.compile("(?i)\\b(720p|1080p|2160p|4k|nf|webrip|x264|x265|hevc|galaxytg|galaxyty|hdtv|bluray)\\b");
    private static final Pattern SEASON_FOLDER_PATTERN = 
        Pattern.compile("(?i)season[s]?[-_.]?\\d{1,3}");
    
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
            return null;
        }
        processingCache.put(relativePath, true);
        
        // Check if pending media already exists
        PendingMedia existing = PendingMedia.findByMediaFile(mediaFile);
        if (existing != null) {
            return existing;
        }
        
        PendingMedia pendingMedia = new PendingMedia();
        pendingMedia.mediaFile = mediaFile;
        pendingMedia.originalFilename = filename;
        pendingMedia.originalPath = relativePath;
        
        // Perform basic, raw detection first
        extractRawDetectionData(pendingMedia, filename, videoPath);
        
        pendingMedia.persist();
        return pendingMedia;
    }
    
    /**
     * Extracts raw detection data using simple patterns (Phase 1)
     */
    private void extractRawDetectionData(PendingMedia pendingMedia, String filename, Path videoPath) {
        Pattern[] episodePatterns = {EPISODE_PATTERN_SXXEXX, EPISODE_PATTERN_XXY};
        
        boolean isEpisode = false;
        for (Pattern pattern : episodePatterns) {
            Matcher matcher = pattern.matcher(filename);
            if (matcher.matches()) {
                pendingMedia.rawMediaType = "episode";
                pendingMedia.rawSeason = Integer.parseInt(matcher.group(2));
                pendingMedia.rawEpisode = Integer.parseInt(matcher.group(3));
                String rawHint = matcher.group(4).trim();
                if (!rawHint.isEmpty()) {
                    String cleanedHint = QUALITY_PATTERN.matcher(rawHint).replaceAll("")
                                               .replaceAll("[._\\-\\[\\]\\(\\)]+", " ")
                                               .trim();
                    pendingMedia.rawTitle = cleanedHint.isEmpty() ? null : cleanedHint;
                } else {
                    pendingMedia.rawTitle = null;
                }
                pendingMedia.rawShowName = inferBasicShowName(videoPath);
                isEpisode = true;
                break;
            }
        }
        
        // If no episode pattern in filename, check path for strong indicators
        if (!isEpisode) {
            String fullPath = videoPath.toString().toLowerCase();
            if (fullPath.contains("tv shows") || fullPath.contains("tvseries") || 
                fullPath.contains("season") || fullPath.contains("series")) {
                pendingMedia.rawMediaType = "episode";
                pendingMedia.rawShowName = inferBasicShowName(videoPath);
                pendingMedia.rawTitle = extractTitleFromFilename(filename);
                isEpisode = true;
            }
        }
        
        if (!isEpisode) {
            // Assume movie
            pendingMedia.rawMediaType = "movie";
            pendingMedia.rawYear = extractYearFromFilename(filename);
            
            // If the parent folder looks like a movie title (contains a year), use it
            String parentFolderName = videoPath.getParent() != null ? videoPath.getParent().getFileName().toString() : "";
            if (parentFolderName.matches("(?i).*\\(\\d{4}\\).*")) {
                pendingMedia.rawTitle = extractTitleFromFilename(parentFolderName);
                if (pendingMedia.rawYear == null) {
                    pendingMedia.rawYear = extractYearFromFilename(parentFolderName);
                }
            } else {
                pendingMedia.rawTitle = extractTitleFromFilename(filename);
            }
        }
    }
    
    private String inferBasicShowName(Path videoPath) {
        Path parent = videoPath.getParent();
        if (parent == null) return "Unknown Show";

        String showNameCandidate = parent.getFileName().toString();
        
        // If parent is a season folder, go up one level
        if (showNameCandidate.matches("(?i)season[s]?[-_.]?\\d{1,3}")) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                showNameCandidate = grandParent.getFileName().toString();
            }
        }
        
        return showNameCandidate.replaceAll("(?i)\\b(?:s\\d{1,2}|season\\s*\\d{1,2})\\b", "").trim();
    }
    
    private String extractTitleFromFilename(String filename) {
        String title = filename.replaceFirst("\\.[^.]+$", ""); // Remove extension
        title = title.replaceAll("\\b(19|20)\\d{2}\\b", ""); // Remove year
        title = title.replaceAll("(?i)\\b(720p|1080p|4k|2160p|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|x264|x265|hevc)\\b", "");
        return title.replaceAll("[._\\-\\[\\]\\(\\)]+", " ").replaceAll("\\s+", " ").trim();
    }
    
    private Integer extractYearFromFilename(String filename) {
        Matcher matcher = YEAR_PATTERN.matcher(filename);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    @Transactional
    public void processPendingMedia() {
        processPendingMedia(false);
    }
    
    @Transactional
    public void processPendingMedia(boolean parallel) {
        List<PendingMedia> pendingList = PendingMedia.findPendingProcessing();
        
        if (pendingList.isEmpty()) {
            loggingService.addLog("MediaProcessor: No pending media to process.");
            return;
        }
        
        loggingService.addLog("MediaProcessor: Processing " + pendingList.size() + " pending items (" 
            + (parallel ? "parallel" : "sequential") + ")...");
        
        if (parallel && pendingList.size() > 1) {
            processPendingMediaParallel(pendingList);
        } else {
            processPendingMediaSequential(pendingList);
        }
    }
    
    private void processPendingMediaSequential(List<PendingMedia> pendingList) {
        int processedCount = 0;
        
        for (PendingMedia pending : pendingList) {
            try {
                processSinglePendingMedia(pending);
                processedCount++;
            } catch (Exception e) {
                loggingService.addLog("MediaProcessor: Error processing " + pending.originalFilename + ": " + e.getMessage());
            }
        }
        
        loggingService.addLog("MediaProcessor: Smart processing completed for " + processedCount + " items.");
    }
    
    private void processPendingMediaParallel(List<PendingMedia> pendingList) {
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
        final int[] processedCount = {0};
        final int[] errorCount = {0};
        
        for (PendingMedia pending : pendingList) {
            completion.submit(() -> {
                try {
                    processSinglePendingMedia(pending);
                    synchronized (processedCount) {
                        processedCount[0]++;
                    }
                } catch (Exception e) {
                    synchronized (errorCount) {
                        errorCount[0]++;
                    }
                    loggingService.addLog("MediaProcessor: Error processing " + pending.originalFilename + ": " + e.getMessage());
                }
                return null;
            });
        }
        
        for (int i = 0; i < pendingList.size(); i++) {
            try {
                completion.take().get();
            } catch (Exception e) {
                loggingService.addLog("MediaProcessor: Error waiting for task: " + e.getMessage());
            }
        }
        
        loggingService.addLog("MediaProcessor: Parallel processing completed for " + processedCount[0] 
            + " items (" + errorCount[0] + " errors).");
    }
    
    @Transactional
    public void processSinglePendingMedia(PendingMedia pending) {
        pending.status = ProcessingStatus.PROCESSING;
        pending.persist();
        
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
        
        pending.detectedMediaType = result.mediaType;
        pending.detectedShowName = result.showName;
        pending.detectedTitle = result.title;
        pending.detectedSeason = result.season;
        pending.detectedEpisode = result.episode;
        pending.detectedYear = result.year;
        pending.confidenceScore = result.confidence;
        
        if (result.confidence >= 0.5) {
            pending.status = ProcessingStatus.COMPLETED; 
        } else {
            pending.status = ProcessingStatus.USER_CORRECTION_NEEDED;
        }
        
        pending.processedAt = java.time.LocalDateTime.now();
        pending.persist();
    }
    
    public ProcessingStats getProcessingStats() {
        ProcessingStats stats = new ProcessingStats();
        stats.totalPending = PendingMedia.count("status", ProcessingStatus.PENDING);
        stats.totalProcessing = PendingMedia.count("status", ProcessingStatus.PROCESSING);
        stats.totalCompleted = PendingMedia.count("status", ProcessingStatus.COMPLETED);
        stats.totalFailed = PendingMedia.count("status", ProcessingStatus.FAILED);
        stats.totalUserCorrectionNeeded = PendingMedia.count("status", ProcessingStatus.USER_CORRECTION_NEEDED);
        stats.totalUserApproved = PendingMedia.count("status", ProcessingStatus.USER_APPROVED);
        return stats;
    }
    
    public void clearCache() {
        processingCache.clear();
    }
    
    /**
     * Process multiple pending media items in parallel by ID
     * @param pendingIds List of PendingMedia IDs to process
     * @param parallel If true, use parallel processing; if false, sequential
     * @return List of processed PendingMedia
     */
    public List<PendingMedia> processPendingMediaBatch(List<Long> pendingIds, boolean parallel) {
        if (pendingIds == null || pendingIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        loggingService.addLog("MediaProcessor: Processing batch of " + pendingIds.size() + " items (" 
            + (parallel ? "parallel" : "sequential") + ")...");
        
        if (parallel && pendingIds.size() > 1) {
            return processPendingMediaBatchParallel(pendingIds);
        } else {
            return processPendingMediaBatchSequential(pendingIds);
        }
    }
    
    private List<PendingMedia> processPendingMediaBatchSequential(List<Long> pendingIds) {
        List<PendingMedia> results = new ArrayList<>();
        int processedCount = 0;
        
        for (Long id : pendingIds) {
            try {
                PendingMedia pending = PendingMedia.findById(id);
                if (pending != null) {
                    processSinglePendingMedia(pending);
                    results.add(pending);
                    processedCount++;
                }
            } catch (Exception e) {
                loggingService.addLog("MediaProcessor: Error processing pending media " + id + ": " + e.getMessage());
            }
        }
        
        loggingService.addLog("MediaProcessor: Batch sequential processing completed for " + processedCount + " items.");
        return results;
    }
    
    private List<PendingMedia> processPendingMediaBatchParallel(List<Long> pendingIds) {
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
        List<PendingMedia> results = Collections.synchronizedList(new ArrayList<>());
        final int[] processedCount = {0};
        final int[] errorCount = {0};
        
        for (Long id : pendingIds) {
            completion.submit(() -> {
                try {
                    PendingMedia pending = PendingMedia.findById(id);
                    if (pending != null) {
                        processSinglePendingMedia(pending);
                        results.add(pending);
                        synchronized (processedCount) {
                            processedCount[0]++;
                        }
                    }
                } catch (Exception e) {
                    synchronized (errorCount) {
                        errorCount[0]++;
                    }
                    loggingService.addLog("MediaProcessor: Error processing pending media " + id + ": " + e.getMessage());
                }
                return null;
            });
        }
        
        for (int i = 0; i < pendingIds.size(); i++) {
            try {
                completion.take().get();
            } catch (Exception e) {
                loggingService.addLog("MediaProcessor: Error waiting for task: " + e.getMessage());
            }
        }
        
        loggingService.addLog("MediaProcessor: Batch parallel processing completed for " + processedCount[0] 
            + " items (" + errorCount[0] + " errors).");
        return results;
    }
    
    @PreDestroy
    public void shutdownExecutor() {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public static class ProcessingStats {
        public long totalPending;
        public long totalProcessing;
        public long totalCompleted;
        public long totalFailed;
        public long totalUserCorrectionNeeded;
        public long totalUserApproved;
    }
}
