package Services;

import Models.Video.MediaFile;
import Models.Video.Video;
import Models.Video.VideoHistory;
import Models.Video.ScanState;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

@Startup
@ApplicationScoped
public class VideoImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoImportService.class);

    private static final Set<String> EXCLUDED_SCAN_DIRS = Set.of("hls", "mp4");

    @PersistenceContext(unitName = "video")
    private EntityManager em;

    @Inject
    SmartNamingService smartNamingService;

    @Inject
    UnifiedVideoEntityCreationService entityCreationService;

    @Inject
    SettingsService settingsService;

    @Inject
    MediaAnalysisService mediaAnalysisService;

    @Inject
    VideoScanExecutor videoScanExecutor;

    @Inject
    VideoStateService videoStateService;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    VideoStoryboardService videoStoryboardService;

    @Inject
    VideoMetadataService videoMetadataService;

    // TODO: Remove this method once stale column migration is complete
    private static final String[] STALE_COLUMNS = {"WATCHED", "WATCHPROGRESSDOUBLE"};
    private volatile boolean staleColumnsCleaned = false;

    // Featurette folder names — videos in these subfolders are featurettes, not episodes
    private static final Set<String> FEATURETTE_FOLDERS = Set.of(
        "featurette", "behind the scenes", "behind-the-scenes", "bts"
    );

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        cleanStaleColumns();
    }

    @Transactional
    void cleanStaleColumns() {
        if (staleColumnsCleaned) return;
        for (String column : STALE_COLUMNS) {
            try {
                em.createNativeQuery("ALTER TABLE video DROP COLUMN IF EXISTS " + column).executeUpdate();
                LOGGER.info("Dropped stale column from video table if it existed: " + column);
            } catch (Exception e) {
                LOGGER.warn("Could not drop column " + column + ": " + e.getMessage());
            }
        }
        staleColumnsCleaned = true;
    }

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
        if (videoScanExecutor.isDisabled()) {
            LOGGER.info("Video scanning is disabled in system settings (videoScanThreads). Scan aborted.");
            return new ArrayList<>();
        }
        String scanType = forceFullScan ? "full" : "incremental";
        
        ScanState previousScan = getInterruptedScan();
        Set<String> processedPaths = new HashSet<>();
        boolean isResuming = false;
        
        if (previousScan != null && !forceFullScan) {
            LOGGER.info("Found interrupted scan from previous session. Will resume from where it left off.");
            completeScanState(previousScan, "interrupted", "App restarted mid-scan", 0);
            
            if (previousScan.processedPaths != null) {
                processedPaths.addAll(previousScan.processedPaths);
                LOGGER.info("Resuming: will skip " + processedPaths.size() + " already-processed files");
                isResuming = true;
            }
        }
        
        LOGGER.info("Starting video scan of directory: " + directory + " (" + scanType + ")");
        List<Video> discoveredMedia = new ArrayList<>();
        
        ScanState scanState = null;
        try {
            ScanContext ctx = loadScanContext();
            LOGGER.info("Loaded " + ctx.mediaFileByPath.size() + " existing media records, " 
                + ctx.videoByPath.size() + " videos");
            
            String libPathStr = settingsService.getOrCreateSettings().getVideoLibraryPath();
            Path rootPath = libPathStr != null ? Paths.get(libPathStr) : directory;
            
            isScanRunning = true;
            currentScanProgress.set(0);
            
            ExecutorCompletionService<Video> completion = new ExecutorCompletionService<>(videoScanExecutor.getExecutor());
            AtomicInteger submittedTasks = new AtomicInteger();
            AtomicInteger skippedFiles = new AtomicInteger();
            AtomicInteger processedCount = new AtomicInteger();
            
            LOGGER.info("Scanning filesystem...");
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> videoFiles = paths.filter(Files::isRegularFile)
                        .filter(this::isVideoFile)
                        .filter(p -> {
                            for (int i = 0; i < p.getNameCount() - 1; i++) {
                                if (EXCLUDED_SCAN_DIRS.contains(p.getName(i).toString().toLowerCase())) {
                                    return false;
                                }
                            }
                            return true;
                        })
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
                LOGGER.info("Skipped " + skipped + " unchanged files (incremental scan)");
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
                            LOGGER.info(status);
                            if (callback != null) {
                                callback.onProgress(discoveredMedia.size(), totalFiles, status);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error during parallel discovery", e);
                    }
                }
            } finally {
                isScanRunning = false;
                if (scanState != null) {
                    completeScanState(scanState, "COMPLETED", null, processedCount.get());
                }
            }
            LOGGER.info("Discovery phase completed. Found " + discoveredMedia.size() + " new or updated items.");
        } catch (IOException e) {
            LOGGER.error("Error scanning directory: " + directory, e);
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
        LOGGER.info("Starting scan for directory: " + directory);
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
            LOGGER.info("Video not found: " + videoId);
            return;
        }
        MediaFile mediaFile = MediaFile.find("path", video.path).firstResult();
        if (mediaFile == null) {
            LOGGER.info("MediaFile missing for video: " + videoId);
            return;
        }
        Path path = Paths.get(mediaFile.path);
        if (!Files.exists(path)) {
            LOGGER.info("File missing on disk: " + mediaFile.path);
            return;
        }
        
        String relativePath = path.getFileName().toString();
        SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(mediaFile, path.getFileName().toString(), relativePath, null, null, null, null, null, null);
        Video updatedVideo = entityCreationService.createVideoFromNamingResult(mediaFile, res);
        applyContentType(updatedVideo);
        
        // Trigger full metadata enrichment including audio track extraction
        if (updatedVideo != null) {
            videoMetadataService.fetchAndEnrichMetadata(updatedVideo);
        }
        
        LOGGER.info("Reloaded metadata for: " + path.getFileName());
    }

    @Transactional
    public void resetVideoDatabase() {
        LOGGER.info("Resetting video database...");
        try {
            // Clear per-profile video progress (video_progress table)
            Models.Video.VideoState.deleteAll();
        } catch (Exception e) {
            LOGGER.warn("Could not reset video progress: " + e.getMessage());
        }
        try {
            // Clear profile session states
            Models.Video.ProfileSessionState.deleteAll();
        } catch (Exception e) {
            LOGGER.warn("Could not reset session states: " + e.getMessage());
        }
        VideoHistory.deleteAll();
        try {
            Models.Video.VideoGenre.deleteAll();
        } catch (Exception e) {
            LOGGER.warn("Could not clear video genres: " + e.getMessage());
        }
        try {
            Models.Video.SubtitleTrack.deleteAll();
        } catch (Exception ignored) {}
        try {
            Models.Video.AudioTrack.deleteAll();
        } catch (Exception ignored) {}
        try {
            Models.Video.CollectionEntry.deleteAll();
        } catch (Exception ignored) {}
        Video.deleteAll();
        try {
            Models.Video.Series.deleteAll();
        } catch (Exception ignored) {}
        MediaFile.deleteAll();
        ScanState.deleteAll();

        for (String column : STALE_COLUMNS) {
            try {
                em.createNativeQuery("ALTER TABLE video DROP COLUMN IF EXISTS " + column).executeUpdate();
            } catch (Exception ignored) {}
        }

        try {
            Path thumbnailDir = Paths.get("thumbnails");
            if (Files.exists(thumbnailDir)) {
                try (Stream<Path> files = Files.list(thumbnailDir)) {
                    files.forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            LOGGER.warn("Could not delete thumbnail file: " + file.getFileName());
                        }
                    });
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not clear thumbnail directory: " + e.getMessage());
        }

        try {
            Path storyboardDir = Paths.get("storyboards");
            if (Files.exists(storyboardDir)) {
                try (Stream<Path> files = Files.list(storyboardDir)) {
                    files.forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            LOGGER.warn("Could not delete storyboard file: " + file.getFileName());
                        }
                    });
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not clear storyboard directory: " + e.getMessage());
        }

        thumbnailService.clearThumbnailCache();

        LOGGER.info("Video database reset completed");
    }

    @Transactional
    public int pruneMissingByType(String type) {
        return pruneMissingByType(type, null);
    }

    @Transactional
    public int pruneMissingByType(String type, Path libraryPath) {
        LOGGER.info("Pruning missing {} entries...", type);
        Path libPath = libraryPath != null ? libraryPath : null;

        List<Video> videos = Video.list("type", type);
        int prunedCount = 0;

        for (Video video : videos) {
            Path videoPath = Paths.get(video.path);
            boolean exists = Files.exists(videoPath);

            if (!exists && libPath != null) {
                Path resolved = libPath.resolve(video.path);
                if (Files.exists(resolved)) {
                    exists = true;
                }
            }

            if (!exists) {
                LOGGER.info("Pruning missing {}: {} (file not found)", type, video.path);
                deleteVideoWithRelations(video);
                prunedCount++;
            }
        }

        LOGGER.info("Pruned {} missing {} entries.", prunedCount, type);
        return prunedCount;
    }

    @Transactional
    protected void deleteVideoWithRelations(Video video) {
        Long videoId = video.id;

        Models.Video.VideoState.delete("video.id", videoId);
        Models.Video.VideoGenre.delete("video.id", videoId);
        Models.Video.SubtitleTrack.delete("video.id", videoId);
        Models.Video.AudioTrack.delete("video.id", videoId);
        Models.Video.CollectionEntry.delete("video.id", videoId);

        // Delete VideoHistory referencing this media file before deleting MediaFile (FK constraint)
        MediaFile mf = MediaFile.find("path", video.path).firstResult();
        if (mf != null) {
            VideoHistory.delete("mediaFile.id", mf.id);
            mf.delete();
        }
        video.delete();
    }

    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        // Skip incomplete downloads and temp files: they will be picked up on a
        // later scan once fully written. A .tmp.mp4 has no moov atom yet, so
        // importing it now would leave the library with an unplayable entry that
        // triggers repeated probe/transcode failures until it is overwritten.
        if (isPartialOrTempFile(fileName)) {
            return false;
        }
        return fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") ||
               fileName.endsWith(".mov") || fileName.endsWith(".wmv") || fileName.endsWith(".flv") ||
               fileName.endsWith(".webm") || fileName.endsWith(".m4v") || fileName.endsWith(".srt");
    }

    private boolean isPartialOrTempFile(String fileName) {
        // Hidden/system files (leading dot, e.g. ._Movie.mp4 or .Movie.mp4.part)
        if (fileName.startsWith(".")) {
            return true;
        }
        // Download-in-progress markers from common downloaders
        return fileName.contains(".tmp.") || fileName.contains(".part")
                || fileName.contains(".crdownload") || fileName.contains(".download")
                || fileName.contains(".!qB");
    }

    void applyContentType(Video video) {
        if (video == null) return;

        if (video.contentType == null) {
            video.contentType = "episode";
        }

        if (video.folder != null) {
            String folderLower = video.folder.trim().toLowerCase();
            if (FEATURETTE_FOLDERS.contains(folderLower)) {
                video.contentType = "featurette";
            }
        }

        if ("movie".equalsIgnoreCase(video.contentType)
                && video.seasonNumber != null
                && video.folder == null) {
            video.seasonNumber = null;
        }

        if ("extra".equalsIgnoreCase(video.contentType) && video.seasonNumber == null) {
            video.seasonNumber = 0;
        }

        if ("special".equalsIgnoreCase(video.contentType) && video.seasonNumber == null) {
            video.seasonNumber = 0;
        }
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
                
                if (!fileChanged && existingVideo != null && !metadataOnly && !forceFullScan) {
                    if (skippedFiles != null) {
                        skippedFiles.incrementAndGet();
                    }
                    return existingVideo;
                }
                
                String relativePath = rootPath.relativize(filePath).toString();
                SmartNamingService.NamingResult res = smartNamingService.detectSmartNames(existingFile, filename, relativePath, null, null, null, null, null, null);
                Video updated = entityCreationService.createVideoFromNamingResult(existingFile, res, forceFullScan);
                applyContentType(updated);
                return updated;
            }

            if (!metadataOnly) {
                if (filename.toLowerCase().endsWith(".srt")) return null;

                String currentHash = mediaAnalysisService.generateFingerprint(filePathStr);
                
                if (currentHash != null && ctx.videoByHash.containsKey(currentHash)) {
                    Video originalVideo = ctx.videoByHash.get(currentHash);
                    Video movedVideo = Video.findById(originalVideo.id);
                    if (movedVideo != null) {
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
                Video created = entityCreationService.createVideoFromNamingResult(mediaFile, res);
                applyContentType(created);
                return created;
            }
        } catch (Exception e) {
            LOGGER.error("Error processing file {}: {}", filename, e.getMessage(), e);
        }
        return null;
    }

    @Transactional
    public int updateContentTypeForExisting() {
        long total = Video.count("contentType IS NULL");
        if (total == 0) {
            LOGGER.info("All videos already have contentType set.");
            return 0;
        }

        LOGGER.info("Batch updating contentType for {} videos...", total);
        int processed = 0;
        final int batchSize = 100;

        while (processed < total) {
            List<Video> batch = Video.<Video>find("contentType IS NULL")
                    .page(processed / batchSize, batchSize).list();
            if (batch.isEmpty()) break;

            for (Video v : batch) {
                applyContentType(v);
            }

            em.flush();
            em.clear();
            processed += batch.size();
            LOGGER.info("updateContentTypeForExisting progress: {}/{}", processed, total);
        }

        LOGGER.info("Batch contentType update complete: {} videos updated.", processed);
        return processed;
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
