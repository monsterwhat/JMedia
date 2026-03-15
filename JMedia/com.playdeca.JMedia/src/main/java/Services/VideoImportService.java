package Services;

import Models.MediaFile;
import Models.Video;
import Models.PendingMedia;
import Models.VideoHistory;
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
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
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

    public static class ScanContext {
        public final Map<String, MediaFile> mediaFileByPath = new HashMap<>();
        public final Map<String, Video> videoByPath = new HashMap<>();
        public final Map<Long, PendingMedia> pendingByMediaFileId = new HashMap<>();
        public final Map<String, Long> lastModifiedByPath = new HashMap<>();
        public Instant lastScanTime;
    }

    public interface ScanProgressCallback {
        void onFileDiscovered(PendingMedia media, int index, int total);
        void onProgress(int discovered, int total, String status);
    }

    @Inject
    LoggingService loggingService;

    @Inject
    MediaPreProcessor mediaPreProcessor;

    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    @Inject
    SettingsService settingsService;

    private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors() - 1);
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    public ScanContext loadScanContext() {
        ScanContext ctx = new ScanContext();
        
        List<MediaFile> allMediaFiles = MediaFile.listAll();
        for (MediaFile mf : allMediaFiles) {
            ctx.mediaFileByPath.put(mf.path, mf);
            ctx.lastModifiedByPath.put(mf.path, mf.lastModified);
        }
        
        List<Video> allVideos = Video.listAll();
        for (Video v : allVideos) {
            ctx.videoByPath.put(v.path, v);
        }
        
        List<PendingMedia> allPending = PendingMedia.listAll();
        for (PendingMedia pm : allPending) {
            if (pm.mediaFile != null) {
                ctx.pendingByMediaFileId.put(pm.mediaFile.id, pm);
            }
        }
        
        ctx.lastScanTime = Instant.now();
        return ctx;
    }

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly) {
        return scan(directory, metadataOnly, null, false);
    }

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, boolean forceFullScan) {
        return scan(directory, metadataOnly, null, forceFullScan);
    }

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback) {
        return scan(directory, metadataOnly, callback, false);
    }

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback, boolean forceFullScan) {
        loggingService.addLog("Starting video scan of directory: " + directory + (forceFullScan ? " (full scan)" : " (incremental)"));
        mediaPreProcessor.clearCache();
        List<PendingMedia> discoveredMedia = new ArrayList<>();
        try {
            ScanContext ctx = loadScanContext();
            loggingService.addLog("Loaded " + ctx.mediaFileByPath.size() + " existing media records, " 
                + ctx.videoByPath.size() + " videos, " + ctx.pendingByMediaFileId.size() + " pending");
            
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;
            
            ExecutorCompletionService<PendingMedia> completion = new ExecutorCompletionService<>(executor);
            AtomicInteger submittedTasks = new AtomicInteger();
            AtomicInteger skippedFiles = new AtomicInteger();
            
            loggingService.addLog("Scanning filesystem...");
            LOGGER.info("DEBUG: Starting filesystem walk on: {}", directory);
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> videoFiles = paths.filter(Files::isRegularFile)
                        .filter(this::isVideoFile)
                        .toList();
                LOGGER.info("DEBUG: Found {} video files in filesystem", videoFiles.size());
                loggingService.addLog("Found " + videoFiles.size() + " video files in filesystem");
                for (Path path : videoFiles) {
                    submittedTasks.incrementAndGet();
                    completion.submit(() -> processVideoFile(path, rootPath, metadataOnly, ctx, forceFullScan, skippedFiles));
                }
            }
            
            int totalFiles = submittedTasks.get();
            int skipped = skippedFiles.get();
            if (skipped > 0) {
                loggingService.addLog("Skipped " + skipped + " unchanged files (incremental scan)");
            }
            loggingService.addLog("Found " + totalFiles + " video files. Starting discovery phase...");
            
            for (int i = 0; i < totalFiles; i++) {
                try {
                    Future<PendingMedia> future = completion.take();
                    PendingMedia result = future.get();
                    if (result != null) {
                        discoveredMedia.add(result);
                        if (callback != null) {
                            callback.onFileDiscovered(result, i + 1, totalFiles);
                        }
                    }
                    if ((i + 1) % 50 == 0) {
                        String status = "Discovered " + (i + 1) + " / " + totalFiles + " files...";
                        loggingService.addLog(status);
                        if (callback != null) {
                            callback.onProgress(discoveredMedia.size(), totalFiles, status);
                        }
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

    @Transactional
    @ActivateRequestContext
    public PendingMedia scanSingleFile(Path filePath) {
        if (!Files.exists(filePath)) return null;
        if (!isVideoFile(filePath)) return null;
        
        String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path rootPath = libPathStr != null ? Paths.get(libPathStr) : filePath.getParent();
        ScanContext ctx = loadScanContext();
        return processVideoFile(filePath, rootPath, true, ctx);
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
    protected PendingMedia processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx) {
        return processVideoFile(filePath, rootPath, metadataOnly, ctx, false, null);
    }

    @Transactional
    @ActivateRequestContext
    protected PendingMedia processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx, boolean forceFullScan, AtomicInteger skippedFiles) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        
        MediaFile existingFile = ctx.mediaFileByPath.get(filePathStr);
        if (existingFile == null) {
            LOGGER.info("DEBUG: New file (not in DB): {}", filePathStr);
        } else {
            LOGGER.info("DEBUG: Existing file in DB: {}, hasVideo: {}, metadataOnly: {}", 
                filePathStr, ctx.videoByPath.containsKey(filePathStr), metadataOnly);
        }
        
        try {
            if (existingFile != null) {
                boolean fileChanged = true;
                if (!forceFullScan) {
                    Long dbLastModified = ctx.lastModifiedByPath.get(filePathStr);
                    if (dbLastModified != null) {
                        long fsLastModified = Files.getLastModifiedTime(filePath).toMillis();
                        if (dbLastModified == fsLastModified) {
                            fileChanged = false;
                        }
                    }
                }
                
                if (filename.toLowerCase().endsWith(".srt")) {
                    return null; 
                }
                
                Video existingVideo = ctx.videoByPath.get(filePathStr);
                
                // Skip if file unchanged AND already has a Video (fully processed)
                if (!fileChanged && existingVideo != null && !metadataOnly) {
                    if (skippedFiles != null) {
                        skippedFiles.incrementAndGet();
                    }
                    return null;
                }
                
                // Skip if file unchanged AND has pending media (already discovered)
                if (!fileChanged) {
                    Long mediaFileId = existingFile.id;
                    PendingMedia existingPending = ctx.pendingByMediaFileId.get(mediaFileId);
                    if (existingPending != null) {
                        if (skippedFiles != null) {
                            skippedFiles.incrementAndGet();
                        }
                        return null;
                    }
                }
                
                // File is new or changed - process it
                Long mediaFileId = existingFile.id;
                PendingMedia pending = ctx.pendingByMediaFileId.get(mediaFileId);
                if (pending == null) {
                    return mediaPreProcessor.createPendingMedia(existingFile, filePath, rootPath);
                }
                return pending;
            }
            if (!metadataOnly) {
                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = filename.toLowerCase().endsWith(".srt") ? "subtitle" : "video";
                mediaFile.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                mediaFile.size = Files.size(filePath);
                mediaFile.persist();
                
                ctx.mediaFileByPath.put(filePathStr, mediaFile);
                ctx.lastModifiedByPath.put(filePathStr, mediaFile.lastModified);
                
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
