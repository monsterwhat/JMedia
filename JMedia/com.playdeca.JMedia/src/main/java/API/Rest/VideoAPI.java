package API.Rest;

import API.ApiResponse;
import Models.DTOs.PaginatedMovieResponse;
import Services.SettingsService;
import Services.ThumbnailService;
import Services.VideoImportService;
import Services.VideoService;
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
import jakarta.ws.rs.core.Context;

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

    private boolean checkAdmin(jakarta.ws.rs.core.HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }

        if (sessionId == null) {
            return false;
        }
        Models.Session session = Models.Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return false;
        }

        Models.User user = Models.User.find("username", session.username).firstResult();
        return user != null && "admin".equals(user.getGroupName());
    }

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

            // CHECK FOR CUSTOM THUMBNAIL FIRST
            if (video.thumbnailPath != null && !video.thumbnailPath.isBlank()) {
                File customThumbnail = new File(video.thumbnailPath);
                if (customThumbnail.exists() && customThumbnail.isFile()) {
                    return Response.ok(customThumbnail)
                            .header("Content-Type", "image/jpeg")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + customThumbnail.lastModified() + "\"")
                            .build();
                }
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

            // Safe path resolution: handle both absolute and relative paths stored in DB
            String fullPath;
            java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
            if (vPath.isAbsolute()) {
                fullPath = vPath.toString();
            } else {
                fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
            }

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
                            // Safe path resolution
                            String fullPath;
                            java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                            if (vPath.isAbsolute()) {
                                fullPath = vPath.toString();
                            } else {
                                fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
                            }

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

        // Safe path resolution
        java.nio.file.Path baseFilePath = java.nio.file.Paths.get(video.path);
        final java.nio.file.Path filePath = baseFilePath.isAbsolute()
                ? baseFilePath : java.nio.file.Paths.get(videoLibraryPath, video.path);

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
                    if (bytesRead == -1) {
                        break;
                    }
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

    @Inject
    private Services.VideoHistoryService videoHistoryService;

    @POST
    @Path("/scan")
    public Response scanVideoLibrary(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }

            try {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    LOG.info("Starting per-video library scan: {}", videoLibraryPath);

                    // Phase 1: Discovery
                    List<Models.PendingMedia> discovered = videoImportService.scan(Paths.get(videoLibraryPath), false);

                    LOG.info("Found {} items. Starting individual processing...", discovered.size());

                    // Phase 2 & 3: Process individually
                    for (Models.PendingMedia pending : discovered) {
                        try {
                            // Run in its own context to ensure immediate commit
                            namingController.processPendingMedia(pending.id);
                            
                            // Let the creation service handle its own reload internally within a transaction
                            // to avoid stale data and context issues
                            unifiedVideoEntityCreationService.createVideoFromPendingMedia(pending.id);
                        } catch (Exception e) {
                            LOG.error("Error processing video {}: {}", pending.originalFilename, e.getMessage());
                        }
                    }

                    LOG.info("Full video library scan and processing completed");
                }
            } catch (Exception e) {
                LOG.error("Error during video scan: {}", e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });

        return Response.ok(ApiResponse.success("Video library scan started. Items will appear one by one.")).build();
    }

    @POST
    @Path("/reload-metadata")
    public Response reloadVideoMetadata(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }

            try {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    LOG.info("Starting video metadata reload: {}", videoLibraryPath);

                    List<Models.PendingMedia> discovered = videoImportService.scan(Paths.get(videoLibraryPath), true);

                    for (Models.PendingMedia pending : discovered) {
                        try {
                            namingController.processPendingMedia(pending.id);
                            unifiedVideoEntityCreationService.createVideoFromPendingMedia(pending.id);
                        } catch (Exception e) {
                            LOG.error("Error reloading metadata for {}: {}", pending.originalFilename, e.getMessage());
                        }
                    }

                    thumbnailService.queueAllVideosForRegeneration();
                    LOG.info("Video metadata reload completed");
                }
            } catch (Exception e) {
                LOG.error("Error during metadata reload: {}", e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });
        return Response.ok(ApiResponse.success("Video metadata reload started. Items will be updated one by one.")).build();
    }
    @POST
    @Path("/reset-database")
    public Response resetVideoDatabase(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoImportService.resetVideoDatabase();
        return Response.ok(ApiResponse.success("Video database and history have been reset.")).build();
    }

    @POST
    @Path("/clear-history")
    public Response clearVideoHistory(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoHistoryService.clearHistory();
        return Response.ok(ApiResponse.success("Video playback history cleared")).build();
    }

    @POST
    @Path("/clear-all")
    public Response clearAllVideos(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoImportService.resetVideoDatabase();
        return Response.ok(ApiResponse.success("All video records cleared from database")).build();
    }

    @POST
    @Path("/thumbnail/{videoId}/fetch")
    public Response fetchThumbnail(@PathParam("videoId") Long videoId, @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
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
                        // Safe path resolution
                        String fullPath;
                        java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                        if (vPath.isAbsolute()) {
                            fullPath = vPath.toString();
                        } else {
                            fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
                        }
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
    public Response regenerateThumbnails(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) requestContext.activate();
            try {
                thumbnailService.queueAllVideosForRegeneration();
            } catch (Exception e) {
                LOG.error("Error during thumbnail regeneration", e);
            } finally {
                if (requestContext.isActive()) requestContext.deactivate();
            }
        });
        return Response.ok(ApiResponse.success("Thumbnail regeneration started for all videos")).build();
    }

    @POST
    @Path("/create-entities")
    public Response createVideoEntities(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) requestContext.activate();
            try {
                unifiedVideoEntityCreationService.importExistingVideos();
                LOG.info("Video import process completed");
            } catch (Exception e) {
                LOG.error("Error during video import", e);
            } finally {
                if (requestContext.isActive()) requestContext.deactivate();
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
    @Path("/metadata/{videoId}/reload")
    public Response reloadVideoMetadata(@PathParam("videoId") Long videoId,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        try {
            Models.Video video = Models.Video.findById(videoId);

            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found"))
                        .build();
            }

            executor.submit(() -> {
                ManagedContext requestContext = Arc.container().requestContext();
                if (!requestContext.isActive()) requestContext.activate();
                try {
                    java.nio.file.Path videoPath = Paths.get(video.path);
                    Models.PendingMedia pending = videoImportService.scanSingleFile(videoPath);
                    if (pending != null) {
                        namingController.processPendingMedia(pending.id);
                        unifiedVideoEntityCreationService.createVideoFromPendingMedia(pending.id);
                        LOG.info("Metadata reloaded for video: {}", video.title);
                    }
                } catch (Exception e) {
                    LOG.error("Error during background metadata reload for video {}", videoId, e);
                } finally {
                    if (requestContext.isActive()) requestContext.deactivate();
                }
            });

            return Response.ok(ApiResponse.success("Metadata reload started for video " + video.title)).build();

        } catch (Exception e) {
            LOG.error("Error reloading metadata for video {}", videoId, e);
            return Response.serverError()
                    .entity(ApiResponse.error("Internal server error: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/thumbnail/{videoId}/extract")
    public Response extractThumbnail(@PathParam("videoId") Long videoId,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {

        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }

        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) requestContext.activate();
            try {
                Models.Video video = Models.Video.findById(videoId);
                if (video == null || video.path == null) return;

                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath == null) return;

                java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                String fullPath = vPath.isAbsolute() ? vPath.toString() : java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
                
                thumbnailService.deleteExistingThumbnail(videoId.toString(), video.type);
                thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);
                LOG.info("Thumbnail manually extracted for video: {}", video.title);
            } catch (Exception e) {
                LOG.error("Error extracting thumbnail for video {}", videoId, e);
            } finally {
                if (requestContext.isActive()) requestContext.deactivate();
            }
        });

        return Response.accepted().entity(ApiResponse.success("Thumbnail extraction started")).build();
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
        List<String> seriesTitles = Models.Video.<Models.Video>list("type = ?1", "episode")
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

        List<Models.Video> movies = Models.Video.<Models.Video>list("type = ?1", "movie");
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

    @Inject
    Services.VideoStoryboardService storyboardService;

    @GET
    @Path("/storyboard/{videoId}")
    @Produces("image/jpeg")
    public Response getStoryboard(@PathParam("videoId") Long videoId) {
        File file = storyboardService.getStoryboardImage(videoId);
        if (file == null || !file.exists()) {
            if (storyboardService.isGenerating(videoId)) {
                return Response.status(Response.Status.ACCEPTED).entity("Storyboard is being generated").build();
            }
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(file).build();
    }

    @GET
    @Path("/storyboard/{videoId}/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStoryboardMetadata(@PathParam("videoId") Long videoId) {
        LOG.debug("Storyboard metadata request for video {}", videoId);
        Services.VideoStoryboardService.StoryboardMetadata metadata = storyboardService.getMetadata(videoId);
        if (metadata == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(ApiResponse.success(metadata)).build();
    }

    private boolean isClientDisconnect(Throwable e) {
        if (e == null) return false;
        
        String msg = e.getMessage();
        if (msg != null) {
            String lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("broken pipe") || 
                lowerMsg.contains("connection reset") || 
                lowerMsg.contains("connection aborted") || 
                lowerMsg.contains("stream closed") ||
                lowerMsg.contains("connection has been closed") ||
                lowerMsg.contains("failed to write")) {
                return true;
            }
        }
        
        // Check cause recursively
        return isClientDisconnect(e.getCause());
    }
}
