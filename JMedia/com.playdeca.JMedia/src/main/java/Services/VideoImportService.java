package Services;

import Models.MediaFile;
import Models.Video;
import Models.PendingMedia;
import Models.VideoHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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

    @Inject
    SettingsService settingsService;

    @Inject
    EntityManager em;

    private static final int THREADS = Math.max(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    
    @Transactional
    public void scan(Path directory, boolean metadataOnly) {
        loggingService.addLog("Starting video scan of directory: " + directory);
        
        try {
            // Get root path from settings for relativization
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;

            // First collect all video files
            List<Path> videoFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isVideoFile)
                     .forEach(videoFiles::add);
            }
            
            loggingService.addLog("Found " + videoFiles.size() + " video files. Starting discovery phase...");
            
            if (videoFiles.isEmpty()) {
                loggingService.addLog("No video files found to process");
                return;
            }
            
            // Process files in parallel
            ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
            videoFiles.forEach(path -> completion.submit(() -> processVideoFile(path, rootPath, metadataOnly)));
            
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
                        if (result.startsWith("DISCOVERED:")) {
                            totalAdded++;
                        } else if (result.startsWith("SKIPPED:")) {
                            totalSkipped++;
                        } else if (result.startsWith("FAILED:")) {
                            totalFailed++;
                        }
                    }
                    
                    if ((i + 1) % 50 == 0) {
                        loggingService.addLog("Discovered " + (i + 1) + " / " + videoFiles.size() + " files...");
                    }
                } catch (Exception e) {
                    batchLogs.add("FAILED: Error during parallel discovery: " + e.getMessage());
                    totalFailed++;
                    totalProcessed++;
                }
            }
            
            loggingService.addLogs(batchLogs);
            loggingService.addLog("Discovery phase completed. Total: " + totalProcessed + 
                               ", New: " + totalAdded + ", Skipped: " + totalSkipped + ", Failed: " + totalFailed);
            
        } catch (IOException e) {
            loggingService.addLog("Error scanning directory: " + directory, e);
        }
    }
    
    @Inject
    VideoStateService videoStateService; 
    
    @Transactional
    public void resetVideoDatabase() {
        loggingService.addLog("Resetting video database...");
        
        // Reset playback state first
        try {
            videoStateService.resetState();
        } catch (Exception e) {
            loggingService.addLog("Warning: Could not reset video playback state: " + e.getMessage());
        }

        // Delete in correct order to respect foreign keys
        PendingMedia.deleteAll();
        VideoHistory.deleteAll();
        
        // Clear genres junction table (if using VideoGenre entity)
        try {
            em.createNativeQuery("DELETE FROM video_genres").executeUpdate();
        } catch (Exception e) {
            // Table might not exist or be named differently
        }

        // Subtitles are cascade deleted with Video, but let's be safe
        try {
            Models.SubtitleTrack.deleteAll();
        } catch (Exception e) {}

        Video.deleteAll();
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
    protected String processVideoFile(Path filePath, Path rootPath, boolean metadataOnly) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        
        try {
            // Check if MediaFile already exists
            MediaFile existingFile = MediaFile.find("path", filePathStr).firstResult();
            
            if (existingFile != null) {
                // If it exists, check if it has a Video entity associated
                Video existingVideo = Video.find("path", filePathStr).firstResult();
                if (existingVideo != null && !metadataOnly) {
                    return "SKIPPED: Already in library: " + filename;
                }
                
                // Ensure PendingMedia exists for existing files that might need metadata update
                if (PendingMedia.findByMediaFile(existingFile) == null) {
                    mediaPreProcessor.createPendingMedia(existingFile, filePath, rootPath);
                    return "DISCOVERED: Created missing PendingMedia for existing file: " + filename;
                }
                
                return "SKIPPED: Already pending: " + filename;
            }
            
            if (!metadataOnly) {
                // Create new MediaFile
                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = "video";
                
                try {
                    mediaFile.lastModified = java.nio.file.Files.getLastModifiedTime(filePath).toMillis();
                    mediaFile.size = java.nio.file.Files.size(filePath);
                } catch (IOException e) {
                    return "FAILED: Attributes read error for " + filename;
                }
                
                mediaFile.persist();
                
                // Create PendingMedia for smart processing (Phase 2)
                mediaPreProcessor.createPendingMedia(mediaFile, filePath, rootPath);
                
                return "DISCOVERED: New file found: " + filename;
            }
            
            return "SKIPPED: Metadata only mode: " + filename;
            
        } catch (Exception e) {
            return "FAILED: " + filename + ": " + e.getMessage();
        }
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
}
