package API.Rest;

import API.ApiResponse;
import Models.DTOs.PaginatedMovieResponse;
import Services.SettingsService;
import Services.ThumbnailService;
import Services.Thumbnail.ThumbnailProcessingStatus;
import Services.VideoImportService;
import Services.VideoService;
import Services.MediaPreProcessor;
import Models.PendingMedia;
import Controllers.NamingController;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ThreadFactory;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

@Path("/api/video")
public class VideoAPI {

    private static final Logger LOG = LoggerFactory.getLogger(VideoAPI.class);

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoImportService videoImportService;

    @Inject
    ManagedExecutor executor;
    
    private static final ThreadFactory scanThreadFactory = r -> {
        Thread thread = new Thread(r);
        thread.setName("VideoScanThread-" + System.currentTimeMillis());
        thread.setDaemon(true);
        return thread;
    };
    
    @Inject
    Services.UnifiedVideoEntityCreationService unifiedVideoEntityCreationService;
    
    @Inject
    ThumbnailService thumbnailService;
    
    @Inject
    NamingController namingController;

    @GET
    @Path("/thumbnail/{videoId}")
    @Produces("image/jpeg")
    public Response getThumbnail(@PathParam("videoId") Long videoId) {
        // Validate videoId parameter
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
                LOG.error("Video library path is not configured.");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }

            // Validate and sanitize the video path to prevent directory traversal
            if (video.path == null || video.path.trim().isEmpty()) {
                LOG.error("Invalid video path: null or empty for video ID: {}", videoId);
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            // Normalize the path and remove any directory traversal attempts
            String normalizedVideoPath = video.path.replace("..", "").replace("//", "/");
            // Remove any leading slashes to prevent absolute paths
            normalizedVideoPath = normalizedVideoPath.replaceFirst("^[/\\\\]+", "");

            String fullPath = java.nio.file.Paths.get(videoLibraryPath, normalizedVideoPath).toString();
            String thumbnailUrl = thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);
            
            if (thumbnailUrl != null && !thumbnailUrl.contains("picsum.photos")) {
                // Convert URL to file path
                String filename = videoId + "_" + video.type + ".jpg";
                java.nio.file.Path thumbnailPath = thumbnailService.getThumbnailDirectory().resolve(filename);
                File thumbnailFile = thumbnailPath.toFile();
                
                if (thumbnailFile.exists()) {
                    return Response.ok(thumbnailFile)
                            .header("Content-Type", "image/jpeg")
                            .header("Cache-Control", "public, max-age=86400") // Cache for 24 hours
                            .header("ETag", "\"" + thumbnailFile.lastModified() + "\"")
                            .build();
                }
            }
            
            // Fallback to placeholder
            String redirectUrl = "https://picsum.photos/seed/video" + videoId + "/300/450.jpg";
            return Response.temporaryRedirect(java.net.URI.create(redirectUrl)).build();
            
        } catch (Exception e) {
            LOG.error("Error serving thumbnail for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @Path("/thumbnail/batch")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBatchThumbnails(@QueryParam("ids") String videoIds) {
        try {
            if (videoIds == null || videoIds.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Video IDs are required"))
                        .build();
            }

            String[] idArray = videoIds.split(",");
            java.util.List<String> thumbnailUrls = new java.util.ArrayList<>();
            
            for (String idStr : idArray) {
                try {
                    Long videoId = Long.parseLong(idStr.trim());
                    Models.Video video = Models.Video.findById(videoId);
                    
                    if (video != null) {
                        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                        if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                            // Validate and sanitize the video path to prevent directory traversal
                            if (video.path == null || video.path.trim().isEmpty()) {
                                LOG.error("Invalid video path: null or empty for video ID: {}", videoId);
                                thumbnailUrls.add("https://picsum.photos/seed/error" + videoId + "/300/450.jpg");
                                continue;
                            }

                            // Normalize the path and remove any directory traversal attempts
                            String normalizedVideoPath = video.path.replace("..", "").replace("//", "/");
                            // Remove any leading slashes to prevent absolute paths
                            normalizedVideoPath = normalizedVideoPath.replaceFirst("^[/\\\\]+", "");

                            String fullPath = java.nio.file.Paths.get(videoLibraryPath, normalizedVideoPath).toString();
                            String thumbnailUrl = thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);
                            thumbnailUrls.add(thumbnailUrl != null ? thumbnailUrl : "https://picsum.photos/seed/video" + videoId + "/300/450.jpg");
                        } else {
                            thumbnailUrls.add("https://picsum.photos/seed/video" + videoId + "/300/450.jpg");
                        }
                    } else {
                        thumbnailUrls.add("https://picsum.photos/seed/missing" + videoId + "/300/450.jpg");
                    }
                } catch (NumberFormatException e) {
                    thumbnailUrls.add("https://picsum.photos/seed/error" + idStr + "/300/450.jpg");
                }
            }
            
            return Response.ok(ApiResponse.success(thumbnailUrls)).build();
            
        } catch (Exception e) {
            LOG.error("Error serving batch thumbnails", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to process batch thumbnail request"))
                    .build();
        }
    }

    @GET
    @Path("/stream/{videoId}")
    @Produces("video/mp4")
    public Response streamVideo(@PathParam("videoId") Long videoId, @HeaderParam("Range") String rangeHeader) {
        // Validate videoId parameter
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid video ID").build();
        }

        Models.Video video = Models.Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            LOG.error("Video library path is not configured.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Video library path not configured.")
                    .build();
        }

        // Validate and sanitize the video path to prevent directory traversal
        if (video.path == null || video.path.trim().isEmpty()) {
            LOG.error("Invalid video path: null or empty for video ID: {}", videoId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid video path").build();
        }

        // Normalize the path and remove any directory traversal attempts
        String normalizedVideoPath = video.path.replace("..", "").replace("//", "/");
        // Remove any leading slashes to prevent absolute paths
        normalizedVideoPath = normalizedVideoPath.replaceFirst("^[/\\\\]+", "");

        java.nio.file.Path filePath = Paths.get(videoLibraryPath, normalizedVideoPath);
        File videoFile = filePath.toFile();

        // Additional security check: ensure the resolved file is within the video library
        try {
            java.nio.file.Path canonicalLibraryPath = Paths.get(videoLibraryPath).toRealPath();
            java.nio.file.Path canonicalFilePath = filePath.toRealPath();
            
            if (!canonicalFilePath.startsWith(canonicalLibraryPath)) {
                LOG.warn("Path traversal attempt detected for video ID {}: {} resolves to {}", 
                         videoId, video.path, canonicalFilePath);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("Access denied: invalid file path").build();
            }
        } catch (IOException e) {
            LOG.warn("Error resolving canonical paths for security check: {}", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Security validation failed").build();
        }

        if (!videoFile.exists() || !videoFile.isFile()) {
            LOG.warn("Video file not found: {}", filePath);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long fileLength = videoFile.length();
        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] parts = rangeHeader.replace("bytes=", "").split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                }
            } catch (Exception e) {
                LOG.warn("Invalid Range header '{}': {}", rangeHeader, e.getMessage());
            }
        }

        long contentLength = end - start + 1;
        final long rangeStart = start;

        StreamingOutput stream = outputStream -> {
            try (InputStream fis = new FileInputStream(videoFile)) {
                fis.skip(rangeStart);
                byte[] buffer = new byte[8192];
                long bytesRemaining = contentLength;
                while (bytesRemaining > 0) {
                    int bytesToRead = (int) Math.min(buffer.length, bytesRemaining);
                    int bytesRead = fis.read(buffer, 0, bytesToRead);
                    if (bytesRead == -1) break;
                    outputStream.write(buffer, 0, bytesRead);
                    bytesRemaining -= bytesRead;
                }
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Streaming error for {}: {}", filePath, e.getMessage(), e);
                }
            }
        };

        if (rangeHeader != null) {
            return Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(stream)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", contentLength)
                    .header("Content-Range", "bytes " + rangeStart + "-" + end + "/" + fileLength)
                    .build();
        }

        return Response.ok(stream)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", "video/mp4")
                .header("Content-Length", fileLength)
                .build();
    }

    @POST
    @Path("/scan")
    public Response scanVideoLibrary() {
        // Use managed executor instead of manual thread creation to maintain CDI context
        executor.submit(() -> {
            // Activate CDI context for this background thread
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }
            
            String threadName = Thread.currentThread().getName();
            try {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    LOG.info("{}: Starting background scan of library: {}", threadName, videoLibraryPath);
                    
                    // Phase 1: Scan and create MediaFile/PendingMedia records
                    LOG.info("{}: Phase 1 - Scanning files in: {}", threadName, videoLibraryPath);
                    videoImportService.scan(Paths.get(videoLibraryPath), false);
                    LOG.info("{}: Phase 1 completed. File scanning finished", threadName);
                    
                    // Phase 2: Smart processing of PendingMedia
                    try {
                        // Give scan time to complete and transactions to settle
                        Thread.sleep(2000);
                        LOG.info("{}: Phase 2 - Smart processing pending media items", threadName);
                        namingController.processAllPendingMedia();
                        LOG.info("{}: Phase 2 completed. Smart processing finished", threadName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn("{}: Smart processing interrupted", threadName);
                    } catch (Exception e) {
                        LOG.error("{}: Error during smart processing: {}", threadName, e.getMessage(), e);
                    }
                    
                    // Phase 3: Entity creation from completed PendingMedia (MISSING PREVIOUSLY)
                    try {
                        // Give smart processing time to complete
                        Thread.sleep(1000);
                        LOG.info("{}: Phase 3 - Importing existing videos", threadName);
                        unifiedVideoEntityCreationService.importExistingVideos();
                        LOG.info("{}: Phase 3 completed. Import finished", threadName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn("{}: Entity creation interrupted", threadName);
                    } catch (Exception e) {
                        LOG.error("{}: Error during entity creation: {}", threadName, e.getMessage(), e);
                    }
                    
                    LOG.info("{}: All phases completed successfully", threadName);
                } else {
                    LOG.warn("{}: Video library path is not configured or is blank", threadName);
                }
            } catch (Exception e) {
                LOG.error("{}: Critical error during video library scan: {}", threadName, e.getMessage(), e);
            } finally {
                // Deactivate CDI context
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });
        
        return Response.ok(ApiResponse.success("Video library scan started in background")).build();
    }

    @POST
    @Path("/reload-metadata")
    public Response reloadVideoMetadata() {
        executor.submit(() -> {
            try {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    // Phase 1: Fast metadata scan
                    videoImportService.scan(Paths.get(videoLibraryPath), true);
                    
                    // Phase 2: Process smart naming after full scan completes
                    try {
                        Thread.sleep(3000); // Give scan time to complete and transactions to settle
                        namingController.processAllPendingMedia();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        LOG.error("Error during smart processing after metadata reload", e);
                    }
                    
                    // Phase 3: Separate thumbnail regeneration (delete existing and regenerate)
                    thumbnailService.queueAllVideosForRegeneration();
                }
            } catch (Exception e) {
                LOG.error("Error during metadata reload and thumbnail regeneration", e);
            }
        });
        return Response.ok(ApiResponse.success("Full video library scan and thumbnail regeneration started")).build();
    }

    @POST
    @Path("/reset-database")
    public Response resetVideoDatabase() {
        videoImportService.resetVideoDatabase();
        return Response.ok(ApiResponse.success("Video database has been reset.")).build();
    }

    @POST
    @Path("/thumbnail/{videoId}/fetch")
    public Response fetchThumbnail(@PathParam("videoId") Long videoId) {
        // Validate videoId parameter
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid video ID")).build();
        }

        executor.submit(() -> {
            try {
                Models.Video video = Models.Video.findById(videoId);
                if (video != null) {
                    String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                    if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                        // Validate and sanitize the video path to prevent directory traversal
                        if (video.path == null || video.path.trim().isEmpty()) {
                            LOG.error("Invalid video path: null or empty for video ID: {}", videoId);
                            return;
                        }

                        // Normalize the path and remove any directory traversal attempts
                        String normalizedVideoPath = video.path.replace("..", "").replace("//", "/");
                        // Remove any leading slashes to prevent absolute paths
                        normalizedVideoPath = normalizedVideoPath.replaceFirst("^[/\\\\]+", "");

                        String fullPath = java.nio.file.Paths.get(videoLibraryPath, normalizedVideoPath).toString();
                        thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error fetching thumbnail for video ID: " + videoId, e);
            }
        });
        return Response.ok(ApiResponse.success("Thumbnail fetch started for video ID: " + videoId)).build();
    }

    @POST
    @Path("/regenerate-thumbnails")
    public Response regenerateThumbnails() {
        executor.submit(() -> {
            try {
                thumbnailService.queueAllVideosForRegeneration();
            } catch (Exception e) {
                LOG.error("Error during thumbnail regeneration", e);
            }
        });
        return Response.ok(ApiResponse.success("Thumbnail regeneration started for all videos")).build();
    }

    @POST
    @Path("/create-entities")
    public Response createVideoEntities() {
        executor.submit(() -> {
            try {
                unifiedVideoEntityCreationService.importExistingVideos();
                LOG.info("Video import process completed");
            } catch (Exception e) {
                LOG.error("Error during video import", e);
            }
        });
        return Response.ok(ApiResponse.success("Video entity creation started")).build();
    }

    @GET
    @Path("/entity-stats")
    public Response getEntityCreationStats() {
        try {
            String stats = "Entity creation service is running";
            return Response.ok(ApiResponse.success(stats)).build();
        } catch (Exception e) {
            LOG.error("Error getting entity creation stats", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get entity stats: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/thumbnail-status")
    public Response getThumbnailProcessingStatus() {
        try {
            Services.Thumbnail.ThumbnailProcessingStatus status = thumbnailService.getProcessingStatus();
            return Response.ok(ApiResponse.success(status)).build();
        } catch (Exception e) {
            LOG.error("Error getting thumbnail processing status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get thumbnail status: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/thumbnail/{videoId}/extract")
    public Response extractThumbnail(@PathParam("videoId") Long videoId) {
        // Validate videoId parameter
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid video ID")).build();
        }

        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found")).build();
            }

            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(ApiResponse.error("Video library path not configured")).build();
            }

            // Validate and sanitize the video path to prevent directory traversal
            if (video.path == null || video.path.trim().isEmpty()) {
                LOG.error("Invalid video path: null or empty for video ID: {}", videoId);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Invalid video path")).build();
            }

            // Normalize the path and remove any directory traversal attempts
            String normalizedVideoPath = video.path.replace("..", "").replace("//", "/");
            // Remove any leading slashes to prevent absolute paths
            normalizedVideoPath = normalizedVideoPath.replaceFirst("^[/\\\\]+", "");

            String fullPath = java.nio.file.Paths.get(videoLibraryPath, normalizedVideoPath).toString();
            
            // Delete existing thumbnail first to force regeneration
            thumbnailService.deleteExistingThumbnail(videoId.toString(), video.type);
            
            // Extract new thumbnail
            String thumbnailUrl = thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);
            
            if (thumbnailUrl != null && !thumbnailUrl.contains("picsum.photos")) {
                return Response.ok(ApiResponse.success(thumbnailUrl)).build();
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(ApiResponse.error("Failed to extract thumbnail")).build();
            }
        } catch (Exception e) {
            LOG.error("Error extracting thumbnail for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error extracting thumbnail: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/metadata/{videoId}/reload")
    public Response reloadVideoMetadata(@PathParam("videoId") Long videoId) {
        // Validate videoId parameter
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid video ID")).build();
        }

        executor.submit(() -> {
            try {
                Models.Video video = Models.Video.findById(videoId);
                if (video != null) {
                    String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                    if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                        // For now, we'll trigger a full scan. In the future, we could implement single file processing
                        videoImportService.scan(java.nio.file.Paths.get(videoLibraryPath), true);
                        
                        // Process smart naming after scan completes
                        try {
                            Thread.sleep(3000); // Give scan time to complete and transactions to settle
                            namingController.processAllPendingMedia();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            LOG.error("Error during smart processing after metadata reload", e);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error reloading metadata for video ID: " + videoId, e);
            }
        });
        return Response.ok(ApiResponse.success("Metadata reload started for video ID: " + videoId)).build();
    }

    @GET
    @Path("/videos")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllVideos(@QueryParam("mediaType") String mediaType) {
        List<Models.Video> videos = Models.Video.listAll();
        return Response.ok(videos).build();
    }

    @GET
    @Path("/shows")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllSeriesTitles() {
        List<String> seriesTitles = Models.Video.<Models.Video>list("type", "episode")
            .stream()
            .map(v -> v.seriesTitle)
            .filter(title -> title != null && !title.isBlank())
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.toList());
        return Response.ok(seriesTitles).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSeasonsForSeries(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasonNumbers = Models.Video.<Models.Video>list("type = ?1 and seriesTitle = ?2", "episode", seriesTitle)
            .stream()
            .map(v -> v.seasonNumber)
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.toList());
        return Response.ok(seasonNumbers).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEpisodesForSeason(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<Models.Video> episodes = Models.Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", "episode", seriesTitle, seasonNumber);
        return Response.ok(episodes).build();
    }

    @GET
    @Path("/movies")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllMovies(
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {

        List<Models.Video> movies = Models.Video.<Models.Video>list("type", "movie");
        long totalItems = movies.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);

        PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) movies, page, limit, totalItems, totalPages);
        return Response.ok(response).build();
    }

    // ========== GENRE-BASED ENDPOINTS ==========
    
    @GET
    @Path("/genres")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllGenres() {
        try {
            List<Models.Genre> genres = Models.Genre.list("isActive = true ORDER BY sortOrder, name");
            return Response.ok(ApiResponse.success(genres)).build();
        } catch (Exception e) {
            LOG.error("Error getting genres", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get genres: " + e.getMessage())).build();
        }
    }
    
    @GET
    @Path("/genre/{genreSlug}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosByGenre(
            @PathParam("genreSlug") String genreSlug,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @QueryParam("userId") Long userId) {
        try {
            List<Models.Video> videos = videoService.findByGenre(genreSlug, page, limit);
            
            // Apply personalization if userId provided
            if (userId != null) {
                videos = videoService.personalizeVideoRecommendations(videos, userId);
            }
            
            long totalItems = videoService.countByGenre(genreSlug);
            int totalPages = (int) Math.ceil((double) totalItems / limit);
            
            PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) videos, page, limit, totalItems, totalPages);
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            LOG.error("Error getting videos by genre: " + genreSlug, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get videos by genre: " + e.getMessage())).build();
        }
    }
    
    @GET
    @Path("/genres/multiple")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosByMultipleGenres(
            @QueryParam("genres") List<String> genreSlugs,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @QueryParam("limit") @jakarta.ws.rs.DefaultValue("20") int limit,
            @QueryParam("userId") Long userId) {
        try {
            if (genreSlugs == null || genreSlugs.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("At least one genre must be specified")).build();
            }
            
            List<Models.Video> videos = videoService.findByMultipleGenres(genreSlugs, page, limit);
            
            // Apply personalization if userId provided
            if (userId != null) {
                videos = videoService.personalizeVideoRecommendations(videos, userId);
            }
            
            long totalItems = videoService.countByMultipleGenres(genreSlugs);
            int totalPages = (int) Math.ceil((double) totalItems / limit);
            
            PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) videos, page, limit, totalItems, totalPages);
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            LOG.error("Error getting videos by multiple genres", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get videos by genres: " + e.getMessage())).build();
        }
    }
    
    @GET
    @Path("/genre/{genreSlug}/recommendations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRecommendedByGenre(
            @PathParam("genreSlug") String genreSlug,
            @QueryParam("userId") Long userId,
            @QueryParam("limit") @jakarta.ws.rs.DefaultValue("10") int limit) {
        try {
            if (userId == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("userId is required for recommendations")).build();
            }
            
            List<Models.Video> recommendations = videoService.findRecommendedByGenre(genreSlug, userId);
            
            // Limit results
            if (recommendations.size() > limit) {
                recommendations = recommendations.subList(0, limit);
            }
            
            return Response.ok(ApiResponse.success(recommendations)).build();
        } catch (Exception e) {
            LOG.error("Error getting genre recommendations for: " + genreSlug, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get recommendations: " + e.getMessage())).build();
        }
    }
    
    @GET
    @Path("/carousels/genre")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllGenreCarousels(
            @QueryParam("userId") Long userId,
            @QueryParam("itemsPerGenre") @jakarta.ws.rs.DefaultValue("8") int itemsPerGenre) {
        try {
            java.util.Map<String, List<Models.Video>> carousels = videoService.getAllGenreCarousels(userId, itemsPerGenre);
            return Response.ok(ApiResponse.success(carousels)).build();
        } catch (Exception e) {
            LOG.error("Error getting genre carousels", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get genre carousels: " + e.getMessage())).build();
        }
    }

    private boolean isClientDisconnect(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        msg = msg.toLowerCase();
        return msg.contains("broken pipe")
                || msg.contains("connection reset")
                || msg.contains("connection aborted")
                || msg.contains("stream closed");
    }
}