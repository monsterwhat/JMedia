package Services;

import Models.MediaFile;
import Models.Video;
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

    @Inject
    SmartNamingService smartNamingService;

    @Inject
    LoggingService loggingService;

    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    @Inject
    SettingsService settingsService;

    @Inject
    MediaAnalysisService mediaAnalysisService;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    @Inject
    VideoStateService videoStateService;

    public static class ScanContext {
        public final Map<String, MediaFile> mediaFileByPath = new HashMap<>();
        public final Map<String, Video> videoByPath = new HashMap<>();
        public final Map<String, Long> lastModifiedByPath = new HashMap<>();
        public final Map<String, Video> videoByHash = new HashMap<>();
        public Instant lastScanTime;
    }

    public interface ScanProgressCallback {
        void onFileDiscovered(Video video, int index, int total);
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

    @Transactional
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
        
        ctx.lastScanTime = Instant.now();
        return ctx;
    }

    @ActivateRequestContext
    public List<Video> scan(Path directory, boolean metadataOnly) {
        return scan(directory, metadataOnly, null, false);
    }

    @ActivateRequestContext
    public List<Video> scan(Path directory, boolean metadataOnly, boolean forceFullScan) {
        return scan(directory, metadataOnly, null, forceFullScan);
    }

    @ActivateRequestContext
    public List<Video> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback) {
        return scan(directory, metadataOnly, callback, false);
    }

    @ActivateRequestContext
    public List<Video> scan(Path directory, boolean metadataOnly, ScanProgressCallback callback, boolean forceFullScan) {
        String scanType = forceFullScan ? "full" : "incremental";
        
        ScanState previousScan = getInterruptedScan();
        Set<String> processedPaths = new HashSet<>();
        boolean isResuming = false;
        
        if (previousScan != null && !forceFullScan) {
            loggingService.addLog("Found interrupted scan from previous session. Will resume from where it left off.");
            completeScanState(previousScan, "interrupted", "App restarted mid-scan", 0);
            
            if (previousScan.processedPaths != null) {
                processedPaths.addAll(previousScan.processedPaths);
                loggingService.addLog("Resuming: will skip " + processedPaths.size() + " already-processed files");
                isResuming = true;
            }
        }
        
        loggingService.addLog("Starting video scan of directory: " + directory + " (" + scanType + ")");
        List<Video> discoveredMedia = new ArrayList<>();
        
        ScanState scanState = null;
        try {
            ScanContext ctx = loadScanContext();
            loggingService.addLog("Loaded " + ctx.mediaFileByPath.size() + " existing media records, " 
                + ctx.videoByPath.size() + " videos");
            
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;
            
            isScanRunning = true;
            currentScanProgress.set(0);
            
            ExecutorCompletionService<Video> completion = new ExecutorCompletionService<>(managedExecutor);
            AtomicInteger submittedTasks = new AtomicInteger();
            AtomicInteger skippedFiles = new AtomicInteger();
            AtomicInteger processedCount = new AtomicInteger();
            
            loggingService.addLog("Scanning filesystem...");
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> videoFiles = paths.filter(Files::isRegularFile)
                        .filter(this::isVideoFile)
                        .toList();
                
                currentScanTotal.set(videoFiles.size());
                scanState = startScanState(directory.toString(), scanType, videoFiles.size(), 10);
                
                if (isResuming && !processedPaths.isEmpty()) {
                    scanState.processedPaths.addAll(processedPaths);
                    updateScanState(scanState, processedPaths.size(), null);
                    currentScanProgress.set(processedPaths.size());
                }
                
                List<Path> filesToProcess = videoFiles;
                if (isResuming && !processedPaths.isEmpty()) {
                    final Set<String> finalProcessedPaths = processedPaths;
                    filesToProcess = videoFiles.stream()
                        .filter(p -> !finalProcessedPaths.contains(p.toString()))
                        .toList();
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
            
            try {
                for (int i = 0; i < totalFiles; i++) {
                    try {
                        Future<Video> future = completion.take();
                        Video result = future.get();
                        
                        currentScanProgress.incrementAndGet();
                        
                        if (result != null) {
                            discoveredMedia.add(result);
                            int processed = processedCount.incrementAndGet();
                            if (scanState != null) {
                                String processedPath = result.path;
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
                    completeScanState(scanState, "COMPLETED", null, processedCount.get());
                }
            }
            loggingService.addLog("Discovery phase completed. Found " + discoveredMedia.size() + " new or updated items.");
        } catch (IOException e) {
            loggingService.addLog("Error scanning directory: " + directory, e);
            if (scanState != null) {
                completeScanState(scanState, "failed", e.getMessage(), 0);
            }
        }
        return discoveredMedia;
    }

    @ActivateRequestContext
    public List<Video> scanAndCreate(Path directory) {
        return scanAndCreate(directory, false);
    }

    @ActivateRequestContext
    public List<Video> scanAndCreate(Path directory, boolean forceFullScan) {
        loggingService.addLog("Starting scan for directory: " + directory);
        return scan(directory, false, null, forceFullScan);
    }
    
    @ActivateRequestContext
    public List<Video> scanAndProcess(Path directory) {
        return scanAndCreate(directory, false);
    }
    
    @ActivateRequestContext
    public List<Video> scanAndProcess(Path directory, boolean forceFullScan) {
        return scanAndCreate(directory, forceFullScan);
    }

    @Transactional
    public Video scanSingleFile(Path filePath) {
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
        
        String relativePath = path.getFileName().toString();
        SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(mediaFile, path.getFileName().toString(), relativePath, null, null, null, null, null, null);
        entityCreationService.createVideoFromNamingResult(mediaFile, res);
        
        loggingService.addLog("Reloaded metadata for: " + path.getFileName());
    }

    @Transactional
    public void resetVideoDatabase() {
        loggingService.addLog("Resetting video database...");
        try {
            videoStateService.resetState();
        } catch (Exception e) {
            loggingService.addLog("Warning: Could not reset video playback state: " + e.getMessage());
        }
        VideoHistory.deleteAll();
        try {
            Models.VideoGenre.deleteAll();
        } catch (Exception e) {
            loggingService.addLog("Warning: Could not clear video genres: " + e.getMessage());
        }
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
    protected Video processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx) {
        return processVideoFile(filePath, rootPath, metadataOnly, ctx, false, null);
    }

    @Transactional(value = TxType.REQUIRES_NEW)
    @ActivateRequestContext
    protected Video processVideoFile(Path filePath, Path rootPath, boolean metadataOnly, ScanContext ctx, boolean forceFullScan, AtomicInteger skippedFiles) {
        String filePathStr = filePath.toString();
        String filename = filePath.getFileName().toString();
        
        MediaFile existingFile = ctx.mediaFileByPath.get(filePathStr);
        
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
                
                if (!fileChanged && existingVideo != null && !metadataOnly) {
                    if (skippedFiles != null) {
                        skippedFiles.incrementAndGet();
                    }
                    return existingVideo;
                }
                
                String relativePath = rootPath.relativize(filePath).toString();
                SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(existingFile, filename, relativePath, null, null, null, null, null, null);
                return entityCreationService.createVideoFromNamingResult(existingFile, res);
            }

            if (!metadataOnly) {
                if (filename.toLowerCase().endsWith(".srt")) return null;

                String currentHash = mediaAnalysisService.generateFingerprint(filePathStr);
                
                if (currentHash != null && ctx.videoByHash.containsKey(currentHash)) {
                    Video movedVideo = ctx.videoByHash.get(currentHash);
                    movedVideo.path = filePathStr;
                    movedVideo.filename = filename;
                    movedVideo.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                    movedVideo.persist();
                    
                    MediaFile mf = MediaFile.find("mediaHash", currentHash).firstResult();
                    if (mf != null) {
                        mf.path = filePathStr;
                        mf.lastModified = movedVideo.lastModified;
                        mf.persist();
                    }
                    
                    if (skippedFiles != null) {
                        skippedFiles.incrementAndGet();
                    }
                    return movedVideo;
                }

                MediaFile mediaFile = new MediaFile();
                mediaFile.path = filePathStr;
                mediaFile.type = "video";
                mediaFile.lastModified = Files.getLastModifiedTime(filePath).toMillis();
                mediaFile.size = Files.size(filePath);
                mediaFile.mediaHash = currentHash;
                
                mediaAnalysisService.analyze(mediaFile);
                mediaFile.persist();
                
                ctx.mediaFileByPath.put(filePathStr, mediaFile);
                ctx.lastModifiedByPath.put(filePathStr, mediaFile.lastModified);
                
                String relativePath = rootPath.relativize(filePath).toString();
                SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(mediaFile, filename, relativePath, null, null, null, null, null, null);
                return entityCreationService.createVideoFromNamingResult(mediaFile, res);
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
        ScanState managed = ScanState.findById(state.id);
        if (managed != null) {
            managed.processedFiles = processedFiles;
            if (processedPath != null && !processedPath.isEmpty()) {
                managed.processedPaths.add(processedPath);
            }
            managed.persist();
        }
    }
    
    @Transactional(TxType.REQUIRES_NEW)
    public void completeScanState(ScanState state, String status, String errorMessage, int processedFiles) {
        if (state == null) return;
        ScanState managed = ScanState.findById(state.id);
        if (managed != null) {
            managed.status = status;
            managed.endTime = LocalDateTime.now();
            managed.errorMessage = errorMessage;
            if (processedFiles > 0) {
                managed.processedFiles = processedFiles;
            }
            managed.persist();
        }
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
