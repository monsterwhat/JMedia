package Services;

import Models.MediaFile;
import Models.Video;
import Models.PendingMedia;
import Models.VideoHistory;
import Models.ScanState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
        public final Map<String, Video> videoByHash = new HashMap<>();
        public Instant lastScanTime;
    }

    public interface ScanProgressCallback {
        void onFileDiscovered(PendingMedia media, int index, int total);
        void onProgress(int discovered, int total, String status);
    }

    public static class ScanProgress {
        public int total;
        public int current;
        public String status;
        public boolean isRunning;

        public ScanProgress(int total, int current, String status, boolean isRunning) {
            this.total = total;
            this.current = current;
            this.status = status;
            this.isRunning = isRunning;
        }
    }

    private final AtomicInteger currentScanTotal = new AtomicInteger(0);
    private final AtomicInteger currentScanProgress = new AtomicInteger(0);
    private volatile boolean isScanRunning = false;

    public ScanProgress getProgress() {
        return new ScanProgress(
            currentScanTotal.get(),
            currentScanProgress.get(),
            isScanRunning ? "RUNNING" : "IDLE",
            isScanRunning
        );
    }

    @Inject
    LoggingService loggingService;

    @Inject
    MediaPreProcessor mediaPreProcessor;

    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    @Inject
    SettingsService settingsService;

    @Inject
    MediaAnalysisService mediaAnalysisService;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

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
            if (v.mediaHash != null) {
                ctx.videoByHash.put(v.mediaHash, v);
            }
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

    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly) {
        return scan(directory, metadataOnly, null, false);
    }

    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, boolean forceFullScan) {
        return scan(directory, metadataOnly, null, forceFullScan);
    }

    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback) {
        return scan(directory, metadataOnly, callback, false);
    }

    @Transactional
    @ActivateRequestContext
    public List<PendingMedia> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback, boolean forceFullScan) {
        String scanType = forceFullScan ? "full" : "incremental";
        
        // Check for interrupted scan from previous session
        ScanState previousScan = getInterruptedScan();
        Set<String> processedPaths = new HashSet<>();
        boolean isResuming = false;
        
        if (previousScan != null && !forceFullScan) {
            loggingService.addLog("Found interrupted scan from previous session. Will resume from where it left off.");
            completeScanState(previousScan, "interrupted", "App restarted mid-scan");
            
            // Load processed paths from previous scan to skip already-processed files
            if (previousScan.processedPaths != null) {
                processedPaths.addAll(previousScan.processedPaths);
                loggingService.addLog("Resuming: will skip " + processedPaths.size() + " already-processed files");
                isResuming = true;
            }
        }
        
        loggingService.addLog("Starting video scan of directory: " + directory + " (" + scanType + ")");
        mediaPreProcessor.clearCache();
        List<PendingMedia> discoveredMedia = new ArrayList<>();
        
        // Create scan state to track progress
        ScanState scanState = null;
        try {
            ScanContext ctx = loadScanContext();
            loggingService.addLog("Loaded " + ctx.mediaFileByPath.size() + " existing media records, " 
                + ctx.videoByPath.size() + " videos, " + ctx.pendingByMediaFileId.size() + " pending");
            
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;
            
            isScanRunning = true;
            currentScanProgress.set(0);
            
            ExecutorCompletionService<PendingMedia> completion = new ExecutorCompletionService<>(managedExecutor);
            AtomicInteger submittedTasks = new AtomicInteger();
            AtomicInteger skippedFiles = new AtomicInteger();
            AtomicInteger processedCount = new AtomicInteger();
            
            loggingService.addLog("Scanning filesystem...");
            LOGGER.info("DEBUG: Starting filesystem walk on: {}", directory);
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> videoFiles = paths.filter(Files::isRegularFile)
                        .filter(this::isVideoFile)
                        .toList();
                LOGGER.info("DEBUG: Found {} video files in filesystem", videoFiles.size());
                loggingService.addLog("Found " + videoFiles.size() + " video files in filesystem");
                
                // Update total
                currentScanTotal.set(videoFiles.size());
                
                // Create scan state - total is all files, we'll track actual progress
                scanState = startScanState(directory.toString(), scanType, videoFiles.size(), 10);
                
                // Initialize with already-processed paths if resuming
                if (isResuming && !processedPaths.isEmpty()) {
                    scanState.processedPaths.addAll(processedPaths);
                    scanState.persist();
                    currentScanProgress.set(processedPaths.size());
                }
                
                // Filter out already-processed files if resuming
                List<Path> filesToProcess = videoFiles;
                if (isResuming && !processedPaths.isEmpty()) {
                    final Set<String> finalProcessedPaths = processedPaths;
                    filesToProcess = videoFiles.stream()
                        .filter(p -> !finalProcessedPaths.contains(p.toString()))
                        .toList();
                    loggingService.addLog("Resuming: " + filesToProcess.size() + " new files to process (skipped " + processedPaths.size() + " already-processed)");
                }
                
                for (Path path : filesToProcess) {
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
            
            try {
                for (int i = 0; i < totalFiles; i++) {
                    try {
                        Future<PendingMedia> future = completion.take();
                        PendingMedia result = future.get();
                        
                        currentScanProgress.incrementAndGet();
                        
                        if (result != null) {
                            discoveredMedia.add(result);
                            
                            // Track processed paths
                            int processed = processedCount.incrementAndGet();
                            if (scanState != null) {
                                // Get path from result
                                String processedPath = result.mediaFile != null ? result.mediaFile.path : null;
                                if (processed % 10 == 0) {
                                    updateScanState(scanState, processed, processedPath);
                                }
                            }
                            
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
                        LOGGER.error("Error during parallel discovery: " + e.getMessage(), e);
                    }
                }
            } finally {
                isScanRunning = false;
                if (scanState != null) {
                    scanState.status = "COMPLETED";
                    scanState.endTime = LocalDateTime.now();
                    scanState.processedFiles = processedCount.get();
                    scanState.persist();
                }
            }
            loggingService.addLog("Discovery phase completed. Found " + discoveredMedia.size() + " new or updated items.");
            
            // Mark scan as completed
            LOGGER.info("DEBUG: Returning {} discovered items from scan", discoveredMedia.size());
        } catch (IOException e) {
            loggingService.addLog("Error scanning directory: " + directory, e);
            if (scanState != null) {
                completeScanState(scanState, "failed", e.getMessage());
            }
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
        ScanState.deleteAll();
        loggingService.addLog("Video database reset completed");
    }

    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") ||
               fileName.endsWith(".mov") || fileName.endsWith(".wmv") || fileName.endsWith(".flv") ||
               fileName.endsWith(".webm") || fileName.endsWith(".m4v") || fileName.endsWith(".srt");
    }

    @Transactional(value = TxType.REQUIRES_NEW)
    @ActivateRequestContext
    protected PendingMedia processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx) {
        return processVideoFile(filePath, rootPath, metadataOnly, ctx, false, null);
    }

    @Transactional(value = TxType.REQUIRES_NEW)
    @ActivateRequestContext
    protected PendingMedia processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx, boolean forceFullScan, AtomicInteger skippedFiles) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        
        MediaFile existingFile = ctx.mediaFileByPath.get(filePathStr);
        
        try {
            if (existingFile != null) {
                // ... (existing logic for unchanged files)
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

            // FILE IS NEW TO THIS PATH
            if (!metadataOnly) {
                if (filename.toLowerCase().endsWith(".srt")) return null;

                // 1. Generate fingerprint for the new file
                String currentHash = mediaAnalysisService.generateFingerprint(filePathStr);
                
                // 2. Check if we already have this file at a DIFFERENT path (Move detection)
                if (currentHash != null && ctx.videoByHash.containsKey(currentHash)) {
                    Video movedVideo = ctx.videoByHash.get(currentHash);
                    LOGGER.info("Detected MOVED video: {} -> {}", movedVideo.path, filePathStr);
                    
                    // Update the video entity with the new path
                    movedVideo.path = filePathStr;
                    movedVideo.filename = filename;
                    movedVideo.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                    movedVideo.persist();
                    
                    // Update MediaFile if it exists
                    MediaFile mf = MediaFile.find("mediaHash", currentHash).firstResult();
                    if (mf != null) {
                        mf.path = filePathStr;
                        mf.lastModified = movedVideo.lastModified;
                        mf.persist();
                    }
                    
                    if (skippedFiles != null) {
                        skippedFiles.incrementAndGet();
                    }
                    return null; // Skip further processing as it's already in DB
                }

                // 3. If not a move, create new record
                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = "video";
                mediaFile.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                mediaFile.size = Files.size(filePath);
                mediaFile.mediaHash = currentHash;
                
                // Technical analysis (Plex-style: analyze during scan)
                mediaAnalysisService.analyze(mediaFile);
                
                mediaFile.persist();
                
                ctx.mediaFileByPath.put(filePathStr, mediaFile);
                ctx.lastModifiedByPath.put(filePathStr, mediaFile.lastModified);
                
                PendingMedia pm = mediaPreProcessor.createPendingMedia(mediaFile, filePath, rootPath);
                return pm;
            }
        } catch (Exception e) {
            LOGGER.error("Error processing file {}: {}", filename, e.getMessage(), e);
        }
        return null;
    }

    @Transactional
    public ScanState startScanState(String libraryPath, String scanType, int totalFiles, int batchSize) {
        ScanState state = new ScanState();
        state.libraryPath = libraryPath;
        state.scanType = scanType;
        state.status = "running";
        state.startTime = LocalDateTime.now();
        state.totalFiles = totalFiles;
        state.processedFiles = 0;
        state.batchSize = batchSize;
        state.processedPaths = new ArrayList<>();
        state.persist();
        return state;
    }
    
    @Transactional(TxType.REQUIRES_NEW)
    public void updateScanState(ScanState state, int processedFiles, String processedPath) {
        if (state == null) return;
        state.processedFiles = processedFiles;
        if (processedPath != null && !processedPath.isEmpty()) {
            state.processedPaths.add(processedPath);
        }
        state.flush();
    }
    
    @Transactional(TxType.REQUIRES_NEW)
    public void completeScanState(ScanState state, String status, String errorMessage) {
        if (state == null) return;
        state.status = status;
        state.endTime = LocalDateTime.now();
        state.errorMessage = errorMessage;
        state.persist();
    }
    
    public ScanState getLastScanState() {
        return ScanState.findLatest();
    }
    
    public ScanState getInterruptedScan() {
        return ScanState.find("status", "running").firstResult();
    }
    
    public boolean isPathProcessed(String path) {
        ScanState state = getInterruptedScan();
        if (state == null || state.processedPaths == null) return false;
        return state.processedPaths.contains(path);
    }
}
