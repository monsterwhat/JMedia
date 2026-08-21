package API.Rest;

import API.ApiResponse;
import Models.DTOs.ContinueWatchingDTO;
import Models.DTOs.PaginatedMovieResponse;
import Services.AuthService;
import Services.SettingsService;
import Services.ThumbnailService;
import Services.VideoImportService;
import Services.VideoService;
import Services.VideoScanExecutor;
import Services.SubtitleDiscoveryQueueProcessor;
import Services.ExternalVideoService;
import jakarta.inject.Inject;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import Models.Video.Video;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ThreadFactory;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.ws.rs.core.Context;

@Path("/api/video")

public class VideoAPI {

    private static final Logger LOG = LoggerFactory.getLogger(VideoAPI.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoImportService videoImportService;

    @Inject
    ExecutorService executor;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    Services.UserInteractionService userInteractionService;

    @Inject
    Services.VideoStateService videoStateService;

    @Inject
    Services.VideoMetadataService videoMetadataService;

    @Inject
    Services.VideoEnrichmentWorker videoEnrichmentWorker;

    @Inject
    SubtitleDiscoveryQueueProcessor subtitleDiscoveryProcessor;

    @Inject
    ExternalVideoService externalVideoService;

    @Inject
    AuthService authService;

    @GET
    @Path("/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideo(@PathParam("videoId") Long videoId,
                             @QueryParam("textOnly") @DefaultValue("false") boolean textOnly) {
        // On-demand enrichment BEFORE loading the entity
        // so the DTO is built from the enriched version
        if (!textOnly) {
            try {
                thumbnailService.ensureMediaImages(videoId);
            } catch (Exception e) {
                LOG.warn("Could not enrich images for video {}: {}", videoId, e.getMessage());
            }
        }
        try {
            videoMetadataService.ensureMediaTextMetadata(videoId);
        } catch (Exception e) {
            LOG.warn("Could not enrich text metadata for video {}: {}", videoId, e.getMessage());
        }

        Models.Video.Video video = Models.Video.Video.findById(videoId);
        if (video != null && video.series != null) {
            try {
                videoMetadataService.ensureSeriesTextMetadata(video.series.id);
            } catch (Exception e) {
                LOG.warn("Could not enrich series text metadata for video {}: {}", videoId, e.getMessage());
            }
            // Re-load video so the DTO picks up any Series-level changes
            video = Models.Video.Video.findById(videoId);
        }

        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(API.ApiResponse.error("Video not found")).build();
        }
        Models.DTOs.VideoMetadataDTO dto = new Models.DTOs.VideoMetadataDTO(video);
        try {
            if (video.series != null) {
                dto.series = video.series;
            }
        } catch (Exception e) {
            LOG.debug("Could not load series for video {}: {}", videoId, e.getMessage());
        }
        // Populate per-profile resume time from VideoState (single source: VideoService.getResumeTime)
        dto.resumeTime = videoService.getResumeTime(video);
        return Response.ok(API.ApiResponse.success(dto)).build();
    }

    @GET
    @Path("/thumbnail/{videoId}")
    @Produces("image/jpeg")
    public Response getThumbnail(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            Models.Video.Video video = Models.Video.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

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

            // Tier 1.5 - on-demand episode still: fetch the episode's TMDB image,
            // store it locally, and serve it (falls back to show poster below).
            if ("episode".equalsIgnoreCase(video.type) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()
                        && video.path != null && !video.path.trim().isEmpty()) {
                    String fullPath;
                    java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                    if (vPath.isAbsolute()) {
                        fullPath = vPath.toString();
                    } else {
                        fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
                    }
                    try {
                        String episodeThumb = thumbnailService.getOrFetchEpisodeThumbnail(videoId, fullPath);
                        if (episodeThumb != null && java.nio.file.Files.exists(java.nio.file.Paths.get(episodeThumb))) {
                            File episodeThumbFile = java.nio.file.Paths.get(episodeThumb).toFile();
                            return Response.ok(episodeThumbFile)
                                    .header("Content-Type", "image/webp")
                                    .header("Cache-Control", "public, max-age=86400")
                                    .header("ETag", "\"" + episodeThumbFile.lastModified() + "\"")
                                    .build();
                        }
                    } catch (Exception e) {
                        LOG.warn("On-demand episode thumbnail fetch failed for video {}: {}", videoId, e.getMessage());
                    }
                }
            }

            // Tier 2 - TMDB poster as fallback thumbnail (NEW)
            try {
                thumbnailService.ensureMediaImages(videoId);
                java.nio.file.Path posterPath = thumbnailService.getThumbnailDirectory().resolve(videoId + "_poster.webp");
                if (java.nio.file.Files.exists(posterPath)) {
                    File posterFile = posterPath.toFile();
                    return Response.ok(posterFile)
                            .header("Content-Type", "image/webp")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + posterFile.lastModified() + "\"")
                            .build();
                }
                // Try PNG fallback for poster
                java.nio.file.Path posterPng = thumbnailService.getThumbnailDirectory().resolve(videoId + "_poster.png");
                if (java.nio.file.Files.exists(posterPng)) {
                    File posterFile = posterPng.toFile();
                    return Response.ok(posterFile)
                            .header("Content-Type", "image/png")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + posterFile.lastModified() + "\"")
                            .build();
                }
            } catch (Exception e) {
                LOG.warn("TMDB enrichment failed for thumbnail {}: {}", videoId, e.getMessage());
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

            String thumbnailUrl = thumbnailService.getThumbnailPathWithFallback(fullPath, video);

            if (thumbnailUrl != null && Files.exists(java.nio.file.Paths.get(thumbnailUrl))) {
                File thumbnailFile = java.nio.file.Paths.get(thumbnailUrl).toFile();
                return Response.ok(thumbnailFile)
                        .header("Content-Type", "image/jpeg")
                        .header("Cache-Control", "public, max-age=86400")
                        .header("ETag", "\"" + thumbnailFile.lastModified() + "\"")
                        .build();
            }

            return Response.temporaryRedirect(java.net.URI.create("/logo.png")).build();

        } catch (Exception e) {
            LOG.error("Error serving thumbnail for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Serve an image from the thumbnails directory with a configurable fallback chain.
     *
     * Fallback order:
     *   1. {thumbnailsDir}/{videoId}_{imageType}.webp   (generated image)
     *   2. {thumbnailsDir}/{videoId}_{fallbackType}.webp (optional secondary type, e.g. hero→backdrop)
     *   3. Existing thumbnail path from the Video entity (custom thumbnailPath or generated thumbnail)
     *   4. Return 404 (client shows text title fallback)
     */
    private Response serveImageFromThumbnails(Long videoId, String imageType, String fallbackType) {
        try {
            Models.Video.Video video = Models.Video.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            java.nio.file.Path thumbnailsDir = thumbnailService.getThumbnailDirectory();

            java.nio.file.Path primaryPath = thumbnailsDir.resolve(videoId + "_" + imageType + ".webp");
            if (java.nio.file.Files.exists(primaryPath)) {
                File imageFile = primaryPath.toFile();
                return Response.ok(imageFile)
                        .header("Content-Type", "image/webp")
                        .header("Cache-Control", "public, max-age=86400")
                        .header("ETag", "\"" + imageFile.lastModified() + "\"")
                        .build();
            }

            // Check for .png fallback (e.g. alpha-transparent logos that failed WebP)
            java.nio.file.Path pngPath = thumbnailsDir.resolve(videoId + "_" + imageType + ".png");
            if (java.nio.file.Files.exists(pngPath)) {
                File imageFile = pngPath.toFile();
                return Response.ok(imageFile)
                        .header("Content-Type", "image/png")
                        .header("Cache-Control", "public, max-age=86400")
                        .header("ETag", "\"" + imageFile.lastModified() + "\"")
                        .build();
            }

            // Check for .jpg fallback
            java.nio.file.Path jpgPath = thumbnailsDir.resolve(videoId + "_" + imageType + ".jpg");
            if (java.nio.file.Files.exists(jpgPath)) {
                File imageFile = jpgPath.toFile();
                return Response.ok(imageFile)
                        .header("Content-Type", "image/jpeg")
                        .header("Cache-Control", "public, max-age=86400")
                        .header("ETag", "\"" + imageFile.lastModified() + "\"")
                        .build();
            }

            if (fallbackType != null) {
                java.nio.file.Path fallbackPath = thumbnailsDir.resolve(videoId + "_" + fallbackType + ".webp");
                if (java.nio.file.Files.exists(fallbackPath)) {
                    File imageFile = fallbackPath.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/webp")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }

                java.nio.file.Path fbPngPath = thumbnailsDir.resolve(videoId + "_" + fallbackType + ".png");
                if (java.nio.file.Files.exists(fbPngPath)) {
                    File imageFile = fbPngPath.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/png")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }

                java.nio.file.Path fbJpgPath = thumbnailsDir.resolve(videoId + "_" + fallbackType + ".jpg");
                if (java.nio.file.Files.exists(fbJpgPath)) {
                    File imageFile = fbJpgPath.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/jpeg")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }
            }

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

            // Generate on-demand: fetch all TMDB media images for this video
            if (thumbnailService.ensureMediaImages(videoId)) {
                // Re-check for the requested image type after generation
                java.nio.file.Path regeneratedPrimary = thumbnailsDir.resolve(videoId + "_" + imageType + ".webp");
                if (java.nio.file.Files.exists(regeneratedPrimary)) {
                    File imageFile = regeneratedPrimary.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/webp")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }
                java.nio.file.Path regeneratedPng = thumbnailsDir.resolve(videoId + "_" + imageType + ".png");
                if (java.nio.file.Files.exists(regeneratedPng)) {
                    File imageFile = regeneratedPng.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/png")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }
                java.nio.file.Path regeneratedJpg = thumbnailsDir.resolve(videoId + "_" + imageType + ".jpg");
                if (java.nio.file.Files.exists(regeneratedJpg)) {
                    File imageFile = regeneratedJpg.toFile();
                    return Response.ok(imageFile)
                            .header("Content-Type", "image/jpeg")
                            .header("Cache-Control", "public, max-age=86400")
                            .header("ETag", "\"" + imageFile.lastModified() + "\"")
                            .build();
                }
            }

            return Response.status(Response.Status.NOT_FOUND).build();

        } catch (Exception e) {
            LOG.error("Error serving {} image for video ID: {}", imageType, videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @Path("/poster/{videoId}")
    @Produces("image/webp")
    public Response getPoster(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return serveImageFromThumbnails(videoId, "poster", null);
    }

    @GET
    @Path("/backdrop/{videoId}")
    @Produces("image/webp")
    public Response getBackdrop(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return serveImageFromThumbnails(videoId, "backdrop", null);
    }

    @GET
    @Path("/logo/{videoId}")
    public Response getLogoImage(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            Video video = videoService.find(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            if (video.logoPath == null || video.logoPath.isBlank()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            File logoFile = new File(video.logoPath);
            if (!logoFile.exists() || !logoFile.isFile()) {
                LOG.warn("Logo file not found on disk for video {}: {}", videoId, video.logoPath);
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            String contentType = getImageContentType(video.logoPath);

            return Response.ok(logoFile)
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "public, max-age=86400")
                    .header("ETag", "\"" + logoFile.lastModified() + "\"")
                    .build();

        } catch (Exception e) {
            LOG.error("Error serving logo for video ID: " + videoId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @Path("/hero/{videoId}")
    @Produces("image/webp")
    public Response getHero(@PathParam("videoId") Long videoId) {
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return serveImageFromThumbnails(videoId, "hero", "backdrop");
    }

    @POST
    @Path("/watchlist/toggle/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggleWatchlist(@PathParam("videoId") Long videoId) {
        try {
            Models.Video.Video video = Models.Video.Video.findById(videoId);
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
                    Models.Video.Video video = Models.Video.Video.findById(videoId);

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

                            String thumbnailUrl = thumbnailService.getThumbnailPathWithFallback(fullPath, video);
                            thumbnailUrls.add(thumbnailUrl != null ? thumbnailUrl : "/logo.png");
                        } else {
                            thumbnailUrls.add("/logo.png");
                        }
                    } else {
                        thumbnailUrls.add("/logo.png");
                    }
                } catch (NumberFormatException e) {
                    thumbnailUrls.add("/logo.png");
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

    private String getImageContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    @GET
    @Path("/{videoId}/audio-tracks")
    public Response getAudioTracks(@PathParam("videoId") Long videoId) {
        List<Models.Video.AudioTrack> tracks = videoService.getAudioTracks(videoId);
        if (tracks == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Video not found")).build();
        }

        return Response.ok(ApiResponse.success(tracks)).build();
    }

    @Inject
    private Services.VideoHistoryService videoHistoryService;

    @POST
    @Path("/scan")
    public Response scanVideoLibrary(@Context jakarta.ws.rs.core.HttpHeaders headers,
            @jakarta.ws.rs.QueryParam("mode") String mode) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        
        // Determine scan mode: "full" = reload all, "update" (default) = only new videos
        boolean forceFullScan = "full".equalsIgnoreCase(mode);
        String scanModeDesc = forceFullScan ? "full" : "incremental";
        
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }

            try {
                String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    LOG.info("Starting per-video library scan ({}): {}", scanModeDesc, videoLibraryPath);

                    List<Models.Video.Video> videos = videoImportService.scanAndCreate(Paths.get(videoLibraryPath), forceFullScan);

                    LOG.info("Scan and create completed. Created {} videos.", videos.size());
                    
                    // Queue metadata enrichment for background processing
                    executor.submit(() -> videoEnrichmentWorker.queueAllUnenriched());
                    
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

        return Response.ok(ApiResponse.success("Video library scan started (" + scanModeDesc + " mode).")).build();
    }

    @POST
    @Path("/scan/movies")
    public Response scanMovies(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            return Response.ok(ApiResponse.success("Video library path not configured, skipping movies scan.")).build();
        }

        final String libPath = videoLibraryPath;
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }

            try {
                LOG.info("Starting movies scan (prune + find new): {}", libPath);
                int pruned = videoImportService.pruneMissingByType("movie", Paths.get(libPath));
                LOG.info("Pruned {} missing movies", pruned);
                List<Models.Video.Video> videos = videoImportService.scanAndCreate(Paths.get(libPath), false);
                LOG.info("Movies scan completed. Created {} videos.", videos.size());
                executor.submit(() -> thumbnailService.queueAllVideosForRegeneration());
            } catch (Exception e) {
                LOG.error("Error during movies scan: {}", e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });

        return Response.ok(ApiResponse.success("Movies scan started (prune missing, find new).")).build();
    }

    @POST
    @Path("/scan/tvshows")
    public Response scanTvShows(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            return Response.ok(ApiResponse.success("Video library path not configured, skipping TV shows scan.")).build();
        }

        final String libPath = videoLibraryPath;
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) {
                requestContext.activate();
            }

            try {
                LOG.info("Starting TV shows scan (prune + find new): {}", libPath);
                int pruned = videoImportService.pruneMissingByType("episode", Paths.get(libPath));
                LOG.info("Pruned {} missing episodes", pruned);
                List<Models.Video.Video> videos = videoImportService.scanAndCreate(Paths.get(libPath), false);
                LOG.info("TV shows scan completed. Created {} videos.", videos.size());
                executor.submit(() -> thumbnailService.queueAllVideosForRegeneration());
            } catch (Exception e) {
                LOG.error("Error during TV shows scan: {}", e.getMessage(), e);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        });

        return Response.ok(ApiResponse.success("TV shows scan started (prune missing, find new).")).build();
    }

    @POST
    @Path("/reload-metadata")
    public Response reloadVideoMetadata(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
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
                    List<Models.Video.Video> videos = videoImportService.scanAndCreate(Paths.get(videoLibraryPath), true);

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
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoImportService.resetVideoDatabase();
        return Response.ok(ApiResponse.success("Video database and history have been reset.")).build();
    }

    @POST
    @Path("/clear-history")
    public Response clearVideoHistory(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoHistoryService.clearHistory();
        return Response.ok(ApiResponse.success("Video playback history cleared")).build();
    }

    @POST
    @Path("/clear-all")
    public Response clearAllVideos(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        videoImportService.resetVideoDatabase();
        return Response.ok(ApiResponse.success("All video records cleared from database")).build();
    }

    @POST
    @Path("/thumbnail/{videoId}/fetch")
    public Response fetchThumbnail(@PathParam("videoId") Long videoId, @Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        if (videoId == null || videoId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid video ID")).build();
        }

        Models.Video.Video video = Models.Video.Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Video not found")).build();
        }
        String videoPath = video.path;
        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();

        executor.submit(() -> {
            try {
                if (videoPath != null && videoLibraryPath != null && !videoLibraryPath.isBlank()) {
                    String fullPath;
                    java.nio.file.Path vPath = java.nio.file.Paths.get(videoPath);
                    if (vPath.isAbsolute()) {
                        fullPath = vPath.toString();
                    } else {
                        fullPath = java.nio.file.Paths.get(videoLibraryPath, videoPath).toString();
                    }
                    // getThumbnailPathWithFallback uses the Video object directly — no EntityManager needed
                    thumbnailService.getThumbnailPathWithFallback(fullPath, video);
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
        if (!authService.isAdmin(headers)) {
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

    @POST
    @Path("/backfill-images")
    public Response backfillMediaImages(@Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) requestContext.activate();
            try {
                thumbnailService.backfillMediaImages();
            } catch (Exception e) {
                LOG.error("Error during media image backfill", e);
            } finally {
                if (requestContext.isActive()) requestContext.deactivate();
            }
        });
        return Response.ok(ApiResponse.success("Media image backfill started.")).build();
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
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            Models.Video.Video video = Models.Video.Video.findById(videoId);
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
                    
                    Models.Video.Video result = videoImportService.scanSingleFile(videoPath);
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
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            List<Models.Video.Video> existingEpisodes = videoService.findEpisodesForSeries(seriesTitle);
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
                    List<Models.Video.Video> discovered = videoImportService.scan(fullSeriesFolder, false, true);
                    Set<String> discoveredPaths = discovered.stream()
                        .map(v -> v.path)
                        .collect(Collectors.toSet());
                    
                    for (Models.Video.Video episode : existingEpisodes) {
                        if (!discoveredPaths.contains(episode.path)) {
                            episode.delete();
                        }
                    }
                    
                    for (Models.Video.Video video : discovered) {
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
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        try {
            List<Models.Video.Video> existingEpisodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
            
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
                    List<Models.Video.Video> discovered = videoImportService.scan(fullSeasonFolder, false, true);
                    Set<String> discoveredPaths = discovered.stream()
                        .map(v -> v.path)
                        .collect(Collectors.toSet());
                    
                    for (Models.Video.Video episode : existingEpisodes) {
                        if (!discoveredPaths.contains(episode.path)) {
                            episode.delete();
                        }
                    }
                    
                    for (Models.Video.Video video : discovered) {
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
        if (!authService.isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (!requestContext.isActive()) requestContext.activate();
            try {
                Models.Video.Video video = Models.Video.Video.findById(videoId);
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
    public Response getAllVideos(@QueryParam("mediaType") String mediaType,
                                @QueryParam("seriesId") Long seriesId) {
        List<Models.Video.Video> videos;
        if (seriesId != null) {
            Models.Video.Series series = Models.Video.Series.findById(seriesId);
            if (series == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Series not found")).build();
            }
            videos = Models.Video.Video.<Models.Video.Video>find("series = ?1 AND (contentType IS NULL OR contentType = 'episode') ORDER BY seasonNumber ASC, episodeNumber ASC", series).list();
        } else {
            videos = Models.Video.Video.listAll();
        }
        return Response.ok(videos).build();
    }

    @GET
    @Path("/series/{seriesId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosBySeries(@PathParam("seriesId") Long seriesId,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("50") int size) {
        Models.Video.Series series = Models.Video.Series.findById(seriesId);
        if (series == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Series not found")).build();
        }
        List<Models.Video.Video> episodes = Models.Video.Video.<Models.Video.Video>find(
                "series = ?1 AND (contentType IS NULL OR contentType = 'episode') ORDER BY seasonNumber ASC, episodeNumber ASC", series)
                .page(io.quarkus.panache.common.Page.of(page, size))
                .list();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("seriesId", series.id);
        result.put("seriesTitle", series.title);
        result.put("episodes", episodes);
        result.put("page", page);
        result.put("size", size);
        return Response.ok(ApiResponse.success(result)).build();
    }

    @GET
    @Path("/content-type/{contentType}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVideosByContentType(@PathParam("contentType") String contentType,
                                            @QueryParam("page") @DefaultValue("0") int page,
                                            @QueryParam("size") @DefaultValue("50") int size) {
        List<Models.Video.Video> videos = Models.Video.Video.<Models.Video.Video>find(
                "contentType = ?1 ORDER BY title ASC", contentType)
                .page(io.quarkus.panache.common.Page.of(page, size))
                .list();
        long total = Models.Video.Video.<Models.Video.Video>count("contentType = ?1", contentType);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("contentType", contentType);
        result.put("videos", videos);
        result.put("page", page);
        result.put("size", size);
        result.put("totalItems", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return Response.ok(ApiResponse.success(result)).build();
    }

    @GET
    @Path("/continue-watching")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getContinueWatching() {
        List<Models.Video.VideoState> inProgress = videoStateService.getInProgressVideos();
        List<ContinueWatchingDTO> dtos = new ArrayList<>();
        Set<Long> enrichedSeries = new HashSet<>();
        // Dedup by seriesTitle: only keep the latest episode per series.
        // inProgress is sorted by lastUpdated DESC, so the first encounter per series is the latest.
        Set<String> seenSeriesForContinue = new HashSet<>();
        for (Models.Video.VideoState vs : inProgress) {
            if (vs.video == null) continue;
            if ("episode".equals(vs.video.type) && vs.video.seriesTitle != null && !vs.video.seriesTitle.isBlank()) {
                String seriesKey = vs.video.seriesTitle.toLowerCase(Locale.ROOT).trim();
                if (!seenSeriesForContinue.add(seriesKey)) {
                    continue;
                }
            }
            // Enrich metadata (mirrors getVideo pattern)
            try {
                thumbnailService.ensureMediaImages(vs.video.id);
            } catch (Exception e) {
                LOG.warn("Could not enrich images for video {}: {}", vs.video.id, e.getMessage());
            }
            try {
                videoMetadataService.ensureMediaTextMetadata(vs.video.id);
            } catch (Exception e) {
                LOG.warn("Could not enrich text metadata for video {}: {}", vs.video.id, e.getMessage());
            }
            // Series enrichment with deduplication
            if (vs.video.series != null && enrichedSeries.add(vs.video.series.id)) {
                try {
                    videoMetadataService.enrichSeriesTextMetadataAsync(vs.video.series.id);
                } catch (Exception e) {
                    LOG.warn("Could not enrich series text metadata for series {}: {}", vs.video.series.id, e.getMessage());
                }
            }
            // Re-fetch video to get enriched values
            Models.Video.Video enriched = Models.Video.Video.findById(vs.video.id);
            ContinueWatchingDTO dto = new ContinueWatchingDTO();
            dto.id = enriched.id;
            dto.title = enriched.title;
            dto.type = enriched.type;
            dto.seriesTitle = enriched.seriesTitle;
            dto.episodeTitle = enriched.episodeTitle;
            dto.seasonNumber = enriched.seasonNumber;
            dto.episodeNumber = enriched.episodeNumber;
            dto.description = enriched.description;
            dto.overview = enriched.overview;
            dto.releaseYear = enriched.releaseYear;
            dto.imdbRating = enriched.imdbRating;
            dto.duration = enriched.duration;
            dto.thumbnailPath = enriched.thumbnailPath;
            dto.backdropPath = enriched.backdropPath;
            dto.posterPath = enriched.posterPath;
            dto.logoPath = enriched.logoPath;
            dto.genres = enriched.genres;
            // Explicitly populate transient fields from VideoState
            dto.watchProgress = vs.watchProgress;
            dto.watchProgressPercent = vs.watchProgress != null ? (int)(vs.watchProgress * 100) : 0;
            dto.watched = vs.watched;
            dtos.add(dto);
        }
        return Response.ok(dtos).build();
    }

    @GET
    @Path("/shows")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllSeriesTitles() {
        List<String> seriesTitles = Models.Video.Video.<Models.Video.Video>list("type = ?1", "episode")
                .stream()
                .map(v -> v.seriesTitle)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        // Merge external series titles
        List<String> externalTitles = externalVideoService.findAllSeriesTitles();
        for (String ext : externalTitles) {
            if (!seriesTitles.contains(ext)) {
                seriesTitles.add(ext);
            }
        }
        seriesTitles.sort(String.CASE_INSENSITIVE_ORDER);
        return Response.ok(seriesTitles).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSeasonsForSeries(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasonNumbers = Models.Video.Video.<Models.Video.Video>list("type = ?1 and seriesTitle = ?2", "episode", seriesTitle)
                .stream()
                .map(v -> v.seasonNumber)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        // Merge external season numbers
        List<Integer> externalSeasonNumbers = externalVideoService.findSeasonNumbersForSeries(seriesTitle);
        for (Integer extSn : externalSeasonNumbers) {
            if (!seasonNumbers.contains(extSn)) {
                seasonNumbers.add(extSn);
            }
        }
        seasonNumbers.sort(Comparator.naturalOrder());
        return Response.ok(seasonNumbers).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEpisodesForSeason(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<Models.Video.Video> episodes = Models.Video.Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", "episode", seriesTitle, seasonNumber);
        List<Models.Video.ExternalVideo> externalEpisodes = externalVideoService.findBySeriesAndSeason(seriesTitle, seasonNumber);
        com.fasterxml.jackson.databind.node.ArrayNode epArr = mapper.createArrayNode();
        for (Models.Video.Video v : episodes) {
            com.fasterxml.jackson.databind.node.ObjectNode o = mapper.createObjectNode();
            o.put("id", v.id);
            o.put("episodeNumber", v.episodeNumber != null ? v.episodeNumber : 0);
            o.put("seasonNumber", v.seasonNumber != null ? v.seasonNumber : 0);
            o.put("episodeTitle", v.episodeTitle != null ? v.episodeTitle : (v.title != null ? v.title : ""));
            o.put("title", v.title != null ? v.title : "");
            epArr.add(o);
        }
        com.fasterxml.jackson.databind.node.ArrayNode extArr = mapper.createArrayNode();
        for (Models.Video.ExternalVideo ev : externalEpisodes) {
            com.fasterxml.jackson.databind.node.ObjectNode o = mapper.createObjectNode();
            o.put("id", ev.id);
            o.put("episodeNumber", ev.episodeNumber != null ? ev.episodeNumber : 0);
            o.put("seasonNumber", ev.seasonNumber != null ? ev.seasonNumber : 0);
            o.put("episodeTitle", ev.episodeTitle != null ? ev.episodeTitle : (ev.title != null ? ev.title : ""));
            o.put("title", ev.title != null ? ev.title : "");
            extArr.add(o);
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.set("episodes", epArr);
        root.set("externalEpisodes", extArr);
        return Response.ok(root).build();
    }

    @GET
    @Path("/movies")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllMovies(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        List<Models.Video.Video> movies = Models.Video.Video.<Models.Video.Video>list("type = ?1", "movie");
        List<Models.Video.ExternalVideo> externalMovies = externalVideoService.findAllMovies();
        long totalItems = movies.size() + externalMovies.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        PaginatedMovieResponse response = new PaginatedMovieResponse((List<Object>) (Object) movies, page, limit, totalItems, totalPages);
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.valueToTree(response).deepCopy();
        root.set("externalMovies", mapper.valueToTree(externalMovies));
        return Response.ok(root).build();
    }

    @GET
    @Path("/genres")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllGenres() {
        try {
            List<Models.Video.Genre> genres = Models.Video.Genre.list("isActive = true ORDER BY sortOrder, name");
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
            List<Models.Video.Video> videos = videoService.findByGenre(genreSlug, page, limit);
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
            List<Models.Video.Video> videos = videoService.findByMultipleGenres(genreSlugs, page, limit);
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
            List<Models.Video.Video> recommendations = videoService.findRecommendedByGenre(genreSlug, userId);
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
            java.util.Map<String, List<Models.Video.Video>> carousels = videoService.getAllGenreCarousels(userId, itemsPerGenre);
            return Response.ok(ApiResponse.success(carousels)).build();
        } catch (Exception e) {
            LOG.error("Error getting genre carousels", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get genre carousels")).build();
        }
    }

    @Inject
    Services.VideoStoryboardService storyboardService;

    @POST
    @Path("/progress/{videoId}/toggle-watched")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response toggleWatched(@PathParam("videoId") Long videoId) {
        try {
            Models.Settings.Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("No active profile")).build();
            }

            Boolean watched = videoStateService.toggleWatched(videoId);
            if (watched == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found")).build();
            }

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("watched", watched);
            result.put("watchProgress", watched ? 1.0 : 0.0);
            return Response.ok(ApiResponse.success(result)).build();
        } catch (Exception e) {
            LOG.error("Error toggling watched for video {}: {}", videoId, e.getMessage());
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/progress/{videoId}/remove-from-continue-watching")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response removeFromContinueWatching(@PathParam("videoId") Long videoId) {
        try {
            Boolean removed = videoStateService.removeFromContinueWatching(videoId);
            if (removed == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found")).build();
            }
            return Response.ok(ApiResponse.success(true)).build();
        } catch (Exception e) {
            LOG.error("Error removing video {} from continue watching: {}", videoId, e.getMessage());
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/progress/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response reportProgress(@PathParam("videoId") Long videoId, @QueryParam("time") double timeSeconds) {
        try {
            videoStateService.reportProgress(videoId, timeSeconds);
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
}
