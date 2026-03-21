package API.Rest;

import API.ApiResponse;
import Models.DTOs.PaginatedMovieResponse;
import Services.SettingsService;
import Services.ThumbnailService;
import Services.TranscodingService;
import Services.VideoImportService;
import Services.VideoService;
import Services.VideoScanExecutor;
import Services.SubtitleDiscoveryQueueProcessor;
import jakarta.inject.Inject;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.context.ManagedExecutor;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.RandomAccessFile;
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
    TranscodingService transcodingService;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoImportService videoImportService;

    @Inject
    ManagedExecutor executor;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    Services.UserInteractionService userInteractionService;

    @Inject
    Services.VideoStateService videoStateService;

    @Inject
    Services.VideoMetadataService videoMetadataService;

    @Inject
    VideoScanExecutor videoScanExecutor;

    @Inject
    SubtitleDiscoveryQueueProcessor subtitleDiscoveryProcessor;

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
    @Path("/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideo(@PathParam("videoId") Long videoId) {
        Models.Video video = Models.Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(API.ApiResponse.error("Video not found")).build();
        }
        return Response.ok(API.ApiResponse.success(new Models.DTOs.VideoMetadataDTO(video))).build();
    }

    @GET
    @Path("/thumbnail/{videoId}")
    @Produces("image/webp")
    public Response getThumbnail(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            if (video.thumbnailPath != null && !video.thumbnailPath.isBlank()) {
                File customThumbnail = new File(video.thumbnailPath);
                if (customThumbnail.exists() && customThumbnail.isFile()) {
                    return Response.ok(customThumbnail)
                            .header("Content-Type", "image/webp")
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

            if (video.path == null || video.path.trim().isEmpty()) {
                LOG.error("Invalid video path for video ID: {}", videoId);
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            String fullPath;
            java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
            if (vPath.isAbsolute()) {
                fullPath = vPath.toString();
            } else {
                fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
            }

            String thumbnailUrl = thumbnailService.getThumbnailPath(fullPath, videoId.toString(), video.type);

            if (thumbnailUrl != null && !thumbnailUrl.contains("picsum.photos")) {
                String filename = videoId + "_" + video.type + ".jpg";
                java.nio.file.Path thumbnailPath = thumbnailService.getThumbnailDirectory().resolve(filename);
                File thumbnailFile = thumbnailPath.toFile();

                if (thumbnailFile.exists()) {
                    return Response.ok(thumbnailFile)
                            .header("Content-Type", "image/webp")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + thumbnailFile.lastModified() + "\"")
                            .build();
                }
            }

            String redirectUrl = "https://picsum.photos/seed/video" + videoId + "/300/450.jpg";
            return Response.temporaryRedirect(java.net.URI.create(redirectUrl)).build();

        } catch (Exception e) {
            LOG.error("Error serving thumbnail for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @Path("/watchlist/toggle/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggleWatchlist(@PathParam("videoId") Long videoId) {
        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found"))
                        .build();
            }

            if (video.favorite) {
                userInteractionService.removeFavorite(videoId, 1L);
                return Response.ok(ApiResponse.success(false)).build();
            } else {
                userInteractionService.markAsFavorite(videoId, 1L);
                return Response.ok(ApiResponse.success(true)).build();
            }
        } catch (Exception e) {
            LOG.error("Error toggling watchlist for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to toggle watchlist")).build();
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

    private String getMimeType(String filename) {
        if (filename == null) return "video/mp4";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    @GET
    @Path("/stream/{videoId}")
    public Response streamVideo(@PathParam("videoId") Long videoId, 
                               @HeaderParam("Range") String rangeHeader,
                               @HeaderParam("User-Agent") String userAgent,
                               @QueryParam("start") @DefaultValue("0") double startSeconds) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid video ID").build();
        }

        Models.Video video = Models.Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            LOG.error("Video library path is not configured.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Video library path not configured.").build();
        }

        java.nio.file.Path baseFilePath = java.nio.file.Paths.get(video.path);
        final java.nio.file.Path filePath = baseFilePath.isAbsolute()
                ? baseFilePath : java.nio.file.Paths.get(videoLibraryPath, video.path);

        File videoFile = filePath.toFile();

        if (!videoFile.exists() || !videoFile.isFile()) {
            LOG.warn("Video file not found: {}", filePath);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String filename = videoFile.getName().toLowerCase();
        boolean isMKV = filename.endsWith(".mkv");
        boolean isIOS = transcodingService.isIOSClient(userAgent);

        if (isMKV) {
            return streamRemuxedMKV(video, videoFile, startSeconds, userAgent);
        }

        if (isIOS) {
            if (video.videoCodec == null || video.audioCodec == null) {
                videoService.probeVideoMetadata(video);
            }
            if (transcodingService.isIOSTranscodeNeeded(video)) {
                return streamRemuxedMKV(video, videoFile, startSeconds, userAgent);
            }
        }

        return streamDirectFile(videoFile, rangeHeader);
    }

    private Response streamRemuxedMKV(Models.Video video, File videoFile, double startSeconds, String userAgent) {
        StreamingOutput streamingOutput = output -> {
            try {
                transcodingService.streamRemuxedMKV(video, videoFile, startSeconds, userAgent, output);
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("MKV Remux streaming error for {}: {}", videoFile.getName(), e.getMessage());
                }
            }
        };

        return Response.ok(streamingOutput)
                .header("Content-Type", "video/mp4")
                .header("Cache-Control", "no-cache")
                .build();
    }

    private Response streamDirectFile(File videoFile, String rangeHeader) {
        long fileLength = videoFile.length();
        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] parts = rangeHeader.replace("bytes=", "").split("-");
                start = Long.parseLong(parts[0]);

                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                } else {
                    end = fileLength - 1;
                }

                if (end >= fileLength) {
                    end = fileLength - 1;
                }
            } catch (Exception e) {
                LOG.warn("Invalid Range header '{}': {}", rangeHeader, e.getMessage());
                start = 0;
                end = fileLength - 1;
            }
        }

        if (start >= fileLength) {
            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + fileLength)
                    .build();
        }

        long contentLength = end - start + 1;
        final long finalStart = start;
        final long finalContentLength = contentLength;
        final String mimeType = getMimeType(videoFile.getName());

        StreamingOutput streamingOutput = output -> {
            try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                raf.seek(finalStart);
                byte[] buffer = new byte[65536];
                long remaining = finalContentLength;
                while (remaining > 0) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) break;
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Streaming error for {}: {}", videoFile.getAbsolutePath(), e.getMessage());
                }
            }
        };

        Response.ResponseBuilder responseBuilder = Response.status(rangeHeader != null ? Response.Status.PARTIAL_CONTENT : Response.Status.OK)
                .entity(streamingOutput)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", mimeType)
                .header("Content-Length", contentLength)
                .header("Cache-Control", "public, max-age=3600");

        if (rangeHeader != null) {
            responseBuilder.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        }

        return responseBuilder.build();
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
                    
                    List<Models.Video> videos = videoImportService.scanAndCreate(Paths.get(videoLibraryPath));
                    
                    LOG.info("Scan and create completed. Created {} videos.", videos.size());
                    
                    // Queue metadata enrichment for background processing
                    executor.submit(() -> videoMetadataService.queueAllVideosForEnrichment());
                    
                    // Queue thumbnails for background processing
                    executor.submit(() -> thumbnailService.queueAllVideosForRegeneration());
                    
                    // Discover subtitle tracks
                    executor.submit(() -> subtitleDiscoveryProcessor.queueAllVideos());
                }
            } catch (Exception e) {
                LOG.error("Error during video scan: {}", e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });

        return Response.ok(ApiResponse.success("Video library scan started.")).build();
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
                    List<Models.Video> videos = videoImportService.scanAndCreate(Paths.get(videoLibraryPath), true);

                    executor.submit(() -> thumbnailService.queueAllVideosForRegeneration());
                    executor.submit(() -> subtitleDiscoveryProcessor.queueAllVideos());
                    LOG.info("Video metadata reload completed. Updated {} videos.", videos.size());
                }
            } catch (Exception e) {
                LOG.error("Error during metadata reload: " + e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });
        return Response.ok(ApiResponse.success("Video metadata reload started.")).build();
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
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid video ID")).build();
        }

        executor.submit(() -> {
            try {
                Models.Video video = Models.Video.findById(videoId);
                if (video != null) {
                    String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                    if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
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
        return Response.ok(ApiResponse.success("Thumbnail fetch started.")).build();
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
        return Response.ok(ApiResponse.success("Thumbnail regeneration started.")).build();
    }

    @GET
    @Path("/scan-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getScanStatus() {
        return Response.ok(ApiResponse.success(videoImportService.getProgress())).build();
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
                    .entity(ApiResponse.error("Failed to get thumbnail status")).build();
        }
    }

    @POST
    @Path("/metadata/{videoId}/reload")
    public Response reloadVideoMetadata(@PathParam("videoId") Long videoId,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Video not found")).build();
            }
            executor.submit(() -> {
                ManagedContext requestContext = Arc.container().requestContext();
                if (!requestContext.isActive()) requestContext.activate();
                try {
                    String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                    java.nio.file.Path vPath = Paths.get(video.path);
                    java.nio.file.Path videoPath = vPath.isAbsolute() ? vPath : Paths.get(videoLibraryPath, video.path);
                    
                    Models.Video result = videoImportService.scanSingleFile(videoPath);
                    if (result != null) {
                        videoMetadataService.fetchAndEnrichMetadata(result);
                    }
                } catch (Exception e) {
                    LOG.error("Error in background reload for video {}", videoId, e);
                } finally {
                    if (requestContext.isActive()) requestContext.deactivate();
                }
            });
            return Response.ok(ApiResponse.success("Metadata reload started.")).build();
        } catch (Exception e) {
            LOG.error("Error reloading metadata for video {}", videoId, e);
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/metadata/series/{seriesTitle}/reload")
    public Response reloadSeriesMetadata(@PathParam("seriesTitle") String seriesTitle,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            List<Models.Video> existingEpisodes = videoService.findEpisodesForSeries(seriesTitle);
            if (existingEpisodes.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Series not found")).build();
            }
            
            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            java.nio.file.Path seriesFolderPath = videoService.getSeriesFolderPath(seriesTitle);
            if (seriesFolderPath == null) {
                return Response.serverError().entity(ApiResponse.error("Could not determine series folder path")).build();
            }
            
            java.nio.file.Path fullSeriesFolder = seriesFolderPath.isAbsolute() 
                ? seriesFolderPath 
                : Paths.get(videoLibraryPath, seriesFolderPath.toString());
            
            executor.submit(() -> {
                ManagedContext requestContext = Arc.container().requestContext();
                if (!requestContext.isActive()) requestContext.activate();
                try {
                    List<Models.Video> discovered = videoImportService.scan(fullSeriesFolder, false, true);
                    Set<String> discoveredPaths = discovered.stream()
                        .map(v -> v.path)
                        .collect(Collectors.toSet());
                    
                    for (Models.Video episode : existingEpisodes) {
                        if (!discoveredPaths.contains(episode.path)) {
                            episode.delete();
                        }
                    }
                    
                    for (Models.Video video : discovered) {
                        try {
                            videoMetadataService.fetchAndEnrichMetadata(video);
                        } catch (Exception e) {
                            LOG.error("Error enriching metadata for {}: {}", video.filename, e.getMessage());
                        }
                    }
                } finally {
                    if (requestContext.isActive()) requestContext.deactivate();
                }
            });
            return Response.ok(ApiResponse.success("Metadata reload started for series.")).build();
        } catch (Exception e) {
            LOG.error("Error reloading series metadata", e);
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/metadata/series/{seriesTitle}/season/{seasonNumber}/reload")
    public Response reloadSeasonMetadata(@PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            List<Models.Video> existingEpisodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
            
            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            java.nio.file.Path seasonFolderPath = videoService.getSeasonFolderPath(seriesTitle, seasonNumber);
            if (seasonFolderPath == null) {
                seasonFolderPath = videoService.getSeasonFolderPathFallback(seriesTitle, seasonNumber);
            }
            if (seasonFolderPath == null) {
                return Response.serverError().entity(ApiResponse.error("Could not determine season folder path")).build();
            }
            
            java.nio.file.Path fullSeasonFolder = seasonFolderPath.isAbsolute() 
                ? seasonFolderPath 
                : Paths.get(videoLibraryPath, seasonFolderPath.toString());
            
            executor.submit(() -> {
                ManagedContext requestContext = Arc.container().requestContext();
                if (!requestContext.isActive()) requestContext.activate();
                try {
                    List<Models.Video> discovered = videoImportService.scan(fullSeasonFolder, false, true);
                    Set<String> discoveredPaths = discovered.stream()
                        .map(v -> v.path)
                        .collect(Collectors.toSet());
                    
                    for (Models.Video episode : existingEpisodes) {
                        if (!discoveredPaths.contains(episode.path)) {
                            episode.delete();
                        }
                    }
                    
                    for (Models.Video video : discovered) {
                        try {
                            videoMetadataService.fetchAndEnrichMetadata(video);
                        } catch (Exception e) {
                            LOG.error("Error enriching metadata for {}: {}", video.filename, e.getMessage());
                        }
                    }
                } finally {
                    if (requestContext.isActive()) requestContext.deactivate();
                }
            });
            return Response.ok(ApiResponse.success("Metadata reload started for season.")).build();
        } catch (Exception e) {
            LOG.error("Error reloading season metadata", e);
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/thumbnail/{videoId}/extract")
    public Response extractThumbnail(@PathParam("videoId") Long videoId,
            @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
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
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        List<Models.Video> movies = Models.Video.<Models.Video>list("type = ?1", "movie");
        long totalItems = movies.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) movies, page, limit, totalItems, totalPages);
        return Response.ok(response).build();
    }

    @GET
    @Path("/genres")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllGenres() {
        try {
            List<Models.Genre> genres = Models.Genre.list("isActive = true ORDER BY sortOrder, name");
            return Response.ok(ApiResponse.success(genres)).build();
        } catch (Exception e) {
            LOG.error("Error getting genres", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get genres")).build();
        }
    }

    @GET
    @Path("/genre/{genreSlug}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosByGenre(
            @PathParam("genreSlug") String genreSlug,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("userId") Long userId) {
        try {
            List<Models.Video> videos = videoService.findByGenre(genreSlug, page, limit);
            if (userId != null) {
                videos = videoService.personalizeVideoRecommendations(videos, userId);
            }
            long totalItems = videoService.countByGenre(genreSlug);
            int totalPages = (int) Math.ceil((double) totalItems / limit);
            PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) videos, page, limit, totalItems, totalPages);
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            LOG.error("Error getting videos by genre: {}", genreSlug, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get videos by genre")).build();
        }
    }

    @GET
    @Path("/genres/multiple")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosByMultipleGenres(
            @QueryParam("genres") List<String> genreSlugs,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("userId") Long userId) {
        try {
            if (genreSlugs == null || genreSlugs.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("At least one genre required")).build();
            }
            List<Models.Video> videos = videoService.findByMultipleGenres(genreSlugs, page, limit);
            if (userId != null) {
                videos = videoService.personalizeVideoRecommendations(videos, userId);
            }
            long totalItems = videoService.countByMultipleGenres(genreSlugs);
            int totalPages = (int) Math.ceil((double) totalItems / limit);
            PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) videos, page, limit, totalItems, totalPages);
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            LOG.error("Error getting videos by multiple genres", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get videos by genres")).build();
        }
    }

    @GET
    @Path("/genre/{genreSlug}/recommendations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRecommendedByGenre(
            @PathParam("genreSlug") String genreSlug,
            @QueryParam("userId") Long userId,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            if (userId == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("userId required")).build();
            }
            List<Models.Video> recommendations = videoService.findRecommendedByGenre(genreSlug, userId);
            if (recommendations.size() > limit) {
                recommendations = recommendations.subList(0, limit);
            }
            return Response.ok(ApiResponse.success(recommendations)).build();
        } catch (Exception e) {
            LOG.error("Error getting genre recommendations for: {}", genreSlug, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get recommendations")).build();
        }
    }

    @GET
    @Path("/carousels/genre")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllGenreCarousels(
            @QueryParam("userId") Long userId,
            @QueryParam("itemsPerGenre") @DefaultValue("8") int itemsPerGenre) {
        try {
            java.util.Map<String, List<Models.Video>> carousels = videoService.getAllGenreCarousels(userId, itemsPerGenre);
            return Response.ok(ApiResponse.success(carousels)).build();
        } catch (Exception e) {
            LOG.error("Error getting genre carousels", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get genre carousels")).build();
        }
    }

    @Inject
    Services.VideoStoryboardService storyboardService;

    @POST
    @Path("/progress/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response reportProgress(@PathParam("videoId") Long videoId, @QueryParam("time") double timeSeconds) {
        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video != null) {
                if (video.duration == null || video.duration <= 0) {
                    videoService.probeVideoDuration(video);
                }

                video.resumeTime = (long) (timeSeconds * 1000);
                video.lastWatched = java.time.LocalDateTime.now();
                video.dateModified = java.time.LocalDateTime.now();
                
                double durationSeconds = video.duration != null ? video.duration / 1000.0 : 0;
                double progressRatio = (durationSeconds > 0) ? Math.min(1.0, timeSeconds / durationSeconds) : 0;
                
                video.watchProgress = progressRatio;
                if (progressRatio >= 0.95) {
                    video.watched = true;
                    video.watchProgress = 1.0;
                } else {
                    video.watched = false;
                }
                
                video.persist();

                try {
                    Models.VideoState state = videoStateService.getOrCreateState();
                    if (state != null) {
                        state.setCurrentVideoId(videoId);
                        state.setCurrentTime(timeSeconds);
                        state.setLastUpdateTime(System.currentTimeMillis());
                        if (video.duration != null) {
                            state.setDuration(video.duration);
                        }
                        videoStateService.saveState(state);
                    }
                } catch (Exception e) {
                    LOG.warn("Could not sync VideoState for video {}: {}", videoId, e.getMessage());
                }
            }
            return Response.ok(ApiResponse.success(null)).build();
        } catch (Exception e) {
            LOG.error("Error reporting progress for video {}: {}", videoId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @Path("/storyboard/{videoId}/tiles")
    @Produces("image/webp")
    public Response getStoryboardTiles(@PathParam("videoId") Long videoId) {
        File file = storyboardService.getStoryboardImage(videoId);
        if (file == null || !file.exists()) {
            if (storyboardService.isGenerating(videoId)) {
                return Response.status(Response.Status.ACCEPTED).entity("Storyboard generating").build();
            }
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(file)
                .header("Content-Type", "image/webp")
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    @GET
    @Path("/storyboard/{videoId}")
    @Produces("image/webp")
    public Response getStoryboard(@PathParam("videoId") Long videoId) {
        File file = storyboardService.getStoryboardImage(videoId);
        if (file == null || !file.exists()) {
            if (storyboardService.isGenerating(videoId)) {
                return Response.status(Response.Status.ACCEPTED).entity("Storyboard generating").build();
            }
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(file).build();
    }

    @GET
    @Path("/storyboard/{videoId}/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStoryboardMetadata(@PathParam("videoId") Long videoId) {
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
            if (lowerMsg.contains("broken pipe") || lowerMsg.contains("connection reset") || lowerMsg.contains("connection aborted") || lowerMsg.contains("stream closed") || lowerMsg.contains("connection has been closed") || lowerMsg.contains("failed to write")) {
                return true;
            }
        }
        return isClientDisconnect(e.getCause());
    }
}
