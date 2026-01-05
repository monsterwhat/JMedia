package Services;

import API.WS.LogSocket;
import Controllers.SettingsController;
import Detectors.EpisodeDetector;
import Detectors.MovieDetector;
import Detectors.SubtitleMatcher;
import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import Models.PendingMedia;
import Models.Season;
import Models.Show;
import Services.MediaPreProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VideoImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoImportService.class);
    private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".mkv", ".avi", ".webm", ".flv", ".mov");
    private static final List<String> SUBTITLE_EXTENSIONS = List.of(".srt", ".vtt", ".ass", ".ssa");

    @Inject
    LogSocket logSocket;

    @Inject
    ObjectMapper objectMapper;
    
    @Inject
    SettingsController settingsController;
    
    @Inject
    MediaPreProcessor mediaPreProcessor;
    
    @Inject
    VideoEntityCreationService videoEntityCreationService;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS, r -> {
        Thread thread = new Thread(r);
        thread.setName("FileScanner-" + System.currentTimeMillis());
        thread.setDaemon(true);
        return thread;
    });
    
    // Stall detection tracking
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Map<String, StalledFileInfo> stalledFiles = new ConcurrentHashMap<>();
    
    private static class StalledFileInfo {
        int retryCount;
        long stallTime;
        String filePath;
        
        StalledFileInfo(String filePath) {
            this.filePath = filePath;
            this.retryCount = 0;
            this.stallTime = System.currentTimeMillis();
        }
    }

    public static class ScanResult {
        public int total, added, updated, removed, processed;
        public String toString() {
            return String.format("Scan finished. Processed: %d, Added: %d, Updated: %d.", processed, added, updated);
        }
    }
    
    private record TechnicalMetadata(int durationSeconds, int width, int height) {}


    public ScanResult scan(Path root, boolean fullScan) {
        String threadName = Thread.currentThread().getName();
        if (!isScanning.compareAndSet(false, true)) {
            String message = "Scan is already in progress.";
            logSocket.broadcast(message);
            settingsController.addLog(message);
            return null;
        }

        ScanResult stats = new ScanResult();
        try {
            LOGGER.info("FileScanner: Starting video library scan in directory: {}", root);
            settingsController.addLog("Starting video library scan...");
            logSocket.broadcast("Starting library scan...");
            
            List<Path> files = collectFiles(root);
            stats.total = files.size();
            
            // Reset tracking for new scan
            completedTasks.set(0);
            pendingTasks.set(0);
            stalledFiles.clear();
            
            LOGGER.info("FileScanner: Found {} files to analyze in: {}", stats.total, root);
            String foundMsg = "Found " + stats.total + " files to analyze.";
            settingsController.addLog(foundMsg);
            logSocket.broadcast(foundMsg);

            // Submit files for processing
            for (Path file : files) {
                String filePath = file.toString();
                
                // Check if this file was previously stalled and retried
                StalledFileInfo stalledInfo = stalledFiles.get(filePath);
                if (stalledInfo != null && stalledInfo.retryCount >= 1) {
                    LOGGER.warn("{}: Skipping previously stalled file: {} (already retried)", threadName, filePath);
                    synchronized (stats) { 
                        stats.processed++; 
                    }
                    continue;
                }
                
                pendingTasks.incrementAndGet();
                executor.submit(() -> {
                    try {
                        processFileWithLogging(file, root, fullScan, stats, threadName);
                    } catch (Exception e) {
                        LOGGER.error("{}: Error processing file in executor: {}", threadName, file, e);
                        settingsController.addLog("Error processing file " + file.getFileName().toString() + ": " + e.getMessage(), e);
                    } finally {
                        int completed = completedTasks.incrementAndGet();
                        pendingTasks.decrementAndGet();
                        
                        // Remove from stalled tracking if successful
                        stalledFiles.remove(filePath);
                        
                        // Progress logging every 50 tasks
                        synchronized (stats) {
                            stats.processed++;
                            if (stats.processed % 50 == 0 || stats.processed == stats.total) {
                                String progressMsg = String.format("Processed %d/%d files...", stats.processed, stats.total);
                                LOGGER.info("{}: {}", threadName, progressMsg);
                                logSocket.broadcast(progressMsg);
                            }
                        }
                    }
                });
            }
            
            // Stall detection loop
            long lastProgressTime = System.currentTimeMillis();
            int lastCompletedCount = 0;
            final long STALL_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes no progress

            while (completedTasks.get() < files.size()) {
                try {
                    Thread.sleep(100);
                    int currentCompleted = completedTasks.get();
                    
                    // Check for stall
                    if (currentCompleted == lastCompletedCount && 
                        System.currentTimeMillis() - lastProgressTime > STALL_TIMEOUT_MS) {
                        
                        LOGGER.warn("{}: Scan stalled - no progress for 2 minutes", threadName);
                        
                        // Identify stuck files and prepare for retry
                        identifyAndRetryStalledFiles(threadName, executor, root, fullScan, stats);
                        
                        // Update progress tracking
                        lastProgressTime = System.currentTimeMillis();
                        lastCompletedCount = currentCompleted;
                    }
                    
                    // Update progress tracking
                    if (currentCompleted > lastCompletedCount) {
                        lastCompletedCount = currentCompleted;
                        lastProgressTime = System.currentTimeMillis();
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("{}: Scan interrupted", threadName);
                    break;
                }
            }
            
            LOGGER.info("{}: Scan completed. Processed: {}, Added: {}, Updated: {}", 
                       threadName, stats.processed, stats.added, stats.updated);
            settingsController.addLog(stats.toString());
            logSocket.broadcast(stats.toString());
            logSocket.broadcast("[IMPORT_FINISHED]");
            
        } catch(IOException e){
            LOGGER.error("{}: IO error during scan: {}", threadName, e.getMessage(), e);
            System.out.println("Error: " + e.getLocalizedMessage());
        } finally {
            isScanning.set(false);
        }
        return stats;
    }
    
    /**
     * Identifies stalled files and submits them for retry
     */
    private void identifyAndRetryStalledFiles(String threadName, ExecutorService executor, 
                                           Path root, boolean fullScan, ScanResult stats) {
        
        try {
            // Find files that are still pending (stuck)
            List<String> stuckFiles = new ArrayList<>();
            List<Path> allFiles = collectFiles(root);
            
            for (Path file : allFiles) {
                String filePath = file.toString();
                StalledFileInfo stalledInfo = stalledFiles.get(filePath);
                
                // If file is not in stalled tracking but we're stalled, it's a new stuck file
                if (stalledInfo == null) {
                    stuckFiles.add(filePath);
                    stalledFiles.put(filePath, new StalledFileInfo(filePath));
                }
            }
            
            if (stuckFiles.isEmpty()) {
                return; // No new stalled files
            }
            
            LOGGER.info("{}: Identified {} stalled files for retry: {}", threadName, stuckFiles.size(), stuckFiles);
            
            // Submit retry tasks
            for (String filePath : stuckFiles) {
                StalledFileInfo stalledInfo = stalledFiles.get(filePath);
                stalledInfo.retryCount++;
                
                Path file = Paths.get(filePath);
                pendingTasks.incrementAndGet();
                
                executor.submit(() -> {
                    try {
                        LOGGER.info("{}: Retrying stalled file (attempt {}/2): {}",
                                threadName, stalledInfo.retryCount + 1, filePath);
                        
                        processFileWithLogging(file, root, fullScan, stats, threadName);
                        
                        LOGGER.info("{}: Retry successful for: {}", threadName, filePath);
                        
                    } catch (Exception e) {
                        LOGGER.error("{}: Retry failed for {}: {}", threadName, filePath, e.getMessage(), e);
                    } finally {
                        completedTasks.incrementAndGet();
                        pendingTasks.decrementAndGet();
                    }
                });
            }
        } catch (IOException ex) {
            System.getLogger(VideoImportService.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
    /**
     * Process a single file with enhanced logging
     */
    @Transactional
    public void processFileWithLogging(Path file, Path root, boolean fullScan, ScanResult stats, String threadName) {
        try {
            String fileName = file.getFileName().toString();
            String relativePath = root.relativize(file).toString().replace('\\', '/');
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();

            LOGGER.debug("{}: Processing file: {} (size: {}MB)", threadName, fileName, size / (1024 * 1024));

            MediaFile mediaFile = MediaFile.find("path", relativePath).firstResult();

            boolean needsProcessing = true;
            if (!fullScan && mediaFile != null && mediaFile.size == size && mediaFile.lastModified == lastModified) {
                needsProcessing = false;
                LOGGER.debug("{}: Skipping unchanged file: {}", threadName, fileName);
            }

            if (mediaFile == null) {
                LOGGER.info("{}: Discovered video file: {} (type: {})", threadName, fileName, getMediaType(file));
                mediaFile = new MediaFile();
                mediaFile.path = relativePath;
                mediaFile.type = getMediaType(file);
                synchronized(stats) { stats.added++; }
            } else {
                LOGGER.debug("{}: Updating existing file: {}", threadName, fileName);
                synchronized(stats) { stats.updated++; }
            }

            mediaFile.size = size;
            mediaFile.lastModified = lastModified;
            
            if ("video".equals(mediaFile.type) && needsProcessing) {
                TechnicalMetadata techMeta = extractTechnicalMetadata(file);
                if (techMeta != null) {
                    mediaFile.durationSeconds = techMeta.durationSeconds();
                    mediaFile.width = techMeta.width();
                    mediaFile.height = techMeta.height();
                    LOGGER.debug("{}: Extracted metadata for {}: duration={}s, resolution={}x{}", 
                                threadName, fileName, techMeta.durationSeconds(), techMeta.width(), techMeta.height());
                }
            }
            
            mediaFile.persist();
            
            // Phase 1: Create pending media for smart processing (after MediaFile is persisted)
            if ("video".equals(mediaFile.type) && needsProcessing) {
                PendingMedia pendingMedia = mediaPreProcessor.createPendingMedia(mediaFile, file, root);
                if (pendingMedia != null) {
                    LOGGER.debug("{}: Created PendingMedia for smart processing: {}", threadName, fileName);
                }
            }

        } catch (IOException e) {
            LOGGER.error("{}: Error processing file {}", threadName, file, e);
            settingsController.addLog("IO Error processing file " + file.getFileName().toString() + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Automatically processes pending media after scan completes
     * This should be called separately after the scan transaction completes
     */
    @Transactional
    public void processPendingMediaAfterScan() {
        try {
            long pendingCount = PendingMedia.count("status", Models.PendingMedia.ProcessingStatus.PENDING);
            LOGGER.info("Starting automatic smart processing for {} pending items...", pendingCount);
            settingsController.addLog("Starting automatic smart name processing for " + pendingCount + " items...");
            
            mediaPreProcessor.processPendingMedia();
            
            // Process completed pending media into actual Movie/Episode entities
            try {
                Thread.sleep(2000); // Give smart processing time to complete
                videoEntityCreationService.processCompletedPendingMedia();
                settingsController.addLog("Entity creation completed - created Movie and Episode records");
                LOGGER.info("Automatic entity creation completed");
            } catch (Exception e) {
                LOGGER.error("Error during automatic entity creation", e);
                settingsController.addLog("Error during entity creation: " + e.getMessage(), e);
            }
            
            settingsController.addLog("Smart processing completed. Check NamingController for items needing attention.");
            LOGGER.info("Automatic smart processing completed");
        } catch (Exception e) {
            LOGGER.error("Error during automatic smart processing", e);
            settingsController.addLog("Error during smart processing: " + e.getMessage(), e);
        } 
    }

    private List<Path> collectFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                         .filter(this::isMediaFile)
                         .collect(Collectors.toList());
        }
    }

    // Removed - replaced by processFileWithLogging method above
    
    private TechnicalMetadata extractTechnicalMetadata(Path videoFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,duration",
                "-of", "json",
                videoFile.toAbsolutePath().toString()
            );
            // DO NOT redirectErrorStream, keep stdout and stderr separate
            Process process = pb.start();

            StringBuilder stdoutOutput = new StringBuilder();
            StringBuilder stderrOutput = new StringBuilder();

            // Read stdout in a separate thread to prevent deadlock
            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutOutput.append(line);
                    }
                } catch (IOException e) {
                    LOGGER.error("Error reading ffprobe stdout for {}: {}", videoFile, e.getMessage());
                }
            });
            stdoutReader.start();

            // Read stderr in the current thread
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrOutput.append(line);
                }
            }
            stdoutReader.join(); // Wait for stdout reader to finish

            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                // ffprobe failed, log stderr as the error message
                LOGGER.error("ffprobe failed for {}: {}", videoFile, stderrOutput.toString().trim());
                settingsController.addLog("ffprobe failed for " + videoFile.getFileName().toString() + ": " + stderrOutput.toString().trim());
                return null;
            }

            if (stdoutOutput.length() == 0) {
                LOGGER.warn("ffprobe returned no output for {}. Is it a valid video file?", videoFile);
                settingsController.addLog("ffprobe returned no output for " + videoFile.getFileName().toString() + ". Is it a valid video file?");
                return null;
            }

            JsonNode rootNode = objectMapper.readTree(stdoutOutput.toString());
            JsonNode streamNode = rootNode.path("streams").get(0);

            if (streamNode != null) {
                int duration = (int) streamNode.path("duration").asDouble(0);
                int width = streamNode.path("width").asInt(0);
                int height = streamNode.path("height").asInt(0);
                return new TechnicalMetadata(duration, width, height);
            } else {
                LOGGER.warn("ffprobe stream node not found for {}. Is it a valid video stream?", videoFile);
                settingsController.addLog("ffprobe stream node not found for " + videoFile.getFileName().toString() + ". Is it a valid video stream?");
            }

        } catch (Exception e) {
            LOGGER.error("Error extracting metadata from file {}: {}", videoFile, e.getMessage());
            settingsController.addLog("Error extracting metadata from " + videoFile.getFileName().toString() + ": " + e.getMessage(), e);
        }
        return null;
    }

    private void processVideo(MediaFile videoFile, Path videoPath) {
        String filename = videoPath.getFileName().toString();

        Optional<EpisodeDetector.EpisodeInfo> episodeInfoOpt = EpisodeDetector.detect(filename);
        if (episodeInfoOpt.isPresent()) {
            EpisodeDetector.EpisodeInfo episodeInfo = episodeInfoOpt.get();
            
            String showName = inferShowName(videoPath);
            Show show = getOrCreateShow(showName);
            Season season = getOrCreateSeason(show, episodeInfo.season);
            
            Episode episode = Episode.find("videoPath", videoFile.path).firstResult();
            if (episode == null) {
                episode = new Episode();
                episode.videoPath = videoFile.path;
            }
            episode.title = episodeInfo.titleHint;
            episode.seasonNumber = episodeInfo.season;
            episode.episodeNumber = episodeInfo.episode;
            episode.season = season;
            
            List<Path> subtitlePaths = SubtitleMatcher.findExternalSubtitlesForVideo(videoPath);
            episode.subtitlePaths = subtitlePaths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
            
            episode.persist();

        } else {
            MovieDetector.MovieInfo movieInfo = MovieDetector.detect(filename);
            Movie movie = Movie.find("videoPath", videoFile.path).firstResult();
            if (movie == null) {
                movie = new Movie();
                movie.videoPath = videoFile.path;
            }
            movie.title = movieInfo.title;
            movie.releaseYear = movieInfo.releaseYear;
            
            List<Path> subtitlePaths = SubtitleMatcher.findExternalSubtitlesForVideo(videoPath);
            movie.subtitlePaths = subtitlePaths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());

            movie.persist();
        }
    }

    private String inferShowName(Path videoPath) {
        Path parent = videoPath.getParent();
        if (parent == null) return "Unknown Show";

        String showNameCandidate = parent.getFileName().toString();
        
        // If parent is a season folder (e.g., "Season 1", "S01"), go up one more level for the show name
        if (showNameCandidate.matches("(?i)season[s]?[-_.]?\\d{1,3}")) { // Increased digit count for flexibility
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                showNameCandidate = grandParent.getFileName().toString();
            }
        }
        
        // Clean up the show name: remove common patterns like resolution, source, release group tags
        String cleanedShowName = showNameCandidate
                .replaceAll("(?i)\\b(?:s\\d{1,3}|season(?:s)?\\d{1,3})\\b", "") // Remove "S01" or "Season 01"
                .replaceAll("(?i)\\b(?:\\d{3,4}p|\\d{3,4}i)\\b", "") // Remove resolutions like 720p, 1080i
                .replaceAll("(?i)\\b(?:web-?rip|hdtv|bluray|x264|x265|aac\\d{1,2}\\.?\\d{1,2}|ac3|dts|mp4|mkv|avi|flv|mov|webm|tgx|nf|amzn|hulu|netflix|amazon)\\b", "") // Remove common source/codec/release group tags
                .replaceAll("\\[[^\\]]+\\]", "") // Remove anything in square brackets
                .replaceAll("\\([^)]+\\)", "") // Remove anything in parentheses (e.g., years for movies, but here for show titles it's mostly junk)
                .replaceAll("^[._\\- ]+", "") // Remove leading dots, underscores, hyphens, spaces
                .replaceAll("[._\\- ]+$", "") // Remove trailing dots, underscores, hyphens, spaces
                .replaceAll("[._\\-]+", " ") // Replace multiple dots/underscores/hyphens with single spaces
                .trim();
        
        if (cleanedShowName.isEmpty()) {
            return "Unknown Show"; // Fallback if cleaning removed everything
        }
        return cleanedShowName;
    }
    
    private Show getOrCreateShow(String name) {
        Show show = Show.find("name", name).firstResult();
        if (show == null) {
            show = new Show();
            show.name = name;
            show.persist();
        }
        return show;
    }

    private Season getOrCreateSeason(Show show, int seasonNumber) {
        Season season = Season.find("show = ?1 and seasonNumber = ?2", show, seasonNumber).firstResult();
        if (season == null) {
            season = new Season();
            season.show = show;
            season.seasonNumber = seasonNumber;
            season.persist();
        }
        return season;
    }

    private boolean isMediaFile(Path file) {
        String name = file.toString().toLowerCase();
        return VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith) || SUBTITLE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
    
    private String getMediaType(Path file) {
        String name = file.toString().toLowerCase();
        if (VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return "video";
        }
        if (SUBTITLE_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return "subtitle";
        }
        return "unknown";
    }

    @Transactional
    public void resetVideoDatabase() {
        Episode.deleteAll();
        Movie.deleteAll();
        Season.deleteAll();
        Show.deleteAll();
        MediaFile.delete("type = 'video' or type = 'subtitle'");
        settingsController.addLog("Video database has been reset.");
    }

    @PreDestroy
    public void shutdownExecutor() {
        LOGGER.info("Shutting down video import service executor.");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
