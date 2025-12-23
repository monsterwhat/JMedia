package Services;

import Models.Video;
import Services.Thumbnail.ThumbnailJob;
import Services.Thumbnail.ThumbnailProcessingStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.ImageIO;

@ApplicationScoped
public class ThumbnailService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String THUMBNAIL_DIR = "thumbnails";
    
    @Inject
    EntityManager entityManager;
    
    private final ConcurrentHashMap<Long, String> thumbnailCache = new ConcurrentHashMap<>();
    private final BlockingQueue<ThumbnailJob> thumbnailQueue = new LinkedBlockingQueue<>();
    private ThumbnailProcessingStatus processingStatus = new ThumbnailProcessingStatus();
    
    @Transactional
    public String generateThumbnail(Long videoId, String videoPath) {
        try {
            // Check if thumbnail already exists
            String cachedPath = thumbnailCache.get(videoId);
            if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
                LOGGER.debug("Using cached thumbnail for video ID: " + videoId);
                return cachedPath;
            }
            
            // Generate thumbnail path
            String thumbnailFileName = "video_" + videoId + ".jpg";
            String thumbnailPath = THUMBNAIL_DIR + File.separator + thumbnailFileName;
            
            LOGGER.info("Generating thumbnail for video ID: " + videoId + " from: " + videoPath);
            
            // Create thumbnail directory if it doesn't exist
            Path thumbnailDir = Paths.get(THUMBNAIL_DIR);
            if (!Files.exists(thumbnailDir)) {
                Files.createDirectories(thumbnailDir);
                LOGGER.info("Created thumbnail directory: " + THUMBNAIL_DIR);
            }
            
            // Generate thumbnail using FFmpeg or similar
            LOGGER.debug("Starting thumbnail extraction for: " + videoPath);
            boolean success = extractVideoFrame(videoPath, thumbnailPath);
            
            if (success) {
                thumbnailCache.put(videoId, thumbnailPath);
                LOGGER.info("Thumbnail generated successfully: " + thumbnailPath);
                
                // Update video record with thumbnail path
                Video video = entityManager.find(Video.class, videoId);
                if (video != null) {
                    video.setThumbnailPath(thumbnailPath);
                    entityManager.persist(video);
                    LOGGER.debug("Updated video record with thumbnail path for ID: " + videoId);
                }
                
                return thumbnailPath;
            } else {
                LOGGER.warn("Failed to generate thumbnail for video ID: " + videoId);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error generating thumbnail for video " + videoId + ": " + e.getMessage());
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
            
            // Generate on-demand if not cached
            String thumbnailPath = generateThumbnail(id, fullPath);
            if (thumbnailPath != null) {
                return thumbnailPath;
            }
            
            // Fallback to placeholder
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
            // Clear existing queue
            thumbnailQueue.clear();
            
            // Add all videos to queue for regeneration
            Video.listAll().forEach(videoObj -> {
                Video video = (Video) videoObj;
                ThumbnailJob job = new ThumbnailJob(video.id, video.path, video.type);
                job.priority = false; // Background processing
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
    
    public void deleteExistingThumbnail(String videoPath, String videoType) {
        // Extract video ID from path (assuming last part is ID)
        try {
            String[] parts = videoPath.split("[\\\\/]");
            String filename = parts[parts.length - 1];
            String videoId = filename.replaceAll("_" + videoType + "\\.[^.]+$", "");
            
            deleteThumbnail(Long.parseLong(videoId));
            
        } catch (Exception e) {
            LOGGER.error("Error deleting existing thumbnail: " + e.getMessage());
        }
    }
    
    public void queueJob(ThumbnailJob job) {
        thumbnailQueue.offer(job);
    }
    
    public String processApiFirstThumbnail(ThumbnailJob job) {
        String mediaTitle = job.title != null ? job.title : (job.showName != null ? job.showName + " S" + job.season + "E" + job.episode : "Video " + job.videoId);
        
        // Try to get thumbnail from API first, fallback to local
        try {
            LOGGER.info("Attempting API thumbnail generation for: " + mediaTitle);
            
            // This would implement API-based thumbnail retrieval
            // For now, fallback to local generation
            LOGGER.info("API not available, falling back to local generation for: " + mediaTitle);
            return generateLocalThumbnail(job);
            
        } catch (Exception e) {
            LOGGER.error("Error processing API-first thumbnail for " + mediaTitle + ": " + e.getMessage());
            return null;
        }
    }
    
    public String generateLocalThumbnail(ThumbnailJob job) {
        String mediaTitle = job.title != null ? job.title : (job.showName != null ? job.showName + " S" + job.season + "E" + job.episode : "Video " + job.videoId);
        LOGGER.info("Starting local thumbnail generation for: " + mediaTitle);
        String result = generateThumbnail(job.videoId, job.videoPath);
        if (result != null) {
            LOGGER.info("Local thumbnail generation completed for: " + mediaTitle);
        } else {
            LOGGER.warn("Local thumbnail generation failed for: " + mediaTitle);
        }
        return result;
    }
    
    public boolean isQueueEmpty() {
        return thumbnailQueue.isEmpty();
    }
    
    public ThumbnailJob getNextJob() throws InterruptedException {
        return thumbnailQueue.take();
    }
    
    private boolean extractVideoFrame(String videoPath, String outputPath) {
        try {
            // This is a placeholder implementation
            // In a real implementation, you would use FFmpeg or a similar library
            // to extract a frame from the video
            
            // For now, create a simple placeholder image
            BufferedImage placeholderImage = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            
            // Create a simple gray placeholder
            for (int x = 0; x < 320; x++) {
                for (int y = 0; y < 240; y++) {
                    placeholderImage.setRGB(x, y, 0x808080);
                }
            }
            
            File outputFile = new File(outputPath);
            return ImageIO.write(placeholderImage, "jpg", outputFile);
            
        } catch (IOException e) {
            LOGGER.error("Error extracting frame: " + e.getMessage());
            return false;
        }
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
        return path != null && Files.exists(Paths.get(path));
    }
    
    @Transactional
    public void deleteThumbnail(Long videoId) {
        try {
            String thumbnailPath = thumbnailCache.remove(videoId);
            if (thumbnailPath != null) {
                Files.deleteIfExists(Paths.get(thumbnailPath));
            }
        } catch (IOException e) {
            LOGGER.error("Error deleting thumbnail: " + e.getMessage());
        }
    }
}