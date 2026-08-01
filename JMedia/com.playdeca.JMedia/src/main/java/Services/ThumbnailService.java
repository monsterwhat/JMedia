package Services;

import Models.Series;
import Models.Video;
import Services.Thumbnail.ThumbnailJob;
import Services.Thumbnail.ThumbnailProcessingStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import Utils.MediaPathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;

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

    public void clearThumbnailCache() {
        thumbnailCache.clear();
    }
    
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
            
            // Load video entity once for naming and strategy decisions
            Video video = entityManager.find(Video.class, videoId);
            
            // Check if video already has a thumbnail in the database
            if (video != null && video.thumbnailPath != null && !video.thumbnailPath.isBlank()) {
                Path existingPath = Paths.get(video.thumbnailPath);
                if (Files.exists(existingPath)) {
                    thumbnailCache.put(videoId, video.thumbnailPath);
                    LOGGER.info("Using existing thumbnail for video ID {}: {}", videoId, video.thumbnailPath);
                    return video.thumbnailPath;
                }
            }
            
            // Create thumbnail directory if it doesn't exist
            Path thumbnailDir = getThumbnailDirectory();
            
            // Generate canonical thumbnail name using MediaPathResolver
            String thumbnailFileName = video != null
                ? MediaPathResolver.resolveThumbnailName(video)
                : MediaPathResolver.legacyThumbnailName(videoId);
            if (thumbnailFileName == null) thumbnailFileName = MediaPathResolver.legacyThumbnailName(videoId);
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
                    // Check show-level cache first (key by imdbId when available)
                    String showCacheKey = getShowCacheKey(video, title);
                    String cachedApiUrl = showThumbnailCache.get(showCacheKey);
                    
                    if (cachedApiUrl != null) {
                        LOGGER.info("Using cached poster URL for show: {}", title);
                        downloadImage(cachedApiUrl, outputPath);

                        // STRATEGY B+: Fetch and store all 4 TMDB image sizes
                        try {
                            LOGGER.info("Strategy B+: Fetching multi-size images for cached show: {}", title);
                            VideoMetadataService.MediaImages tmdbImages = metadataService.fetchMediaImages(type, title, video.releaseYear,
                                video.seriesTitle, video.seasonNumber, video.episodeNumber);
                            ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                                tmdbImages.posterPath(), tmdbImages.logoPath(),
                                tmdbImages.backdropPath(), tmdbImages.heroPath(),
                                tmdbImages.stillPath());
                            MediaImagePaths paths = downloadMediaImages(videoId, localImages);
                            if (paths.posterPath() != null) video.posterPath = paths.posterPath();
                            if (paths.logoPath() != null) video.logoPath = paths.logoPath();
                            if (paths.backdropPath() != null) video.backdropPath = paths.backdropPath();
                            if (paths.heroPath() != null) video.heroPath = paths.heroPath();
                            if (paths.stillPath() != null) video.stillPath = paths.stillPath();
                            entityManager.persist(video);
                            LOGGER.info("Strategy B+: Updated image paths for cached video {}", videoId);
                        } catch (Exception ex) {
                            LOGGER.warn("Strategy B+: Multi-image fetch failed for cached video {}: {}", videoId, ex.getMessage());
                        }

                        return finalizeThumbnail(videoId, outputPath.toString());
                    }
                    
                    LOGGER.info("Attempting online artwork fetch for: {}", title);
                    Optional<String> apiUrl = metadataService.fetchPosterUrl(type, title, video.releaseYear);
                    
                    // RATE LIMITING: Pause briefly to respect API limits (TMDb/TVMaze)
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                    if (apiUrl.isPresent()) {
                        // Cache the API URL for this show
                        showThumbnailCache.put(showCacheKey, apiUrl.get());
                        downloadImage(apiUrl.get(), outputPath);

                        // STRATEGY B+: Fetch and store all 4 TMDB image sizes
                        try {
                            LOGGER.info("Strategy B+: Fetching multi-size images for: {}", title);
                            VideoMetadataService.MediaImages tmdbImages = metadataService.fetchMediaImages(type, title, video.releaseYear,
                                video.seriesTitle, video.seasonNumber, video.episodeNumber);
                            ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                                tmdbImages.posterPath(), tmdbImages.logoPath(),
                                tmdbImages.backdropPath(), tmdbImages.heroPath(),
                                tmdbImages.stillPath());
                            MediaImagePaths paths = downloadMediaImages(videoId, localImages);
                            if (paths.posterPath() != null) video.posterPath = paths.posterPath();
                            if (paths.logoPath() != null) video.logoPath = paths.logoPath();
                            if (paths.backdropPath() != null) video.backdropPath = paths.backdropPath();
                            if (paths.heroPath() != null) video.heroPath = paths.heroPath();
                            if (paths.stillPath() != null) video.stillPath = paths.stillPath();
                            entityManager.persist(video);
                            LOGGER.info("Strategy B+: Updated image paths for video {}", videoId);
                        } catch (Exception ex) {
                            LOGGER.warn("Strategy B+: Multi-image fetch failed for {}: {}", videoId, ex.getMessage());
                        }

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
    
    /**
     * Compute a show cache key preferring IMDb ID for stability across rescans.
     */
    private String getShowCacheKey(Video video, String fallbackTitle) {
        if (video == null && fallbackTitle == null) return null;
        String id = video != null ? MediaPathResolver.getPrimaryId(video) : null;
        if (id != null) return id;
        if ("episode".equalsIgnoreCase(video != null ? video.type : null) && video.showImdbId != null) {
            return video.showImdbId;
        }
        String showImdbId = video != null ? video.showImdbId : null;
        if (showImdbId != null && !showImdbId.isBlank()) return showImdbId;
        return fallbackTitle != null ? fallbackTitle.toLowerCase().trim() : null;
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
    
    private String getEpisodeCacheKey(Video video, String seriesTitle, int season, int episode) {
        if (video != null && video.showImdbId != null && !video.showImdbId.isBlank()) {
            return video.showImdbId + "_S" + String.format("%02d", season) + "E" + String.format("%02d", episode);
        }
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
            
            String canonicalName = MediaPathResolver.resolveThumbnailName(video);
            if (canonicalName == null) canonicalName = MediaPathResolver.legacyThumbnailName(videoId);
            
            if (isBatchMode) {
                String seriesKey = getShowCacheKey(video, video.seriesTitle);
                ShowMetadata cached = seriesKey != null ? showMetadataCache.get(seriesKey) : null;
                if (cached != null) {
                    LOGGER.debug("Using cached series metadata for batch: {}", seriesKey);
                    Path thumbnailDir = getThumbnailDirectory();
                    Path outputPath = thumbnailDir.resolve(canonicalName);
                    downloadImage(cached.posterUrl, outputPath);
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
            }
            
            if (!isBatchMode && "episode".equalsIgnoreCase(video.type)) {
                String episodeKey = getEpisodeCacheKey(video, video.seriesTitle, video.seasonNumber, video.episodeNumber);
                String cachedEpisode = episodeImageCache.get(episodeKey);
                if (cachedEpisode != null) {
                    Path thumbnailDir = getThumbnailDirectory();
                    Path outputPath = thumbnailDir.resolve(canonicalName);
                    downloadImage(cachedEpisode, outputPath);
                    return finalizeThumbnail(videoId, outputPath.toString());
                }
                
                Optional<String> episodeImage = metadataService.fetchEpisodeImageUrl(
                    video.seriesTitle, video.seasonNumber, video.episodeNumber);
                if (episodeImage.isPresent()) {
                    episodeImageCache.put(episodeKey, episodeImage.get());
                    Path thumbnailDir = getThumbnailDirectory();
                    Path outputPath = thumbnailDir.resolve(canonicalName);
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

            // Try hardware decoder first, fall back to software
            String hwDecoder = discoveryService.getHardwareDecoder(video != null ? video.videoCodec : "h264");
            boolean useHardware = hwDecoder != null;

            if (useHardware) {
                if (runFfmpegFrameExtract(ffmpegPath, videoPath, outputPath, seekSeconds, hwDecoder)) {
                    return true;
                }
                LOGGER.warn("Hardware decoder failed for thumbnail, falling back to software: {}", videoPath);
            }

            return runFfmpegFrameExtract(ffmpegPath, videoPath, outputPath, seekSeconds, null);
            
        } catch (Exception e) {
            LOGGER.error("FFmpeg extraction failed: " + e.getMessage());
            return false;
        }
    }

    private boolean runFfmpegFrameExtract(String ffmpegPath, String videoPath, String outputPath, long seekSeconds, String hwDecoder) {
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            
            command.add("-v");
            command.add("error");
            command.add("-hide_banner");
            
            if (hwDecoder != null) {
                command.add("-hwaccel");
                command.add(hwDecoder.contains("cuvid") ? "cuda" : hwDecoder);
                if (hwDecoder.contains("cuvid")) {
                    command.add("-hwaccel_output_format");
                    command.add("cuda");
                }
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
            if (hwDecoder != null && hwDecoder.contains("cuvid")) {
                command.add("hwdownload,format=nv12,scale=480:-1");
            } else {
                command.add("scale=480:-1");
            }
            command.add("-f");
            command.add("webp");
            command.add("-max_muxing_queue_size");
            command.add("1024");
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            String stderr = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            
            if (finished && process.exitValue() == 0) {
                return true;
            }
            
            int exitCode = finished ? process.exitValue() : -1;
            LOGGER.warn("FFmpeg frame extraction failed for: {} (exit={}) stderr: {}", videoPath, exitCode, stderr);
            return false;
            
        } catch (Exception e) {
            LOGGER.error("FFmpeg frame extract run failed: " + e.getMessage());
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
            
            // Try to find canonical name on disk first
            Video video = entityManager.find(Video.class, id);
            if (video != null) {
                String canonicalName = MediaPathResolver.resolveThumbnailName(video);
                if (canonicalName != null) {
                    Path canonicalPath = getThumbnailDirectory().resolve(canonicalName);
                    if (Files.exists(canonicalPath)) {
                        thumbnailCache.put(id, canonicalPath.toString());
                        return canonicalPath.toString();
                    }
                }
            }
            
            // Legacy fallback: check video_<id>.webp and rename if found
            String legacyName = MediaPathResolver.legacyThumbnailName(id);
            Path legacyPath = getThumbnailDirectory().resolve(legacyName);
            if (Files.exists(legacyPath) && video != null) {
                String canonicalName = MediaPathResolver.resolveThumbnailName(video);
                if (canonicalName != null) {
                    Path canonicalPath = getThumbnailDirectory().resolve(canonicalName);
                    try {
                        Files.move(legacyPath, canonicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Migrated legacy thumbnail {} -> {}", legacyName, canonicalName);
                        thumbnailCache.put(id, canonicalPath.toString());
                        return canonicalPath.toString();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to migrate legacy thumbnail, returning legacy path: {}", e.getMessage());
                        thumbnailCache.put(id, legacyPath.toString());
                        return legacyPath.toString();
                    }
                }
                thumbnailCache.put(id, legacyPath.toString());
                return legacyPath.toString();
            }
            
            // Generate on-demand if not found
            String thumbnailPath = generateThumbnail(id, fullPath);
            if (thumbnailPath != null) {
                return thumbnailPath;
            }
            
            // Fallback to app logo if all else fails
            return "/logo.png";
            
        } catch (Exception e) {
            LOGGER.error("Error getting thumbnail path: " + e.getMessage());
            return "/logo.png";
        }
    }
    
    public String getThumbnailPathWithFallback(String fullPath, Video video) {
        try {
            Long id = video.id;
            String cachedPath = thumbnailCache.get(id);
            if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
                return cachedPath;
            }
            
            // Try canonical name on disk first
            String canonicalName = MediaPathResolver.resolveThumbnailName(video);
            if (canonicalName != null) {
                Path canonicalPath = getThumbnailDirectory().resolve(canonicalName);
                if (Files.exists(canonicalPath)) {
                    thumbnailCache.put(id, canonicalPath.toString());
                    return canonicalPath.toString();
                }
            }
            
            // Legacy fallback: check video_<id>.webp and rename if found
            String legacyName = MediaPathResolver.legacyThumbnailName(id);
            Path legacyPath = getThumbnailDirectory().resolve(legacyName);
            if (Files.exists(legacyPath) && canonicalName != null) {
                Path canonicalPath = getThumbnailDirectory().resolve(canonicalName);
                try {
                    Files.move(legacyPath, canonicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Migrated legacy thumbnail {} -> {}", legacyName, canonicalName);
                    thumbnailCache.put(id, canonicalPath.toString());
                    return canonicalPath.toString();
                } catch (Exception e) {
                    LOGGER.warn("Failed to migrate legacy thumbnail, returning legacy path: {}", e.getMessage());
                    thumbnailCache.put(id, legacyPath.toString());
                    return legacyPath.toString();
                }
            } else if (Files.exists(legacyPath)) {
                thumbnailCache.put(id, legacyPath.toString());
                return legacyPath.toString();
            }
            
            // Generate on-demand if not found
            String thumbnailPath = generateThumbnail(id, fullPath);
            if (thumbnailPath != null) {
                return thumbnailPath;
            }
            
            // For episodes, try season/show thumbnail as fallback
            if ("episode".equals(video.type) && video.seriesTitle != null) {
                String fallback = getSeriesOrSeasonThumbnail(video.seriesTitle, video.seasonNumber, id);
                if (fallback != null) {
                    return fallback;
                }
            }
            
            // Fallback to app logo if all else fails
            return "/logo.png";
            
        } catch (Exception e) {
            LOGGER.error("Error getting thumbnail path with fallback: " + e.getMessage());
            return "/logo.png";
        }
    }
    
    private String getSeriesOrSeasonThumbnail(String seriesTitle, Integer seasonNumber, Long currentVideoId) {
        // First, try to find any episode from the same season with a thumbnail
        if (seasonNumber != null) {
            List<Video> seasonEpisodes;
            try {
                seasonEpisodes = Video.list(
                    "seriesTitle = ?1 and seasonNumber = ?2 and type = ?3",
                    seriesTitle, seasonNumber, "episode"
                );
            } catch (Exception e) {
                LOGGER.warn("Hibernate session unavailable for season thumbnail lookup (background thread): {}", e.getMessage());
                seasonEpisodes = java.util.Collections.emptyList();
            }
            for (Video ep : seasonEpisodes) {
                if (ep.id.equals(currentVideoId)) continue;
                String cached = thumbnailCache.get(ep.id);
                if (cached != null && Files.exists(Paths.get(cached))) {
                    return cached;
                }
                String canonicalName = MediaPathResolver.resolveThumbnailName(ep);
                if (canonicalName != null) {
                    Path path = getThumbnailDirectory().resolve(canonicalName);
                    if (Files.exists(path)) {
                        thumbnailCache.put(ep.id, path.toString());
                        return path.toString();
                    }
                }
                // Fallback to legacy name
                String legacyName = MediaPathResolver.legacyThumbnailName(ep.id);
                Path legacyPath = getThumbnailDirectory().resolve(legacyName);
                if (Files.exists(legacyPath)) {
                    thumbnailCache.put(ep.id, legacyPath.toString());
                    return legacyPath.toString();
                }
            }
        }
        
        // Try to find any episode from the series with a thumbnail
        List<Video> seriesEpisodes;
        try {
            seriesEpisodes = Video.list(
                "seriesTitle = ?1 and type = ?2",
                seriesTitle, "episode"
            );
        } catch (Exception e) {
            LOGGER.warn("Hibernate session unavailable for series thumbnail lookup (background thread): {}", e.getMessage());
            seriesEpisodes = java.util.Collections.emptyList();
        }
        for (Video ep : seriesEpisodes) {
            if (ep.id.equals(currentVideoId)) continue;
            String cached = thumbnailCache.get(ep.id);
            if (cached != null && Files.exists(Paths.get(cached))) {
                return cached;
            }
            String canonicalName = MediaPathResolver.resolveThumbnailName(ep);
            if (canonicalName != null) {
                Path path = getThumbnailDirectory().resolve(canonicalName);
                if (Files.exists(path)) {
                    thumbnailCache.put(ep.id, path.toString());
                    return path.toString();
                }
            }
            String legacyName = MediaPathResolver.legacyThumbnailName(ep.id);
            Path legacyPath = getThumbnailDirectory().resolve(legacyName);
            if (Files.exists(legacyPath)) {
                thumbnailCache.put(ep.id, legacyPath.toString());
                return legacyPath.toString();
            }
        }
        
        return null;
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

    /**
     * Backfill multi-size TMDB images (poster, logo, backdrop, hero) for all
     * videos that are missing any of the four files on disk.  Intended to be
     * called manually from an admin endpoint or background job — NOT invoked
     * automatically.
     * <p>
     * Unlike the previous version that only looked at DB columns, this checks
     * actual disk existence so videos with a poster but missing hero/logo/
     * backdrop are also picked up.  Videos where all four files already exist
     * are skipped without an API call.
     */
    @Transactional
    public void backfillMediaImages() {
        List<Video> videos = Video.list("path IS NOT NULL");
        LOGGER.info("Backfill: Scanning {} videos for missing multi-size images", videos.size());

        Path thumbnailsDir = getThumbnailDirectory();

        int processed = 0;
        int skipped = 0;
        int succeeded = 0;
        int failed = 0;
        int imagesDownloaded = 0;

        for (Video video : videos) {
            try {
                // Check which image files already exist on disk
                boolean hasPoster   = Files.exists(thumbnailsDir.resolve(video.id + "_poster.webp"))   || Files.exists(thumbnailsDir.resolve(video.id + "_poster.jpg"))   || Files.exists(thumbnailsDir.resolve(video.id + "_poster.png"));
                boolean hasLogo     = Files.exists(thumbnailsDir.resolve(video.id + "_logo.webp"))     || Files.exists(thumbnailsDir.resolve(video.id + "_logo.jpg"))     || Files.exists(thumbnailsDir.resolve(video.id + "_logo.png"));
                boolean hasBackdrop = Files.exists(thumbnailsDir.resolve(video.id + "_backdrop.webp")) || Files.exists(thumbnailsDir.resolve(video.id + "_backdrop.jpg")) || Files.exists(thumbnailsDir.resolve(video.id + "_backdrop.png"));
                boolean hasHero     = Files.exists(thumbnailsDir.resolve(video.id + "_hero.webp"))     || Files.exists(thumbnailsDir.resolve(video.id + "_hero.jpg"))     || Files.exists(thumbnailsDir.resolve(video.id + "_hero.png"));

                if (hasPoster && hasLogo && hasBackdrop && hasHero) {
                    skipped++;
                    processed++;
                    continue;
                }

                String type = video.type != null ? video.type : "movie";

                // Resolve title the same way generateThumbnail does
                String title;
                if ("episode".equalsIgnoreCase(type) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                    title = video.seriesTitle;
                } else {
                    title = video.title != null ? video.title : video.seriesTitle;
                }

                if (title == null || title.isBlank()) {
                    LOGGER.debug("Backfill: Skipping video {} — no title available", video.id);
                    processed++;
                    continue;
                }

                VideoMetadataService.MediaImages tmdbImages = metadataService.fetchMediaImages(type, title, video.releaseYear,
                    video.seriesTitle, video.seasonNumber, video.episodeNumber);
                ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                    tmdbImages.posterPath(), tmdbImages.logoPath(),
                    tmdbImages.backdropPath(), tmdbImages.heroPath(),
                    tmdbImages.stillPath());
                MediaImagePaths paths = downloadMediaImages(video.id, localImages);

                int downloaded = 0;
                if (paths.posterPath() != null)   { video.posterPath   = paths.posterPath();   downloaded++; }
                if (paths.logoPath() != null)     { video.logoPath     = paths.logoPath();     downloaded++; }
                if (paths.backdropPath() != null) { video.backdropPath = paths.backdropPath(); downloaded++; }
                if (paths.heroPath() != null)     { video.heroPath     = paths.heroPath();     downloaded++; }
                if (paths.stillPath() != null)    { video.stillPath    = paths.stillPath();    downloaded++; }

                if (downloaded > 0) {
                    entityManager.persist(video);
                    imagesDownloaded += downloaded;
                }
                succeeded++;
            } catch (Exception e) {
                LOGGER.error("Backfill: Failed to process video {}: {}", video.id, e.getMessage());
                failed++;
            }

            processed++;
            if (processed % 50 == 0) {
                LOGGER.info("Backfill: {}/{} videos checked ({} skipped, {} succeeded, {} failed, {} images downloaded)",
                    processed, videos.size(), skipped, succeeded, failed, imagesDownloaded);
            }

            // Rate limit to respect TMDB API limits
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        LOGGER.info("Backfill complete: {}/{} videos checked ({} skipped, {} succeeded, {} failed, {} images downloaded)",
            processed, videos.size(), skipped, succeeded, failed, imagesDownloaded);
    }

    /**
     * Ensure all TMDB media images (poster, logo, backdrop, hero, still) are
     * downloaded for a single video. Independent of generateThumbnail() —
     * works even when the main thumbnail already exists.
     * @return true if any new images were downloaded
     */
    @Transactional
    public boolean ensureMediaImages(Long videoId) {
        try {
            Video video = entityManager.find(Video.class, videoId);
            if (video == null) return false;

            Path thumbnailsDir = getThumbnailDirectory();

            // Check which image files already exist
            boolean hasPoster   = Files.exists(thumbnailsDir.resolve(videoId + "_poster.webp"))   || Files.exists(thumbnailsDir.resolve(videoId + "_poster.jpg"))   || Files.exists(thumbnailsDir.resolve(videoId + "_poster.png"));
            boolean hasLogo     = Files.exists(thumbnailsDir.resolve(videoId + "_logo.webp"))     || Files.exists(thumbnailsDir.resolve(videoId + "_logo.jpg"))     || Files.exists(thumbnailsDir.resolve(videoId + "_logo.png"));
            boolean hasBackdrop = Files.exists(thumbnailsDir.resolve(videoId + "_backdrop.webp")) || Files.exists(thumbnailsDir.resolve(videoId + "_backdrop.jpg")) || Files.exists(thumbnailsDir.resolve(videoId + "_backdrop.png"));
            boolean hasHero     = Files.exists(thumbnailsDir.resolve(videoId + "_hero.webp"))     || Files.exists(thumbnailsDir.resolve(videoId + "_hero.jpg"))     || Files.exists(thumbnailsDir.resolve(videoId + "_hero.png"));

            // Sync DB paths from existing files (files may have been downloaded by
            // a different code path without persisting the path to the DB)
            boolean dbUpdated = false;
            if (hasPoster && video.posterPath == null)     { video.posterPath   = findExistingPath(thumbnailsDir, videoId, "poster");   if (video.posterPath != null) dbUpdated = true; }
            if (hasLogo && (video.logoPath == null || video.logoPath.startsWith("http"))) { video.logoPath = findExistingPath(thumbnailsDir, videoId, "logo"); if (video.logoPath != null) dbUpdated = true; }
            if (hasBackdrop && video.backdropPath == null) { video.backdropPath = findExistingPath(thumbnailsDir, videoId, "backdrop"); if (video.backdropPath != null) dbUpdated = true; }
            if (hasHero && video.heroPath == null)         { video.heroPath     = findExistingPath(thumbnailsDir, videoId, "hero");     if (video.heroPath != null) dbUpdated = true; }
            if (dbUpdated) {
                entityManager.persist(video);
            }

            if (hasPoster && hasLogo && hasBackdrop && hasHero) {
                return false; // All present on disk (DB paths now synced)
            }

            String type = video.type != null ? video.type : "movie";
            String title;
            if ("episode".equalsIgnoreCase(type) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                title = video.seriesTitle;
            } else {
                title = video.title != null ? video.title : video.seriesTitle;
            }

            if (title == null || title.isBlank()) return false;

            VideoMetadataService.MediaImages tmdbImages = metadataService.fetchMediaImages(type, title, video.releaseYear,
                video.seriesTitle, video.seasonNumber, video.episodeNumber);
            ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                tmdbImages.posterPath(), tmdbImages.logoPath(),
                tmdbImages.backdropPath(), tmdbImages.heroPath(),
                tmdbImages.stillPath());
            MediaImagePaths paths = downloadMediaImages(videoId, localImages);

            boolean downloaded = false;
            if (paths.posterPath() != null)   { video.posterPath   = paths.posterPath();   downloaded = true; }
            if (paths.logoPath() != null)     { video.logoPath     = paths.logoPath();     downloaded = true; }
            if (paths.backdropPath() != null) { video.backdropPath = paths.backdropPath(); downloaded = true; }
            if (paths.heroPath() != null)     { video.heroPath     = paths.heroPath();     downloaded = true; }
            if (paths.stillPath() != null)    { video.stillPath    = paths.stillPath();    downloaded = true; }

            if (downloaded) {
                entityManager.persist(video);
            }
            return downloaded;
        } catch (Exception e) {
            LOGGER.error("ensureMediaImages failed for video {}: {}", videoId, e.getMessage());
            return false;
        }
    }

    /**
     * Ensures that TMDB media images (poster, logo, backdrop, hero) are
     * fetched and stored locally for a Series. Uses a {@code series_} file
     * prefix to avoid clashing with video thumbnail IDs.
     *
     * @param seriesId the Series database id
     * @return {@code true} if new images were downloaded, {@code false} if
     *         all images already existed or fetching failed
     */
    @Transactional
    public boolean ensureSeriesMediaImages(Long seriesId) {
        try {
            Series series = entityManager.find(Series.class, seriesId);
            if (series == null) return false;

            String title = series.title;
            if (title == null || title.isBlank()) return false;

            Path thumbnailsDir = getThumbnailDirectory();
            String prefix = "series_" + seriesId;

            VideoMetadataService.MediaImages tmdbImages = metadataService.fetchMediaImages("tv", title, series.releaseYear,
                null, null, null);
            ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                tmdbImages.posterPath(), tmdbImages.logoPath(),
                tmdbImages.backdropPath(), tmdbImages.heroPath(),
                tmdbImages.stillPath());

            MediaImagePaths paths = downloadSeriesMediaImages(seriesId, localImages);

            boolean downloaded = paths.posterPath() != null || paths.logoPath() != null
                || paths.backdropPath() != null || paths.heroPath() != null;

            boolean dbUpdated = false;
            String posterPath   = findExistingPath(thumbnailsDir, prefix, "poster");
            String logoPath     = findExistingPath(thumbnailsDir, prefix, "logo");
            String backdropPath = findExistingPath(thumbnailsDir, prefix, "backdrop");
            String heroPath     = findExistingPath(thumbnailsDir, prefix, "hero");

            if (!Objects.equals(series.posterPath, posterPath))     { series.posterPath   = posterPath;   dbUpdated = true; }
            if (!Objects.equals(series.logoPath, logoPath))         { series.logoPath     = logoPath;     dbUpdated = true; }
            if (!Objects.equals(series.backdropPath, backdropPath)) { series.backdropPath = backdropPath; dbUpdated = true; }
            if (!Objects.equals(series.heroPath, heroPath))         { series.heroPath     = heroPath;     dbUpdated = true; }

            if (dbUpdated) entityManager.persist(series);

            try {
                metadataService.enrichSeriesTextMetadataAsync(seriesId);
            } catch (Exception e) {
                LOGGER.warn("Text metadata enrichment failed for series {}: {}", seriesId, e.getMessage());
            }

            return downloaded;
        } catch (Exception e) {
            LOGGER.error("ensureSeriesMediaImages failed for series {}: {}", seriesId, e.getMessage());
            return false;
        }
    }

    /**
     * Downloads series media images using {@code series_} prefix naming
     * to avoid ID collisions with video thumbnails.
     */
    private MediaImagePaths downloadSeriesMediaImages(Long seriesId, MediaImages images) {
        if (images == null) {
            return new MediaImagePaths(null, null, null, null, null);
        }

        Path thumbnailsDir = getThumbnailDirectory();
        String prefix = "series_" + seriesId;

        String posterPath   = downloadAndConvertImage(prefix, "poster",   images.posterUrl(),   thumbnailsDir);
        String logoPath     = downloadAndConvertImage(prefix, "logo",     images.logoUrl(),     thumbnailsDir);
        String backdropPath = downloadAndConvertImage(prefix, "backdrop", images.backdropUrl(), thumbnailsDir);
        String heroPath     = downloadAndConvertImage(prefix, "hero",     images.heroUrl(),     thumbnailsDir);

        return new MediaImagePaths(posterPath, logoPath, backdropPath, heroPath, null);
    }

    /**
     * String-prefix variant of {@link #downloadAndConvertImage(Long, String, Optional, Path)}.
     * Uses a free-form prefix (e.g. {@code "series_123"}) instead of a numeric video ID.
     */
    private String downloadAndConvertImage(String idPrefix, String type, Optional<String> urlOpt, Path thumbnailsDir) {
        if (urlOpt == null || urlOpt.isEmpty()) {
            return null;
        }
        String url = urlOpt.get();
        if (url == null || url.isBlank()) {
            return null;
        }

        boolean isLogo = "logo".equals(type);

        // Logos use PNG directly (WebP writer can't handle alpha-transparent ARGB)
        if (isLogo) {
            Path pngPath = thumbnailsDir.resolve(idPrefix + "_logo.png");
            if (Files.exists(pngPath)) {
                return pngPath.toAbsolutePath().toString();
            }
        }

        Path webpPath = thumbnailsDir.resolve(idPrefix + "_" + type + ".webp");
        if (Files.exists(webpPath)) {
            return webpPath.toAbsolutePath().toString();
        }

        byte[] rawBytes = downloadBytes(url);
        if (rawBytes == null || rawBytes.length == 0) {
            LOGGER.warn("Failed to download {} image for {}: {}", type, idPrefix, url);
            return null;
        }

        // Logos: save as PNG directly (preserve alpha transparency)
        if (isLogo) {
            try {
                Path pngPath = thumbnailsDir.resolve(idPrefix + "_logo.png");
                Files.write(pngPath, rawBytes);
                LOGGER.info("Saved logo image as PNG for {} ({} bytes)", idPrefix, rawBytes.length);
                return pngPath.toAbsolutePath().toString();
            } catch (IOException e) {
                LOGGER.error("Failed to save logo PNG for {}: {}", idPrefix, e.getMessage());
                return null;
            }
        }

        try {
            writeWebP(rawBytes, webpPath, false);
            LOGGER.info("Saved {} image as WebP for {} ({} bytes)", type, idPrefix, Files.size(webpPath));
            return webpPath.toAbsolutePath().toString();
        } catch (Exception e) {
            LOGGER.warn("WebP conversion failed for {} image {}, falling back to original format: {}",
                    type, idPrefix, e.getMessage());
        }

        try {
            String ext = guessExtension(url);
            Path fallbackPath = thumbnailsDir.resolve(idPrefix + "_" + type + ext);
            Files.write(fallbackPath, rawBytes);
            LOGGER.info("Saved {} image as {} for {} (fallback, {} bytes)", type, ext, idPrefix, rawBytes.length);
            return fallbackPath.toAbsolutePath().toString();
        } catch (IOException e) {
            LOGGER.error("Failed to save fallback {} image for {}: {}", type, idPrefix, e.getMessage());
            return null;
        }
    }

    /**
     * String-prefix variant of {@link #findExistingPath(Path, Long, String)}.
     */
    private String findExistingPath(Path thumbnailsDir, String idPrefix, String type) {
        for (String ext : new String[]{".webp", ".png", ".jpg"}) {
            Path p = thumbnailsDir.resolve(idPrefix + "_" + type + ext);
            if (Files.exists(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private String findExistingPath(Path thumbnailsDir, Long videoId, String type) {
        for (String ext : new String[]{".webp", ".png", ".jpg"}) {
            Path p = thumbnailsDir.resolve(videoId + "_" + type + ext);
            if (Files.exists(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
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
        if (path != null && Files.exists(Paths.get(path))) {
            return true;
        }
        // Check canonical name
        Video video = entityManager.find(Video.class, videoId);
        if (video != null) {
            String canonicalName = MediaPathResolver.resolveThumbnailName(video);
            if (canonicalName != null && Files.exists(getThumbnailDirectory().resolve(canonicalName))) {
                return true;
            }
        }
        // Legacy fallback
        String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
        return Files.exists(getThumbnailDirectory().resolve(legacyName));
    }
    
    @Transactional
    public void deleteThumbnail(Long videoId) {
        try {
            String thumbnailPath = thumbnailCache.remove(videoId);
            if (thumbnailPath != null && Files.exists(Paths.get(thumbnailPath))) {
                Files.deleteIfExists(Paths.get(thumbnailPath));
            }
            // Also try to delete by canonical and legacy names
            Video video = entityManager.find(Video.class, videoId);
            if (video != null) {
                String canonicalName = MediaPathResolver.resolveThumbnailName(video);
                if (canonicalName != null) {
                    Files.deleteIfExists(getThumbnailDirectory().resolve(canonicalName));
                }
            }
            String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
            Files.deleteIfExists(getThumbnailDirectory().resolve(legacyName));
        } catch (IOException e) {
            LOGGER.error("Error deleting thumbnail: " + e.getMessage());
        }
    }
    
    /**
     * Rename an existing thumbnail file when external IDs are obtained after enrichment.
     * Called after fetchAndEnrichMetadata discovers new imdbId/tmdbId/tvdbId.
     */
    @Transactional
    public void renameForExternalIds(Long videoId) {
        try {
            Video video = entityManager.find(Video.class, videoId);
            if (video == null) return;

            String canonicalName = MediaPathResolver.resolveThumbnailName(video);
            if (canonicalName == null) return;

            String currentPath = video.thumbnailPath;
            if (currentPath != null && currentPath.endsWith(canonicalName)) {
                return; // Already using canonical name
            }

            Path thumbnailDir = getThumbnailDirectory();
            Path canonicalPath = thumbnailDir.resolve(canonicalName);

            // If canonical file already exists, nothing to do
            if (Files.exists(canonicalPath)) {
                thumbnailCache.put(videoId, canonicalPath.toString());
                if (!canonicalPath.toString().equals(currentPath)) {
                    video.setThumbnailPath(canonicalPath.toString());
                }
                return;
            }

            // Try to find and rename from legacy name
            String legacyName = MediaPathResolver.legacyThumbnailName(videoId);
            Path legacyPath = thumbnailDir.resolve(legacyName);
            if (Files.exists(legacyPath)) {
                Files.move(legacyPath, canonicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Renamed thumbnail {} -> {} after enrichment", legacyName, canonicalName);
                thumbnailCache.put(videoId, canonicalPath.toString());
                video.setThumbnailPath(canonicalPath.toString());
                return;
            }

            // Check if there's a stale slug-based name
            if (currentPath != null && currentPath.contains(THUMBNAIL_DIR)) {
                Path currentFilePath = Paths.get(currentPath);
                if (Files.exists(currentFilePath) && !currentFilePath.equals(canonicalPath)) {
                    Files.move(currentFilePath, canonicalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Renamed thumbnail {} -> {} after enrichment", currentFilePath.getFileName(), canonicalName);
                    thumbnailCache.put(videoId, canonicalPath.toString());
                    video.setThumbnailPath(canonicalPath.toString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error renaming thumbnail for video {}: {}", videoId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        LOGGER.info("Shutting down ThumbnailService executor");
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

    /**
     * Return value of {@link #downloadMediaImages} holding the four local
     * WebP paths written to the thumbnails directory.
     */
    public record MediaImagePaths(
        String posterPath,
        String logoPath,
        String backdropPath,
        String heroPath,
        String stillPath
    ) {}

    /**
     * Compatible local definition matching {@code VideoMetadataService.MediaImages}.
     * Remove once the parallel task lands that record in VideoMetadataService.
     */
    public record MediaImages(
        Optional<String> posterUrl,
        Optional<String> logoUrl,
        Optional<String> backdropUrl,
        Optional<String> heroUrl,
        Optional<String> stillUrl
    ) {}

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Downloads up to four TMDB image sizes for a video, converts them to
     * WebP (quality 85) and saves them into the thumbnails directory.
     * <p>
     * Files that already exist on disk are skipped.
     * If WebP conversion fails, the raw bytes are stored with the original
     * extension as a fallback.
     *
     * @param videoId the video's database id (used in file names)
     * @param images  the TMDB image URLs (null/empty entries are skipped)
     * @return local paths for each downloaded image (null for skipped/failed)
     */
    public MediaImagePaths downloadMediaImages(Long videoId, MediaImages images) {
        if (images == null) {
            return new MediaImagePaths(null, null, null, null, null);
        }

        Path thumbnailsDir = getThumbnailDirectory();

        String posterPath   = downloadAndConvertImage(videoId, "poster",   images.posterUrl(),   thumbnailsDir);
        String logoPath     = downloadAndConvertImage(videoId, "logo",     images.logoUrl(),     thumbnailsDir);
        String backdropPath = downloadAndConvertImage(videoId, "backdrop", images.backdropUrl(), thumbnailsDir);
        String heroPath     = downloadAndConvertImage(videoId, "hero",     images.heroUrl(),     thumbnailsDir);
        String stillPath    = downloadAndConvertImage(videoId, "still",    images.stillUrl(),    thumbnailsDir);

        return new MediaImagePaths(posterPath, logoPath, backdropPath, heroPath, stillPath);
    }

    private String downloadAndConvertImage(Long videoId, String type, Optional<String> urlOpt, Path thumbnailsDir) {
        if (urlOpt == null || urlOpt.isEmpty()) {
            return null;
        }
        String url = urlOpt.get();
        if (url == null || url.isBlank()) {
            return null;
        }

        boolean isLogo = "logo".equals(type);

        // Logos use PNG directly (WebP writer can't handle alpha-transparent ARGB)
        if (isLogo) {
            Path pngPath = thumbnailsDir.resolve(videoId + "_logo.png");
            if (Files.exists(pngPath)) {
                return pngPath.toAbsolutePath().toString();
            }
        }

        Path webpPath = thumbnailsDir.resolve(videoId + "_" + type + ".webp");

        if (Files.exists(webpPath)) {
            return webpPath.toAbsolutePath().toString();
        }

        byte[] rawBytes = downloadBytes(url);
        if (rawBytes == null || rawBytes.length == 0) {
            LOGGER.warn("Failed to download {} image for video {}: {}", type, videoId, url);
            return null;
        }

        // Logos: save as PNG directly (preserve alpha transparency)
        if (isLogo) {
            try {
                Path pngPath = thumbnailsDir.resolve(videoId + "_logo.png");
                Files.write(pngPath, rawBytes);
                LOGGER.info("Saved logo image as PNG for video {} ({} bytes)", videoId, rawBytes.length);
                return pngPath.toAbsolutePath().toString();
            } catch (IOException e) {
                LOGGER.error("Failed to save logo PNG for video {}: {}", videoId, e.getMessage());
                return null;
            }
        }

        try {
            writeWebP(rawBytes, webpPath, false);
            LOGGER.info("Saved {} image as WebP for video {} ({} bytes)", type, videoId, Files.size(webpPath));
            return webpPath.toAbsolutePath().toString();
        } catch (Exception e) {
            LOGGER.warn("WebP conversion failed for {} image video {}, falling back to original format: {}",
                    type, videoId, e.getMessage());
        }

        try {
            String ext = guessExtension(url);
            Path fallbackPath = thumbnailsDir.resolve(videoId + "_" + type + ext);
            Files.write(fallbackPath, rawBytes);
            LOGGER.info("Saved {} image as {} for video {} (fallback, {} bytes)", type, ext, videoId, rawBytes.length);
            return fallbackPath.toAbsolutePath().toString();
        } catch (IOException e) {
            LOGGER.error("Failed to save fallback {} image for video {}: {}", type, videoId, e.getMessage());
            return null;
        }
    }

    private byte[] downloadBytes(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .header("User-Agent", "JMedia/1.0 (Thumbnail Downloader)")
                    .header("Accept", "image/*")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            } else if (response.statusCode() == 301 || response.statusCode() == 302) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null && !location.equals(imageUrl)) {
                    return downloadBytes(location);
                }
            }
            LOGGER.warn("HTTP {} downloading image: {}", response.statusCode(), imageUrl);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted downloading image: {}", imageUrl);
            return null;
        } catch (Exception e) {
            LOGGER.warn("Error downloading image {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private void writeWebP(byte[] rawBytes, Path outputPath) throws IOException {
        writeWebP(rawBytes, outputPath, false);
    }

    private void writeWebP(byte[] rawBytes, Path outputPath, boolean preserveAlpha) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(rawBytes));
        if (image == null) {
            throw new IOException("Could not decode image from bytes");
        }

        // Only strip alpha when NOT preserving it (logos need transparency)
        if (!preserveAlpha && (image.getType() == BufferedImage.TYPE_INT_ARGB || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)) {
            BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = rgbImage.createGraphics();
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();
            image = rgbImage;
        }

        ImageWriter writer = null;
        ImageOutputStream ios = null;
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
            if (writers.hasNext()) {
                writer = writers.next();
                ios = ImageIO.createImageOutputStream(Files.newOutputStream(outputPath));
                writer.setOutput(ios);

                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType(param.getCompressionTypes()[0]);
                    param.setCompressionQuality(0.85f);
                }

                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            } else {
                throw new IOException("No WebP ImageWriter available – webp-imageio SPI not registered");
            }
        } finally {
            if (ios != null) {
                ios.close();
            }
            if (writer != null) {
                writer.dispose();
            }
        }
    }

    private static String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".gif")) return ".gif";
        return ".jpg";
    }
}