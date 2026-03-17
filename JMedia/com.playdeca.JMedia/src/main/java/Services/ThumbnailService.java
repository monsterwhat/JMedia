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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.ImageIO;

@ApplicationScoped
public class ThumbnailService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String THUMBNAIL_DIR = "thumbnails";
    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

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
    
    private static class ShowMetadata {
        public String posterUrl;
        public String backdropUrl;
        public String tmdbId;
        public Instant fetchedAt;
        
        public ShowMetadata(String posterUrl, String backdropUrl, String tmdbId) {
            this.posterUrl = posterUrl;
            this.backdropUrl = backdropUrl;
            this.tmdbId = tmdbId;
            this.fetchedAt = Instant.now();
        }
    }
    
    private final ConcurrentHashMap<String, ShowMetadata> showMetadataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> episodeImageCache = new ConcurrentHashMap<>();
    
    @Transactional
    public String generateThumbnail(Long videoId, String videoPath) {
        return generateThumbnail(videoId, videoPath, true); // Default: allow FFmpeg fallback
    }
    
    @Transactional
    public String generateThumbnail(Long videoId, String videoPath, boolean allowFfmpegFallback) {
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
            String thumbnailFileName = "video_" + videoId + ".webp";
            Path outputPath = thumbnailDir.resolve(thumbnailFileName);
            
            // 1. STRATEGY A: Try to find local sidecar artwork (common standard/Kodi convention)
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

            // 3. STRATEGY C: Fallback to FFmpeg extraction (skip in background queue)
            if (allowFfmpegFallback) {
                LOGGER.info("No artwork found for ID {}, falling back to FFmpeg extraction", videoId);
                boolean success = extractVideoFrame(videoPath, outputPath.toString());
                
                if (success) {
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
            } else {
                LOGGER.info("Skipping FFmpeg extraction for ID {} (background queue mode)", videoId);
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
    
    private ShowMetadata getCachedShowMetadata(String showTitle) {
        if (showTitle == null) return null;
        return showMetadataCache.get(showTitle.toLowerCase().trim());
    }
    
    private void cacheShowMetadata(String showTitle, String posterUrl, String backdropUrl, String tmdbId) {
        if (showTitle != null && posterUrl != null) {
            showMetadataCache.put(showTitle.toLowerCase().trim(), 
                new ShowMetadata(posterUrl, backdropUrl, tmdbId));
        }
    }
    
    private String getEpisodeCacheKey(String seriesTitle, int season, int episode) {
        return (seriesTitle + "_s" + season + "e" + episode).toLowerCase().trim();
    }
    
    /**
     * Generate thumbnails for multiple videos with intelligent caching
     * @param videoIds List of video IDs
     * @param isBatchMode If true, use series-level caching aggressively
     * @return Map of videoId -> thumbnail path
     */
    public Map<Long, String> generateThumbnailsBatch(List<Long> videoIds, boolean isBatchMode) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        
        LOGGER.info("Generating thumbnails for batch of {} videos (batchMode={})", videoIds.size(), isBatchMode);
        
        Map<Long, String> results = new ConcurrentHashMap<>();
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
        
        for (Long videoId : videoIds) {
            completion.submit(() -> {
                Video video = entityManager.find(Video.class, videoId);
                if (video != null) {
                    return generateThumbnailWithContext(videoId, video.path, isBatchMode);
                }
                return null;
            });
        }
        
        int completed = 0;
        while (completed < videoIds.size()) {
            try {
                var future = completion.take();
                completed++;
                if (completed % 100 == 0) {
                    LOGGER.info("Thumbnail batch progress: {}/{}", completed, videoIds.size());
                }
            } catch (Exception e) {
                LOGGER.error("Error generating thumbnail: {}", e.getMessage());
            }
        }
        
        LOGGER.info("Thumbnail batch completed for {} videos", videoIds.size());
        return results;
    }
    
    /**
     * Generate thumbnail with context awareness
     * @param videoId Video ID
     * @param videoPath Path to video file
     * @param isBatchMode If true, prefer series poster; if false, prefer episode-specific
     * @return Thumbnail path or null
     */
    public String generateThumbnailWithContext(Long videoId, String videoPath, boolean isBatchMode) {
        try {
            Video video = entityManager.find(Video.class, videoId);
            if (video == null) return null;
            
            if (isBatchMode) {
                String seriesKey = video.seriesTitle != null ? video.seriesTitle.toLowerCase().trim() : null;
                ShowMetadata cached = showMetadataCache.get(seriesKey);
                if (cached != null) {
                    LOGGER.debug("Using cached series metadata for batch: {}", seriesKey);
                    Path thumbnailDir = getThumbnailDirectory();
                    String thumbnailFileName = "video_" + videoId + ".webp";
                    Path outputPath = thumbnailDir.resolve(thumbnailFileName);
                    downloadImage(cached.posterUrl, outputPath);
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
            }
            
            if (!isBatchMode && "episode".equalsIgnoreCase(video.type)) {
                String episodeKey = getEpisodeCacheKey(video.seriesTitle, video.seasonNumber, video.episodeNumber);
                String cachedEpisode = episodeImageCache.get(episodeKey);
                if (cachedEpisode != null) {
                    Path thumbnailDir = getThumbnailDirectory();
                    String thumbnailFileName = "video_" + videoId + ".webp";
                    Path outputPath = thumbnailDir.resolve(thumbnailFileName);
                    downloadImage(cachedEpisode, outputPath);
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
                
                Optional<String> episodeImage = metadataService.fetchEpisodeImageUrl(
                    video.seriesTitle, video.seasonNumber, video.episodeNumber);
                if (episodeImage.isPresent()) {
                    episodeImageCache.put(episodeKey, episodeImage.get());
                    Path thumbnailDir = getThumbnailDirectory();
                    String thumbnailFileName = "video_" + videoId + ".webp";
                    Path outputPath = thumbnailDir.resolve(thumbnailFileName);
                    downloadImage(episodeImage.get(), outputPath);
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
            }
            
            return generateThumbnail(videoId, videoPath);
        } catch (Exception e) {
            LOGGER.error("Error generating thumbnail with context for video {}: {}", videoId, e.getMessage());
            return null;
        }
    }

    @Inject
    FFmpegDiscoveryService discoveryService;

    private boolean extractVideoFrame(String videoPath, String outputPath) {
        try {
            // Seek to 10% of the video or 120 seconds, whichever is less, to get a "meaningful" shot
            // We'll use a default of 10 seconds if duration is unknown
            long seekSeconds = 10;
            Video video = Video.find("path", videoPath).firstResult();
            if (video != null && video.duration != null && video.duration > 0) {
                seekSeconds = Math.min(120, (video.duration / 1000) / 10);
            }

            String ffmpegPath = discoveryService.findFFmpegExecutable();
            if (ffmpegPath == null) {
                LOGGER.error("FFmpeg not found - cannot extract frames");
                return false;
            }

            String hwDecoder = discoveryService.getHardwareDecoder(video != null ? video.videoCodec : "h264");
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            
            // If hardware decoder is available, it must be placed BEFORE -i
            if (hwDecoder != null) {
                command.add("-c:v");
                command.add(hwDecoder);
            }
            
            command.add("-ss");
            command.add(String.valueOf(seekSeconds));
            command.add("-i");
            command.add(videoPath);
            command.add("-frames:v");
            command.add("1");
            command.add("-c:v");
            command.add("libwebp");
            command.add("-quality");
            command.add("85");
            command.add("-vf");
            command.add("scale=480:-1");
            command.add("-f");
            command.add("webp");
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            
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

    public String getThumbnailPath(String fullPath, String videoId, String type) {
        try {
            Long id = Long.parseLong(videoId);
            String cachedPath = thumbnailCache.get(id);
            if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
                return cachedPath;
            }
            
            // Try to find on disk even if not in memory cache
            String thumbnailFileName = "video_" + id + ".webp";
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
        // Skip FFmpeg in background queue - only use API/placeholder
        return generateThumbnail(job.videoId, job.videoPath, false);
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
            String thumbnailFileName = "video_" + videoId + ".webp";
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
                String thumbnailFileName = "video_" + videoId + ".webp";
                Files.deleteIfExists(getThumbnailDirectory().resolve(thumbnailFileName));
            }
        } catch (IOException e) {
            LOGGER.error("Error deleting thumbnail: " + e.getMessage());
        }
    }
}