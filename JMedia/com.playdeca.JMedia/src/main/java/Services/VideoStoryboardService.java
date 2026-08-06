package Services;

import Models.Video;
import Utils.MediaPathResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class VideoStoryboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoStoryboardService.class);
    private static final String STORYBOARD_DIR = "storyboards";
    private static final int TILE_WIDTH = 160;
    private static final int COLUMNS = 10;
    private static final int ROWS = 10;
    private static final int TOTAL_TILES = COLUMNS * ROWS;

    @Inject
    EntityManager entityManager;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoService videoService;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor executor;

    private static final java.util.Set<Long> GENERATING_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Remember videos with missing source files so we stop re-attempting/re-logging until the backoff expires.
    private static final long MISSING_FILE_RETRY_MS = TimeUnit.HOURS.toMillis(1);
    private static final java.util.Map<Long, Long> MISSING_FILE_RETRY_AT = new java.util.concurrent.ConcurrentHashMap<>();

    public static class StoryboardMetadata {
        public double interval;
        public int width;
        public int height;
        public int columns;
        public int rows;
        public int totalTiles;
        public boolean isReady;

        public StoryboardMetadata(double interval, int width, int height, int columns, int rows, int totalTiles, boolean isReady) {
            this.interval = interval;
            this.width = width;
            this.height = height;
            this.columns = columns;
            this.rows = rows;
            this.totalTiles = totalTiles;
            this.isReady = isReady;
        }
    }

    public StoryboardMetadata getMetadata(Long videoId) {
        Video video = videoService.find(videoId);
        if (video == null) {
            LOGGER.warn("Storyboard metadata requested for non-existent video ID: {}", videoId);
            return null;
        }

        // Check if image exists using canonical naming
        Path dir = getStoryboardDirectory();
        String canonicalName = MediaPathResolver.resolveStoryboardName(video);
        String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
        Path canonicalPath = canonicalName != null ? dir.resolve(canonicalName) : null;
        Path legacyPath = dir.resolve(legacyName);
        Path actualPath = null;

        if (canonicalPath != null && Files.exists(canonicalPath)) {
            actualPath = canonicalPath;
        } else if (Files.exists(legacyPath)) {
            actualPath = legacyPath;
        }

        boolean exists = actualPath != null;

        // Always trigger generation if it doesn't exist
        if (!exists && !GENERATING_IDS.contains(videoId) && canonicalPath != null && isSourceFileAvailable(video, videoId)) {
            executor.submit(() -> generateStoryboard(videoId, canonicalPath));
        }

        long durationMs = (video.duration != null && video.duration > 0) ? video.duration : 0;
        
        // If duration is 0, we can't provide metadata yet
        if (durationMs <= 0) {
            return null;
        }

        double durationSeconds = durationMs / 1000.0;
        double interval = durationSeconds / TOTAL_TILES;
        int tileHeight = (int) (TILE_WIDTH * 9.0 / 16.0);
        
        return new StoryboardMetadata(interval, TILE_WIDTH, tileHeight, COLUMNS, ROWS, TOTAL_TILES, exists);
    }

    public boolean isGenerating(Long videoId) {
        return GENERATING_IDS.contains(videoId);
    }

    public File getStoryboardImage(Long videoId) {
        Video video = videoService.find(videoId);
        if (video == null) return null;

        Path dir = getStoryboardDirectory();
        String canonicalName = MediaPathResolver.resolveStoryboardName(video);
        if (canonicalName == null) return null;

        Path canonicalPath = dir.resolve(canonicalName);
        
        if (Files.exists(canonicalPath)) {
            return canonicalPath.toFile();
        }

        // Check legacy name and migrate
        String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
        Path legacyPath = dir.resolve(legacyName);
        if (Files.exists(legacyPath)) {
            try {
                Files.move(legacyPath, canonicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Migrated legacy storyboard {} -> {}", legacyName, canonicalName);
                return canonicalPath.toFile();
            } catch (IOException e) {
                LOGGER.warn("Failed to migrate legacy storyboard: {}", e.getMessage());
                return legacyPath.toFile();
            }
        }

        // If already generating, don't start another one, but don't block either
        if (GENERATING_IDS.contains(videoId)) {
            LOGGER.debug("Storyboard for video {} is already being generated.", videoId);
            return null;
        }

        // Generate in background
        if (isSourceFileAvailable(video, videoId)) {
            executor.submit(() -> generateStoryboard(videoId, canonicalPath));
        }

        return null;
    }

    @Inject
    FFmpegDiscoveryService discoveryService;

    /**
     * @return true if the video's source file exists on disk and a storyboard
     *         generation attempt is worthwhile right now
     */
    private boolean isSourceFileAvailable(Video video, Long videoId) {
        if (video.path == null || video.path.isBlank()) {
            return false;
        }
        Long retryAt = MISSING_FILE_RETRY_AT.get(videoId);
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            return false;
        }
        if (Files.exists(Paths.get(video.path))) {
            MISSING_FILE_RETRY_AT.remove(videoId);
            return true;
        }
        MISSING_FILE_RETRY_AT.put(videoId, System.currentTimeMillis() + MISSING_FILE_RETRY_MS);
        LOGGER.debug("Source file missing for video {}: {}", videoId, video.path);
        return false;
    }

    private boolean generateStoryboard(Long videoId, Path outputPath) {
        if (!GENERATING_IDS.add(videoId)) {
            return false;
        }

        try {
            Video video = videoService.find(videoId);
            if (video == null || video.path == null) return false;

            if (!isSourceFileAvailable(video, videoId)) {
                return false;
            }

            String ffmpegPath = discoveryService.findFFmpegExecutable();
            if (ffmpegPath == null) {
                LOGGER.error("FFmpeg not found - cannot generate storyboard");
                return false;
            }

            double durationSeconds = (video.duration != null && video.duration > 0) ? video.duration / 1000.0 : 0;
            if (durationSeconds <= 0) return false;

            // More efficient: jump to specific frames instead of processing every frame
            // We want 100 tiles. Select frames at regular intervals.
            double interval = durationSeconds / TOTAL_TILES;
            
            // Using select filter with a more efficient sampling strategy
            // 'not(mod(n,N))' is fast but 'select=between(t,x,y)' or 'fps' can be slow if not combined with seeking
            // However, for a single pass to a single image, this filter is generally okay
            // Let's use a slightly better filter for tiling
            int tileHeight = (int) (TILE_WIDTH * 9.0 / 16.0);
            String filter = String.format("select='not(mod(n,%d))',scale=%d:%d,tile=%dx%d", 
                (int)(durationSeconds * 24 / TOTAL_TILES), // Rough estimate of frames if 24fps
                TILE_WIDTH, tileHeight, COLUMNS, ROWS);
            
            // Actually, let's use the time-based selection which is more reliable
            filter = String.format("select='isnan(prev_selected_t)+gte(t-prev_selected_t,%.4f)',scale=%d:%d,tile=%dx%d", 
                interval, TILE_WIDTH, tileHeight, COLUMNS, ROWS);

            LOGGER.info("Generating storyboard for video {}: {}", videoId, video.title);
            Path tempPath = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");

            String hwDecoder = null;
            Models.Settings settings = settingsService.getOrCreateSettings();
            if (settings.getHardwareAccelerationEnabled() != null ? settings.getHardwareAccelerationEnabled() : true) {
                hwDecoder = discoveryService.getHardwareDecoder(video.videoCodec);
            }

            if (hwDecoder != null) {
                LOGGER.info("Storyboard for video {} using hardware decoder: {}", videoId, hwDecoder);
                if (runStoryboardFfmpeg(ffmpegPath, video, filter, tempPath, outputPath, videoId, hwDecoder)) {
                    return true;
                }
                LOGGER.warn("Hardware decode failed for storyboard of video {}, falling back to software", videoId);
                Files.deleteIfExists(tempPath);
            }
            return runStoryboardFfmpeg(ffmpegPath, video, filter, tempPath, outputPath, videoId, null);
        } catch (Exception e) {
            LOGGER.error("Error running FFmpeg for storyboard for video " + videoId, e);
            return false;
        } finally {
            GENERATING_IDS.remove(videoId);
        }
    }

    private boolean runStoryboardFfmpeg(String ffmpegPath, Video video, String filter, Path tempPath, Path outputPath, Long videoId, String hwDecoder) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        if (hwDecoder != null) {
            String hwaccelType = discoveryService.decoderToHwaccelType(hwDecoder);
            if (hwaccelType != null) {
                command.add("-hwaccel"); command.add(hwaccelType);
                if ("cuda".equals(hwaccelType)) {
                    command.add("-hwaccel_output_format"); command.add("cuda");
                    filter = "hwdownload,format=nv12," + filter;
                }
            }
        }

        command.add("-i"); command.add(video.path);
        command.add("-vf"); command.add(filter);
        command.add("-frames:v"); command.add("1");
        command.add("-c:v"); command.add("libwebp");
        command.add("-quality"); command.add("80");
        command.add("-f"); command.add("webp");
        command.add("-y");
        command.add(tempPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                Files.move(tempPath, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Storyboard generated successfully for video {}", videoId);
                return true;
            } else {
                if (!finished) {
                    process.destroyForcibly();
                }
                Files.deleteIfExists(tempPath);
                String ffmpegOutput = output.toString();
                if (ffmpegOutput.toLowerCase().contains("no such file or directory")) {
                    MISSING_FILE_RETRY_AT.put(videoId, System.currentTimeMillis() + MISSING_FILE_RETRY_MS);
                    LOGGER.debug("Source file for video {} is no longer available - skipping storyboard", videoId);
                    return false;
                }
                LOGGER.warn("FFmpeg storyboard generation failed or timed out for video {}. Exit code: {}. Output summary: {}",
                    videoId,
                    finished ? process.exitValue() : "TIMEOUT",
                    ffmpegOutput.length() > 500 ? ffmpegOutput.substring(ffmpegOutput.length() - 500) : ffmpegOutput);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Storyboard generation interrupted for video {}", videoId);
            return false;
        } catch (Exception e) {
            LOGGER.error("Error running FFmpeg for storyboard for video " + videoId, e);
            return false;
        }
    }

    private Path getStoryboardDirectory() {
        try {
            Path dir = Paths.get(STORYBOARD_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (IOException e) {
            LOGGER.error("Error creating storyboard directory: " + e.getMessage());
            return Paths.get(".");
        }
    }

    /**
     * Rename storyboard file when external IDs are obtained after enrichment.
     */
    @Transactional
    public void renameForExternalIds(Long videoId) {
        try {
            Video video = videoService.find(videoId);
            if (video == null) return;

            String canonicalName = MediaPathResolver.resolveStoryboardName(video);
            if (canonicalName == null) return;

            Path dir = getStoryboardDirectory();
            Path canonicalPath = dir.resolve(canonicalName);

            if (Files.exists(canonicalPath)) return;

            // Check legacy name and rename
            String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
            Path legacyPath = dir.resolve(legacyName);
            if (Files.exists(legacyPath)) {
                Files.move(legacyPath, canonicalPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Renamed storyboard {} -> {} after enrichment", legacyName, canonicalName);
            }
        } catch (Exception e) {
            LOGGER.error("Error renaming storyboard for video {}: {}", videoId, e.getMessage());
        }
    }
}
