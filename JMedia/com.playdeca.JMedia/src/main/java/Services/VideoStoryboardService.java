package Services;

import Models.Video;
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

    public static class StoryboardMetadata {
        public double interval;
        public int tileWidth;
        public int tileHeight;
        public int columns;
        public int rows;
        public int totalTiles;

        public StoryboardMetadata(double interval, int tileWidth, int tileHeight, int columns, int rows, int totalTiles) {
            this.interval = interval;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.columns = columns;
            this.rows = rows;
            this.totalTiles = totalTiles;
        }
    }

    public StoryboardMetadata getMetadata(Long videoId) {
        Video video = videoService.find(videoId);
        if (video == null) {
            LOGGER.warn("Storyboard metadata requested for non-existent video ID: {}", videoId);
            return null;
        }

        // Check if image exists
        Path dir = getStoryboardDirectory();
        Path path = dir.resolve("video_" + videoId + ".webp");
        boolean exists = Files.exists(path);

        // Always trigger generation if it doesn't exist
        if (!exists && !GENERATING_IDS.contains(videoId)) {
            executor.submit(() -> generateStoryboard(videoId, path));
        }

        long durationMs = (video.duration != null && video.duration > 0) ? video.duration : 0;
        
        // If duration is 0, we can't provide metadata yet
        if (durationMs <= 0) {
            return null;
        }

        double durationSeconds = durationMs / 1000.0;
        double interval = durationSeconds / TOTAL_TILES;
        int tileHeight = (int) (TILE_WIDTH * 9.0 / 16.0);
        
        return new StoryboardMetadata(interval, TILE_WIDTH, tileHeight, COLUMNS, ROWS, TOTAL_TILES);
    }

    public boolean isGenerating(Long videoId) {
        return GENERATING_IDS.contains(videoId);
    }

    public File getStoryboardImage(Long videoId) {
        Path dir = getStoryboardDirectory();
        Path path = dir.resolve("video_" + videoId + ".webp");
        
        if (Files.exists(path)) {
            return path.toFile();
        }

        // If already generating, don't start another one, but don't block either
        if (GENERATING_IDS.contains(videoId)) {
            LOGGER.debug("Storyboard for video {} is already being generated.", videoId);
            return null;
        }

        // Generate in background
        executor.submit(() -> generateStoryboard(videoId, path));

        return null;
    }

    @Inject
    FFmpegDiscoveryService discoveryService;

    private boolean generateStoryboard(Long videoId, Path outputPath) {
        if (!GENERATING_IDS.add(videoId)) {
            return false;
        }

        try {
            Video video = videoService.find(videoId);
            if (video == null || video.path == null) return false;

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
            
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", video.path,
                "-vf", filter,
                "-frames:v", "1",
                "-c:v", "libwebp",
                "-quality", "80",
                "-f", "webp",
                "-y",
                tempPath.toString()
            );

            pb.redirectErrorStream(true);
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
                LOGGER.warn("FFmpeg storyboard generation failed or timed out for video {}. Exit code: {}. Output summary: {}", 
                    videoId, 
                    finished ? process.exitValue() : "TIMEOUT", 
                    output.length() > 500 ? output.substring(output.length() - 500) : output.toString());
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error running FFmpeg for storyboard: " + e.getMessage());
            return false;
        } finally {
            GENERATING_IDS.remove(videoId);
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
}
