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

        long durationMs = (video.duration != null && video.duration > 0) ? video.duration : 0;
        
        // If duration is 0, we can't really calculate intervals
        if (durationMs <= 0) {
            LOGGER.warn("Video ID {} has no duration stored. Cannot provide storyboard metadata.", videoId);
            // We could try to probe here, but for now we just return null as the error indicates
            return null;
        }

        double durationSeconds = durationMs / 1000.0;
        double interval = durationSeconds / TOTAL_TILES;
        
        // Height is usually 9/16 of width for widescreen
        int tileHeight = (int) (TILE_WIDTH * 9.0 / 16.0);
        
        return new StoryboardMetadata(interval, TILE_WIDTH, tileHeight, COLUMNS, ROWS, TOTAL_TILES);
    }

    public File getStoryboardImage(Long videoId) {
        Path dir = getStoryboardDirectory();
        Path path = dir.resolve("video_" + videoId + ".jpg");
        
        if (Files.exists(path)) {
            return path.toFile();
        }

        // Generate on demand
        if (generateStoryboard(videoId, path)) {
            return path.toFile();
        }

        return null;
    }

    private boolean generateStoryboard(Long videoId, Path outputPath) {
        Video video = videoService.find(videoId);
        if (video == null || video.path == null) return false;

        String ffmpegPath = findFFmpegExecutable();
        if (ffmpegPath == null) {
            LOGGER.error("FFmpeg not found - cannot generate storyboard");
            return false;
        }

        double durationSeconds = (video.duration != null && video.duration > 0) ? video.duration / 1000.0 : 0;
        if (durationSeconds <= 0) return false;

        double fps = TOTAL_TILES / durationSeconds;
        int tileHeight = (int) (TILE_WIDTH * 9.0 / 16.0);

        // FFmpeg command to generate a tile grid
        // fps=N: extract frames at specific rate
        // scale=W:H: scale each frame
        // tile=CxR: arrange into grid
        String filter = String.format("fps=%.4f,scale=%d:%d,tile=%dx%d", fps, TILE_WIDTH, tileHeight, COLUMNS, ROWS);

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-i", video.path,
            "-vf", filter,
            "-frames:v", "1",
            "-q:v", "4",
            "-y",
            outputPath.toString()
        );

        try {
            LOGGER.info("Generating storyboard for video {}: {}", videoId, video.title);
            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            
            if (finished && process.exitValue() == 0) {
                LOGGER.info("Storyboard generated successfully for video {}", videoId);
                return true;
            } else {
                LOGGER.warn("FFmpeg storyboard generation failed for video {}", videoId);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error running FFmpeg for storyboard: " + e.getMessage());
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

    private String findFFmpegExecutable() {
        String[] paths = {"ffmpeg", "ffmpeg.exe", "C:\\ffmpeg\\bin\\ffmpeg.exe", "/usr/bin/ffmpeg"};
        for (String p : paths) {
            try {
                if (new ProcessBuilder(p, "-version").start().waitFor() == 0) return p;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
