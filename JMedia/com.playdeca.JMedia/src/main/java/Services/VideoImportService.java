package Services;

import Models.MediaFile;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@ApplicationScoped
public class VideoImportService {
    
    @Inject
    LoggingService loggingService;
    
    @Inject
    MediaPreProcessor mediaPreProcessor;
    
    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    private static final int THREADS = Math.max(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    
    @Transactional
    public void scan(Path directory, boolean metadataOnly) {
        loggingService.addLog("Starting video scan of directory: " + directory);
        
        try {
            // First collect all video files (like music scanning does)
            List<Path> videoFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isVideoFile)
                     .forEach(videoFiles::add);
            }
            
            loggingService.addLog("Found " + videoFiles.size() + " video files in directory. Starting parallel processing...");
            
            if (videoFiles.isEmpty()) {
                loggingService.addLog("No video files found to process");
                return;
            }
            
            // Process files in parallel with progress tracking (like music scanning)
            ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
            videoFiles.forEach(path -> completion.submit(() -> processVideoFile(path, metadataOnly)));
            
            int totalProcessed = 0;
            int totalAdded = 0;
            int totalSkipped = 0;
            int totalFailed = 0;
            List<String> batchLogs = new ArrayList<>();
            
            for (int i = 0; i < videoFiles.size(); i++) {
                try {
                    Future<String> future = completion.take();
                    String result = future.get();
                    totalProcessed++;
                    
                    if (result != null) {
                        batchLogs.add(result);
                        if (result.startsWith("ADDED:")) {
                            totalAdded++;
                        } else if (result.startsWith("SKIPPED:")) {
                            totalSkipped++;
                        } else if (result.startsWith("FAILED:")) {
                            totalFailed++;
                        }
                    }
                    
                    // Progress reporting every 50 files (like music scanning)
                    if ((i + 1) % 50 == 0) {
                        loggingService.addLog("Processed " + (i + 1) + " / " + videoFiles.size() + " video files (Added: " + totalAdded + ", Skipped: " + totalSkipped + ", Failed: " + totalFailed + ")...");
                    }
                } catch (Exception e) {
                    batchLogs.add("FAILED: Error processing video file in parallel: " + e.getMessage());
                    totalFailed++;
                    totalProcessed++;
                }
            }
            
            // Add batch logs for detailed results
            loggingService.addLogs(batchLogs);
            
            loggingService.addLog("Video scan completed. Total processed: " + totalProcessed + 
                               ", Added: " + totalAdded + ", Skipped: " + totalSkipped + ", Failed: " + totalFailed);
            
        } catch (IOException e) {
            loggingService.addLog("Error scanning directory: " + directory, e);
        }
    }
    
    @Transactional
    public void resetVideoDatabase() {
        loggingService.addLog("Resetting video database...");
        
        // Delete all Video entities
        Video.deleteAll();
        
        // Delete all MediaFile entities  
        MediaFile.deleteAll();
        
        loggingService.addLog("Video database reset completed");
    }
    
    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mp4") || 
               fileName.endsWith(".mkv") || 
               fileName.endsWith(".avi") || 
               fileName.endsWith(".mov") || 
               fileName.endsWith(".wmv") || 
               fileName.endsWith(".flv") || 
               fileName.endsWith(".webm");
    }
    
    @Transactional
    @ActivateRequestContext
    protected String processVideoFile(Path filePath, boolean metadataOnly) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        
        try {
            // Check if already processed
            MediaFile existingFile = MediaFile.find("path", filePathStr).firstResult();
            if (existingFile != null) {
                if (!metadataOnly) {
                    return "SKIPPED: File already processed: " + filename;
                }
                // For metadata only, we could update existing MediaFile here if needed
                return "SKIPPED: Metadata only mode for existing file: " + filename;
            }
            
            if (!metadataOnly) {
                // Create new MediaFile
                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = "video";
                
                // Extract basic metadata using file properties
                try {
                    mediaFile.lastModified = java.nio.file.Files.getLastModifiedTime(filePath).toMillis();
                    mediaFile.size = java.nio.file.Files.size(filePath);
                } catch (IOException e) {
                    return "FAILED: Error reading file attributes for " + filename + ": " + e.getMessage();
                }
                
                mediaFile.persist();
                
                // Create Video entity
                try {
                    Video video = entityCreationService.createVideoFromMediaFile(mediaFile);
                    if (video != null) {
                        return "ADDED: Created video entity for: " + filename;
                    } else {
                        return "FAILED: Failed to create video entity (null result) for: " + filename;
                    }
                } catch (Exception e) {
                    return "FAILED: Error creating video entity for " + filename + ": " + e.getMessage();
                }
            }
            
            return "SKIPPED: Metadata only mode for: " + filename;
            
        } catch (Exception e) {
            return "FAILED: Critical error processing " + filename + ": " + e.getMessage();
        }
    }
    
    /**
     * Shutdown method to properly close the executor service
     */
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
}