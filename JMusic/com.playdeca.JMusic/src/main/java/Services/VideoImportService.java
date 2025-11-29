package Services;

import API.WS.LogSocket;
import Models.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional; // Re-add Transactional import


import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean; 
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.context.ManagedExecutor; // Inject ManagedExecutor
import java.util.ArrayList;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VideoImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoImportService.class);

    private final List<ScanResult> failedVideos = Collections.synchronizedList(new ArrayList<>());

    private static class ScanResult {
        String filePath;
        String rejectedReason;

        ScanResult(String filePath, String rejectedReason) {
            this.filePath = filePath;
            this.rejectedReason = rejectedReason;
        }
    }

    private record VideoMetadata(
        String title,
        String mediaType,
        String seriesTitle,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle,
        Integer releaseYear,
        int durationSeconds,
        int width,
        int height
    ) {}

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    LogSocket logSocket;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ManagedExecutor managedExecutor; // Inject ManagedExecutor



    private final AtomicBoolean isImporting = new AtomicBoolean(false);

    public void scanVideoLibrary() {
        if (isImporting.get()) {
            logSocket.broadcast("Video import is already in progress.");
            return;
        }

        if (!isImporting.compareAndSet(false, true)) {
            return;
        }

        this.failedVideos.clear(); // Clear previous failures

        try {
            logSocket.broadcast("Starting video library scan...");
            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
                throw new IllegalStateException("Video library path is not configured.");
            }

            File videoLibraryDir = new File(videoLibraryPath);
            if (!videoLibraryDir.exists() || !videoLibraryDir.isDirectory()) {
                throw new IllegalStateException("Video library directory does not exist or is not a directory: " + videoLibraryPath);
            }

            // Call the new performScan method to handle file collection and task submission
            int totalFiles = performScan(videoLibraryDir, videoLibraryPath);

            int successfullyProcessed = totalFiles - failedVideos.size();
            logSocket.broadcast("Video library scan finished. Processed " + successfullyProcessed + " files successfully.");

            if (!failedVideos.isEmpty()) {
                logSocket.broadcast("The following " + failedVideos.size() + " videos failed to process:");
                failedVideos.forEach(f -> logSocket.broadcast("- " + f.filePath + " (Reason: " + f.rejectedReason + ")"));
            }

        } catch (Exception e) {
            logSocket.broadcast("Error during video scan: " + e.getMessage());
            LOGGER.error("Error during video scan:", e);
        } finally {
            isImporting.set(false);
            logSocket.broadcast("[IMPORT_FINISHED]");
        }
    }

    public void reloadAllVideoMetadata() {
        if (isImporting.get()) {
            logSocket.broadcast("Video import/reload is already in progress.");
            return;
        }

        if (!isImporting.compareAndSet(false, true)) {
            return;
        }

        this.failedVideos.clear(); // Clear previous failures

        try {
            logSocket.broadcast("Starting video metadata reload...");
            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
                throw new IllegalStateException("Video library path is not configured.");
            }

            List<Video> allVideos = videoService.findAll(); // Get all videos currently in DB
            logSocket.broadcast("Found " + allVideos.size() + " videos to reload metadata for.");

            java.util.concurrent.ExecutorCompletionService<Void> completion = new java.util.concurrent.ExecutorCompletionService<>(managedExecutor);

            for (Video video : allVideos) {
                // Submit each video for processing in a separate task
                completion.submit(() -> {
                    reloadMetadataForVideo(video, videoLibraryPath);
                    return null; // Return null as reloadMetadataForVideo handles its own persistence and logging
                });
            }

            // Wait for all tasks to complete and log progress
            int processedCount = 0;
            for (int i = 0; i < allVideos.size(); i++) {
                try {
                    java.util.concurrent.Future<Void> future = completion.take();
                    future.get(); // Blocks until task completes or throws exception
                    processedCount++;
                    if (processedCount % 50 == 0) {
                        logSocket.broadcast("Processed " + processedCount + " / " + allVideos.size() + " video metadata reloads...");
                    }
                } catch (Exception e) {
                    // Error handling for reloadMetadataForVideo is within the method, this is for unexpected completion errors
                    logSocket.broadcast("Error during future.get() for video metadata reload: " + e.getMessage());
                    LOGGER.error("Error during future.get() for video metadata reload:", e);
                    // Add a generic failure if the error cannot be attributed to a specific video
                    failedVideos.add(new ScanResult("Unknown Video (Parallel Processing Error)", e.getMessage()));
                }
            }

            int successfullyProcessed = allVideos.size() - failedVideos.size();
            logSocket.broadcast("Video metadata reload finished. Processed " + successfullyProcessed + " videos successfully.");

            if (!failedVideos.isEmpty()) {
                logSocket.broadcast("The following " + failedVideos.size() + " videos failed to reload metadata:");
                failedVideos.forEach(f -> logSocket.broadcast("- " + f.filePath + " (Reason: " + f.rejectedReason + ")"));
            }

        } catch (Exception e) {
            logSocket.broadcast("Error during video metadata reload: " + e.getMessage());
            LOGGER.error("Error during video metadata reload:", e);
        } finally {
            isImporting.set(false);
            logSocket.broadcast("[IMPORT_FINISHED]");
        }
    }

 

    private int performScan(File folderToScan, String videoLibraryPath) throws InterruptedException, java.util.concurrent.ExecutionException {
        java.util.List<File> videoFiles = new java.util.ArrayList<>();
        collectVideoFiles(folderToScan, videoFiles);
        logSocket.broadcast("Found " + videoFiles.size() + " video files. Starting parallel metadata processing...");

        java.util.concurrent.ExecutorCompletionService<Void> completion = new java.util.concurrent.ExecutorCompletionService<>(managedExecutor);
        for (File file : videoFiles) {
            completion.submit(() -> {
                processFile(file, videoLibraryPath);
                return null; // Return null as processFile handles its own persistence and logging
            });
        }

        int processedCount = 0;
        for (int i = 0; i < videoFiles.size(); i++) {
            try {
                java.util.concurrent.Future<Void> future = completion.take();
                future.get(); // Blocks until task completes or throws exception
                processedCount++;
                if (processedCount % 50 == 0) {
                    logSocket.broadcast("Processed " + processedCount + " / " + videoFiles.size() + " video files...");
                }
            } catch (Exception e) {
                // Error handling now moved to processFile where it can add to failedVideos
                // This catch block is for unexpected errors during future.get()
                logSocket.broadcast("Error during future.get() for a video file: " + e.getMessage());
                LOGGER.error("Error during future.get() for a video file:", e);
                failedVideos.add(new ScanResult("Unknown File (Parallel Processing Error)", e.getMessage()));
            }
        }
        return videoFiles.size(); // Return total files found, not just successfully processed
    }

    private void collectVideoFiles(File folder, java.util.List<File> videoFiles) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectVideoFiles(f, videoFiles);
            } else if (f.isFile() && (f.getName().toLowerCase().endsWith(".mp4") || f.getName().toLowerCase().endsWith(".mkv") || f.getName().toLowerCase().endsWith(".avi") || f.getName().toLowerCase().endsWith(".webm"))) {
                videoFiles.add(f);
            }
        }
    }

    // New method to process a single video's metadata reload
    private void reloadMetadataForVideo(Video video, String videoLibraryPath) {
        String videoPath = video.getPath(); // Use the existing path from the video object
        try {
            // Reconstruct file path
            java.nio.file.Path filePath = Paths.get(videoLibraryPath, videoPath);
            File videoFile = filePath.toFile();

            if (!videoFile.exists() || !videoFile.isFile()) {
                logSocket.broadcast("Skipping metadata reload for missing file: " + videoPath);
                failedVideos.add(new ScanResult(videoPath, "File not found during reload"));
                videoService.delete(video); // Optionally remove missing files during reload
                return;
            }

            VideoMetadata metadata = extractVideoMetadata(videoFile);
            if (metadata == null) {
                logSocket.broadcast("Failed to extract metadata for: " + videoPath);
                failedVideos.add(new ScanResult(videoPath, "Failed to extract metadata (ffprobe error or parsing issue)"));
                return;
            }

            logSocket.broadcast("Reloading metadata for: " + videoPath);
            
            video.setTitle(metadata.title());
            video.setDurationSeconds(metadata.durationSeconds());
            video.setWidth(metadata.width());
            video.setHeight(metadata.height());
            
            video.setMediaType(metadata.mediaType());
            video.setSeriesTitle(metadata.seriesTitle());
            video.setSeasonNumber(metadata.seasonNumber());
            video.setEpisodeNumber(metadata.episodeNumber());
            video.setEpisodeTitle(metadata.episodeTitle());
            video.setReleaseYear(metadata.releaseYear());

            // Persist changes in a new transaction for this video
            videoService.persistVideoInNewTx(video);
        } catch (Exception e) {
            logSocket.broadcast("Error reloading metadata for video " + videoPath + ": " + e.getMessage());
            LOGGER.error("Stack trace for metadata reload error for {}:", videoPath, e);
            failedVideos.add(new ScanResult(videoPath, "Error: " + e.getMessage()));
        }
    }

    private VideoMetadata extractVideoMetadata(File videoFile) { // Removed throws Exception
        try {
            // Use ffprobe to extract core video metadata
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0", // Select only the first video stream
                "-show_entries", "stream=width,height,duration",
                "-of", "json",
                videoFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // Log the error but don't re-throw to allow processing to continue
                logSocket.broadcast("Error running ffprobe for " + videoFile.getName() + ": " + output.toString());
                return null; // Indicate failure
            }

            String ffprobeOutput = output.toString();
            
            JsonNode rootNode = objectMapper.readTree(ffprobeOutput);
            JsonNode streamNode = rootNode.path("streams").get(0);

            int durationSeconds = 0;
            int width = 0;
            int height = 0;

            if (streamNode != null) {
                if (streamNode.has("duration")) {
                    durationSeconds = (int) Double.parseDouble(streamNode.path("duration").asText());
                }
                if (streamNode.has("width")) {
                    width = streamNode.path("width").asInt();
                }
                if (streamNode.has("height")) {
                    height = streamNode.path("height").asInt();
                }
            }

            // --- Heuristic-based metadata extraction from filename and directory structure ---
            String mediaType = "Movie"; // Default to Movie
            String seriesTitle = null;
            Integer seasonNumber = null;
            Integer episodeNumber = null;
            String episodeTitle = null;
            Integer releaseYear = null;
            String detectedTitle = videoFile.getName().substring(0, videoFile.getName().lastIndexOf('.'));

            // Regex for SXXEXX (e.g., S01E01)
            Pattern seriesEpisodePattern = Pattern.compile("(?i)(?:s|season)[._-]?(\\d+)[._-](?:e|episode)[._-]?(\\d+)");
            // Regex for Year in filename or directory name
            Pattern yearPattern = Pattern.compile("(\\d{4})");

            // Try to get series/episode info from filename
            Matcher matcher = seriesEpisodePattern.matcher(videoFile.getName());
            if (matcher.find()) {
                mediaType = "Episode";
                seasonNumber = Integer.parseInt(matcher.group(1));
                episodeNumber = Integer.parseInt(matcher.group(2));
                
                // Try to infer series title from parent directory
                File parentDir = videoFile.getParentFile();
                if (parentDir != null) {
                    String parentName = parentDir.getName();
                    // Common patterns for series directories: "Show Name (Year)", "Show Name Season X"
                    Pattern seriesDirPattern = Pattern.compile("^(.*?)(?:\\s*\\(P?C?\\d{4}\\))?(?:\\s*[Ss]eason[._-]?\\d+)?$");
                    Matcher seriesDirMatcher = seriesDirPattern.matcher(parentName);
                    if (seriesDirMatcher.find()) {
                        seriesTitle = seriesDirMatcher.group(1).trim();
                    } else {
                        seriesTitle = parentName.trim();
                    }
                }
                
                // Extract episode title if available (anything after SXXEXX pattern and before extension)
                String remainingFileName = videoFile.getName().substring(matcher.end()).trim();
                int dotIndex = remainingFileName.lastIndexOf('.');
                if (dotIndex != -1) {
                    remainingFileName = remainingFileName.substring(0, dotIndex).trim();
                }
                if (!remainingFileName.isEmpty() && !remainingFileName.matches("^[._-]*$")) { // ignore just separators
                    episodeTitle = remainingFileName.replaceFirst("^[._-]*", "").replace('_', ' ').replace('.', ' ').trim();
                }
                
                if (episodeTitle == null || episodeTitle.isEmpty()) {
                    // Fallback: try to find a title before the SXXEXX pattern in the filename
                    String prefix = videoFile.getName().substring(0, matcher.start()).trim();
                    if (!prefix.isEmpty()) {
                        episodeTitle = prefix.replace('_', ' ').replace('.', ' ').trim();
                    }
                }
                if (episodeTitle == null || episodeTitle.isEmpty()) {
                     episodeTitle = "Episode " + episodeNumber; // Generic title if none found
                }


                // If seriesTitle is still null, try from grandparent directory (e.g. "TV Shows/Show Name/Season 1")
                File grandParentDir = (parentDir != null) ? parentDir.getParentFile() : null;
                if (seriesTitle == null && grandParentDir != null) {
                    String grandParentName = grandParentDir.getName();
                     Pattern grandParentSeriesPattern = Pattern.compile("^(.*?)(?:\\s*\\(P?C?\\d{4}\\))?(?:\\s*[Ss]eason[._-]?\\d+)?$");
                     Matcher grandParentSeriesMatcher = grandParentSeriesPattern.matcher(grandParentName);
                     if(grandParentSeriesMatcher.find()){
                         seriesTitle = grandParentSeriesMatcher.group(1).trim();
                     } else {
                         seriesTitle = grandParentName.trim();
                     }
                }
            }

            // Try to get release year from filename or parent directory for movies or if not found for episodes
            if (mediaType.equals("Movie") || (releaseYear == null && seriesTitle == null)) {
                Matcher yearMatcher = yearPattern.matcher(videoFile.getName());
                if (yearMatcher.find()) {
                    releaseYear = Integer.parseInt(yearMatcher.group(1));
                } else {
                    File parentDir = videoFile.getParentFile();
                    if (parentDir != null) {
                        yearMatcher = yearPattern.matcher(parentDir.getName());
                        if (yearMatcher.find()) {
                            releaseYear = Integer.parseInt(yearMatcher.group(1));
                        }
                    }
                }
                // If still a movie and release year is not found, use a default from path
                 if(mediaType.equals("Movie") && releaseYear == null){
                     // Try to find year in folder structure like "Movie Title (YYYY)"
                     String parentName = videoFile.getParentFile() != null ? videoFile.getParentFile().getName() : videoFile.getName();
                     Pattern movieFolderYearPattern = Pattern.compile(".*\\((\\d{4})\\)");
                     Matcher movieFolderYearMatcher = movieFolderYearPattern.matcher(parentName);
                     if(movieFolderYearMatcher.find()){
                         releaseYear = Integer.parseInt(movieFolderYearMatcher.group(1));
                     }
                 }
            }
            
            // Final fallback for title for movies
            if (mediaType.equals("Movie") && (detectedTitle == null || detectedTitle.isEmpty())) {
                detectedTitle = videoFile.getName().substring(0, videoFile.getName().lastIndexOf('.')).replace('_', ' ').replace('.', ' ').trim();
            } else if (mediaType.equals("Episode") && (detectedTitle == null || detectedTitle.isEmpty() && episodeTitle != null)) {
                detectedTitle = episodeTitle; // Use episode title as main title for episodes
            }


            return new VideoMetadata(
                detectedTitle,
                mediaType,
                seriesTitle,
                seasonNumber,
                episodeNumber,
                episodeTitle,
                releaseYear,
                durationSeconds,
                width,
                height
            );
        } catch (Exception e) {
            logSocket.broadcast("Error extracting metadata from file " + videoFile.getName() + ": " + e.getMessage());
            // It's good practice to also log the stack trace for debugging purposes
            // LOGGER.error("Stack trace for metadata extraction error:", e);
            return null; // Indicate that metadata extraction failed
        }
    }

    public void processFile(File file, String videoLibraryPath) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".avi") || fileName.endsWith(".webm")) {
            String relativePath = file.getName(); // Default in case of early error
            try {
                Path path = Paths.get(videoLibraryPath).relativize(Paths.get(file.getAbsolutePath()));
                relativePath = path.toString();

                Video video = videoService.findByPath(relativePath);
                if (video == null) {
                    video = new Video();
                    video.setPath(relativePath);
                    video.setDateAdded(LocalDateTime.now());
                } else {
                    logSocket.broadcast("Updating existing video: " + relativePath);
                }

                VideoMetadata metadata = extractVideoMetadata(file);
                if (metadata == null) {
                    logSocket.broadcast("Failed to extract metadata for: " + relativePath);
                    failedVideos.add(new ScanResult(relativePath, "Failed to extract metadata (ffprobe error or parsing issue)"));
                    return;
                }

                logSocket.broadcast("Processing video: " + relativePath);
                
                video.setTitle(metadata.title());
                video.setDurationSeconds(metadata.durationSeconds());
                video.setWidth(metadata.width());
                video.setHeight(metadata.height());
                
                video.setMediaType(metadata.mediaType());
                video.setSeriesTitle(metadata.seriesTitle());
                video.setSeasonNumber(metadata.seasonNumber());
                video.setEpisodeNumber(metadata.episodeNumber());
                video.setEpisodeTitle(metadata.episodeTitle()); 

                videoService.persistVideoInNewTx(video);
                logSocket.broadcast("Processed video: " + video.getTitle() + " (Type: " + metadata.mediaType() + ", Series: " + metadata.seriesTitle() + ", S" + metadata.seasonNumber() + "E" + metadata.episodeNumber() + ", Duration: " + metadata.durationSeconds() + "s, Resolution: " + metadata.width() + "x" + metadata.height() + ")");

            } catch (Exception e) {
                logSocket.broadcast("Error processing file " + file.getName() + ": " + e.getMessage());
                LOGGER.error("Error processing file {}:", file.getName(), e);
                failedVideos.add(new ScanResult(relativePath, "Error: " + e.getMessage()));
            }
        }
    }
}