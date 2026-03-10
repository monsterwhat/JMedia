package Services;

import Models.Video;
import Services.Thumbnail.ThumbnailJob;
import Services.Thumbnail.ThumbnailProcessingStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.ImageIO;

@ApplicationScoped
public class ThumbnailService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String THUMBNAIL_DIR = "thumbnails";
    
    @Inject
    EntityManager entityManager;

    @Inject
    VideoMetadataService metadataService;

    @Inject
    SettingsService settingsService;
    
    private final ConcurrentHashMap<Long, String> thumbnailCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> showThumbnailCache = new ConcurrentHashMap<>();
    private final BlockingQueue<ThumbnailJob> thumbnailQueue = new LinkedBlockingQueue<>();
    private ThumbnailProcessingStatus processingStatus = new ThumbnailProcessingStatus();
    
    @Transactional
    public String generateThumbnail(Long videoId, String videoPath) {
        try {
            // Check if thumbnail already exists in cache and on disk
            String cachedPath = thumbnailCache.get(videoId);
            if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
                return cachedPath;
            }
            
            // Check if video already has a thumbnail in the database
            Video existingVideo = entityManager.find(Video.class, videoId);
            if (existingVideo != null && existingVideo.thumbnailPath != null && !existingVideo.thumbnailPath.isBlank()) {
                Path existingPath = Paths.get(existingVideo.thumbnailPath);
                if (Files.exists(existingPath)) {
                    thumbnailCache.put(videoId, existingVideo.thumbnailPath);
                    LOGGER.info("Using existing thumbnail for video ID {}: {}", videoId, existingVideo.thumbnailPath);
                    return existingVideo.thumbnailPath;
                }
            }
            
            // Create thumbnail directory if it doesn't exist
            Path thumbnailDir = getThumbnailDirectory();
            
            // Generate unique thumbnail path for this video
            String thumbnailFileName = "video_" + videoId + ".jpg";
            Path outputPath = thumbnailDir.resolve(thumbnailFileName);
            
            // 1. STRATEGY A: Try to find local sidecar artwork (common Plex/Kodi convention)
            Path videoFilePath = Paths.get(videoPath);
            Path videoDir = videoFilePath.getParent();
            if (videoDir != null && Files.exists(videoDir)) {
                String[] sidecarNames = {"poster.jpg", "poster.png", "folder.jpg", "cover.jpg", "poster.webp"};
                for (String name : sidecarNames) {
                    Path sidecar = videoDir.resolve(name);
                    if (Files.exists(sidecar)) {
                        LOGGER.info("Found local sidecar artwork for ID {}: {}", videoId, name);
                        Files.copy(sidecar, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return finalizeThumbnail(videoId, outputPath.toString());
                    }
                }
            }

            // 2. STRATEGY B: Try to fetch from online API (TMDb)
            Video video = entityManager.find(Video.class, videoId);
            if (video != null && settingsService.getOrCreateSettings().getThumbnailPreferApi()) {
                String type = video.type != null ? video.type : "movie";
                
                // For episodes, we want to fetch the show's poster, not the episode's title
                String title;
                if ("episode".equalsIgnoreCase(type) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                    title = video.seriesTitle;
                } else {
                    title = video.title != null ? video.title : video.seriesTitle;
                }
                
                if (title != null) {
                    // Check show-level cache first
                    String normalizedTitle = title.toLowerCase().trim();
                    String cachedApiUrl = showThumbnailCache.get(normalizedTitle);
                    
                    if (cachedApiUrl != null) {
                        LOGGER.info("Using cached poster URL for show: {}", title);
                        downloadImage(cachedApiUrl, outputPath);
                        return finalizeThumbnail(videoId, outputPath.toString());
                    }
                    
                    LOGGER.info("Attempting online artwork fetch for: {}", title);
                    Optional<String> apiUrl = metadataService.fetchPosterUrl(type, title, video.releaseYear);
                    
                    // RATE LIMITING: Pause briefly to respect API limits (TMDb/TVMaze)
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                    if (apiUrl.isPresent()) {
                        // Cache the API URL for this show
                        showThumbnailCache.put(normalizedTitle, apiUrl.get());
                        downloadImage(apiUrl.get(), outputPath);
                        return finalizeThumbnail(videoId, outputPath.toString());
                    }
                }
            }

            // 3. STRATEGY C: Fallback to FFmpeg extraction at a meaningful time
            LOGGER.info("No artwork found for ID {}, falling back to FFmpeg extraction", videoId);
            boolean success = extractVideoFrame(videoPath, outputPath.toString());
            
            if (success) {
                return finalizeThumbnail(videoId, outputPath.toString());
            }
            
        } catch (Exception e) {
            LOGGER.error("Error generating thumbnail for video " + videoId + ": " + e.getMessage());
        }
        
        return null;
    }

    private String finalizeThumbnail(Long videoId, String path) {
        thumbnailCache.put(videoId, path);
        // Update video record with thumbnail path
        Video video = entityManager.find(Video.class, videoId);
        if (video != null) {
            // ONLY update if no thumbnail exists OR if the current one is already in the thumbnails directory (meaning it's a generated one)
            if (video.thumbnailPath == null || video.thumbnailPath.isBlank() || 
                video.thumbnailPath.contains(File.separator + THUMBNAIL_DIR + File.separator) ||
                video.thumbnailPath.contains("/" + THUMBNAIL_DIR + "/")) {
                
                video.setThumbnailPath(path);
                entityManager.persist(video);
                LOGGER.info("Updated thumbnail path for video ID {}: {}", videoId, path);
            } else {
                LOGGER.info("Skipping thumbnail path update for video ID {} because a custom path is already set: {}", videoId, video.thumbnailPath);
            }
        }
        return path;
    }

    private void downloadImage(String url, Path outputPath) throws IOException {
        java.net.URL imageUrl = new java.net.URL(url);
        try (java.io.InputStream in = imageUrl.openStream()) {
            Files.copy(in, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean extractVideoFrame(String videoPath, String outputPath) {
        try {
            // Seek to 10% of the video or 120 seconds, whichever is less, to get a "meaningful" shot
            // We'll use a default of 10 seconds if duration is unknown
            long seekSeconds = 10;
            Video video = Video.find("path", videoPath).firstResult();
            if (video != null && video.duration != null && video.duration > 0) {
                seekSeconds = Math.min(120, (video.duration / 1000) / 10);
            }

            String ffmpegPath = findFFmpegExecutable();
            if (ffmpegPath == null) {
                LOGGER.error("FFmpeg not found - cannot extract frames");
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-ss", String.valueOf(seekSeconds),
                "-i", videoPath,
                "-frames:v", "1",
                "-q:v", "2",
                "-vf", "scale=480:-1",
                "-y",
                outputPath
            );
            
            Process process = pb.start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            
            if (finished && process.exitValue() == 0) {
                return true;
            } else {
                LOGGER.warn("FFmpeg frame extraction failed for: {}", videoPath);
                return false;
            }
            
        } catch (Exception e) {
            LOGGER.error("FFmpeg extraction failed: " + e.getMessage());
            return false;
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
    
    public String getThumbnailPath(String fullPath, String videoId, String type) {
        try {
            Long id = Long.parseLong(videoId);
            String cachedPath = thumbnailCache.get(id);
            if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
                return cachedPath;
            }
            
            // Try to find on disk even if not in memory cache
            String thumbnailFileName = "video_" + id + ".jpg";
            Path diskPath = getThumbnailDirectory().resolve(thumbnailFileName);
            if (Files.exists(diskPath)) {
                thumbnailCache.put(id, diskPath.toString());
                return diskPath.toString();
            }
            
            // Generate on-demand if not found
            String thumbnailPath = generateThumbnail(id, fullPath);
            if (thumbnailPath != null) {
                return thumbnailPath;
            }
            
            // Fallback to placeholder if all else fails
            return "https://picsum.photos/seed/video" + videoId + "/300/450.jpg";
            
        } catch (Exception e) {
            LOGGER.error("Error getting thumbnail path: " + e.getMessage());
            return "https://picsum.photos/seed/video" + videoId + "/300/450.jpg";
        }
    }
    
    public Path getThumbnailDirectory() {
        try {
            Path dir = Paths.get(THUMBNAIL_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (IOException e) {
            LOGGER.error("Error creating thumbnail directory: " + e.getMessage());
            return Paths.get(".");
        }
    }
    
    @Transactional
    public void queueAllVideosForRegeneration() {
        try {
            thumbnailQueue.clear();
            Video.listAll().forEach(videoObj -> {
                Video video = (Video) videoObj;
                ThumbnailJob job = new ThumbnailJob(video.id, video.path, video.type);
                job.priority = false;
                thumbnailQueue.offer(job);
            });
            LOGGER.info("Queued all videos for thumbnail regeneration");
        } catch (Exception e) {
            LOGGER.error("Error queueing videos for regeneration: " + e.getMessage());
        }
    }
    
    public ThumbnailProcessingStatus getProcessingStatus() {
        return processingStatus;
    }
    
    public void deleteExistingThumbnail(String videoId, String videoType) {
        try {
            deleteThumbnail(Long.parseLong(videoId));
        } catch (Exception e) {
            LOGGER.error("Error deleting existing thumbnail: " + e.getMessage());
        }
    }
    
    public void queueJob(ThumbnailJob job) {
        thumbnailQueue.offer(job);
    }
    
    public String processApiFirstThumbnail(ThumbnailJob job) {
        return generateLocalThumbnail(job);
    }
    
    public String generateLocalThumbnail(ThumbnailJob job) {
        return generateThumbnail(job.videoId, job.videoPath);
    }
    
    public boolean isQueueEmpty() {
        return thumbnailQueue.isEmpty();
    }
    
    public ThumbnailJob getNextJob() throws InterruptedException {
        return thumbnailQueue.take();
    }
    
    public byte[] getThumbnailBytes(Long videoId) {
        try {
            String thumbnailPath = thumbnailCache.get(videoId);
            if (thumbnailPath != null && Files.exists(Paths.get(thumbnailPath))) {
                return Files.readAllBytes(Paths.get(thumbnailPath));
            }
        } catch (IOException e) {
            LOGGER.error("Error reading thumbnail bytes: " + e.getMessage());
        }
        return null;
    }
    
    public boolean hasThumbnail(Long videoId) {
        String path = thumbnailCache.get(videoId);
        if (path == null) {
            String thumbnailFileName = "video_" + videoId + ".jpg";
            Path diskPath = getThumbnailDirectory().resolve(thumbnailFileName);
            return Files.exists(diskPath);
        }
        return Files.exists(Paths.get(path));
    }
    
    @Transactional
    public void deleteThumbnail(Long videoId) {
        try {
            String thumbnailPath = thumbnailCache.remove(videoId);
            if (thumbnailPath != null) {
                Files.deleteIfExists(Paths.get(thumbnailPath));
            } else {
                String thumbnailFileName = "video_" + videoId + ".jpg";
                Files.deleteIfExists(getThumbnailDirectory().resolve(thumbnailFileName));
            }
        } catch (IOException e) {
            LOGGER.error("Error deleting thumbnail: " + e.getMessage());
        }
    }
}