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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VideoImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoImportService.class);

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

    private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors() - 1);
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly) {
        loggingService.addLog("Starting video scan of directory: " + directory);
        mediaPreProcessor.clearCache();
        List<PendingMedia> discoveredMedia = new ArrayList<>();
        try {
            Set<String> existingPaths = loadExistingMediaPaths();
            loggingService.addLog("Loaded " + existingPaths.size() + " existing media records");
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;
            
            ExecutorCompletionService<PendingMedia> completion = new ExecutorCompletionService<>(executor);
            AtomicInteger submittedTasks = new AtomicInteger();
            
            loggingService.addLog("Scanning filesystem...");
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isVideoFile)
                        .forEach(path -> {
                            submittedTasks.incrementAndGet();
                            completion.submit(() -> processVideoFile(path, rootPath, metadataOnly, existingPaths));
                        });
            }
            
            int totalFiles = submittedTasks.get();
            loggingService.addLog("Found " + totalFiles + " video files. Starting discovery phase...");
            
            for (int i = 0; i < totalFiles; i++) {
                try {
                    Future<PendingMedia> future = completion.take();
                    PendingMedia result = future.get();
                    if (result != null) {
                        discoveredMedia.add(result);
                    }
                    if ((i + 1) % 50 == 0) {
                        loggingService.addLog("Discovered " + (i + 1) + " / " + totalFiles + " files...");
                    }
                } catch (Exception e) {
                    LOGGER.error("Error during parallel discovery: " + e.getMessage());
                }
            }
            loggingService.addLog("Discovery phase completed. Found " + discoveredMedia.size() + " new or updated items.");
        } catch (IOException e) {
            loggingService.addLog("Error scanning directory: " + directory, e);
        }
        return discoveredMedia;
    }

    private Set<String> loadExistingMediaPaths() {
        List<String> paths = em.createQuery("select m.path from MediaFile m", String.class).getResultList();
        return new HashSet<>(paths);
    }

    @Transactional
    @ActivateRequestContext
    public PendingMedia scanSingleFile(Path filePath) {
        if (!Files.exists(filePath)) return null;
        if (!isVideoFile(filePath)) return null;
        
        String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path rootPath = libPathStr != null ? Paths.get(libPathStr) : filePath.getParent();
        Set<String> existingPaths = loadExistingMediaPaths();
        return processVideoFile(filePath, rootPath, false, existingPaths);
    }

    @Transactional
    public void reloadMetadata(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            loggingService.addLog("Video not found: " + videoId);
            return;
        }
        MediaFile mediaFile = MediaFile.find("path", video.path).firstResult();
        if (mediaFile == null) {
            loggingService.addLog("MediaFile missing for video: " + videoId);
            return;
        }
        Path path = Paths.get(mediaFile.path);
        if (!Files.exists(path)) {
            loggingService.addLog("File missing on disk: " + mediaFile.path);
            return;
        }
        mediaPreProcessor.createPendingMedia(mediaFile, path, path.getParent());
        loggingService.addLog("Queued metadata reload for: " + path.getFileName());
    }

    @Inject
    VideoStateService videoStateService;

    @Transactional
    public void resetVideoDatabase() {
        loggingService.addLog("Resetting video database...");
        try {
            videoStateService.resetState();
        } catch (Exception e) {
            loggingService.addLog("Warning: Could not reset video playback state: " + e.getMessage());
        }
        mediaPreProcessor.clearCache();
        VideoHistory.deleteAll();
        try {
            Models.VideoGenre.deleteAll();
        } catch (Exception e) {
            loggingService.addLog("Warning: Could not clear video genres: " + e.getMessage());
        }
        PendingMedia.deleteAll();
        try {
            Models.SubtitleTrack.deleteAll();
        } catch (Exception ignored) {}
        Video.deleteAll();
        MediaFile.deleteAll();
        loggingService.addLog("Video database reset completed");
    }

    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") ||
               fileName.endsWith(".mov") || fileName.endsWith(".wmv") || fileName.endsWith(".flv") ||
               fileName.endsWith(".webm") || fileName.endsWith(".m4v") || fileName.endsWith(".srt");
    }

    @Transactional
    @ActivateRequestContext
    protected PendingMedia processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, Set<String> existingPaths) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        try {
            if (existingPaths.contains(filePathStr)) {
                MediaFile existingFile = MediaFile.find("path", filePathStr).firstResult();
                if (existingFile != null) {
                    // For subtitles, we don't need a PendingMedia/Video entity
                    if (filename.toLowerCase().endsWith(".srt")) {
                        return null; 
                    }
                    
                    Video existingVideo = Video.find("path", filePathStr).firstResult();
                    if (existingVideo != null && !metadataOnly) {
                        return null;
                    }
                    PendingMedia pending = PendingMedia.findByMediaFile(existingFile);
                    if (pending == null) {
                        return mediaPreProcessor.createPendingMedia(existingFile, filePath, rootPath);
                    }
                    return pending;
                }
                return null;
            }
            if (!metadataOnly) {
                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = filename.toLowerCase().endsWith(".srt") ? "subtitle" : "video";
                mediaFile.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                mediaFile.size = Files.size(filePath);
                mediaFile.persist();
                
                // Only create pending media for videos
                if ("video".equals(mediaFile.type)) {
                    return mediaPreProcessor.createPendingMedia(mediaFile, filePath, rootPath);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing file {}: {}", filename, e.getMessage());
        }
        return null;
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
