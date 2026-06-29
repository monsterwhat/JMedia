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
import Services.ExternalVideoService;
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
import Models.Video;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.context.ManagedExecutor;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ThreadFactory;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.ws.rs.core.Context;
import jakarta.transaction.Transactional;

@Path("/api/video")

public class VideoAPI {

    private static final Logger LOG = LoggerFactory.getLogger(VideoAPI.class);
    private static final int DEFAULT_QUALITY_HEIGHT = 720;

    private final ObjectMapper mapper = new ObjectMapper();

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
    Services.ProfileSessionStateService profileSessionStateService;

    @Inject
    Services.VideoMetadataService videoMetadataService;

    @Inject
    VideoScanExecutor videoScanExecutor;

    @Inject
    SubtitleDiscoveryQueueProcessor subtitleDiscoveryProcessor;

    @Inject
    ExternalVideoService externalVideoService;

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
        Models.DTOs.VideoMetadataDTO dto = new Models.DTOs.VideoMetadataDTO(video);
        // Populate per-profile resume time from VideoState
        try {
            Models.VideoState progress = videoStateService.getOrCreate(video);
            if (progress != null && progress.currentTime > 0) {
                dto.resumeTime = progress.currentTime;
            } else if (progress != null && progress.watchProgress != null && progress.watchProgress > 0 && progress.watchProgress < 0.95) {
                dto.resumeTime = progress.watchProgress * (video.getDurationSeconds());
            }
            if (dto.resumeTime != null && video.getDurationSeconds() > 0 && (dto.resumeTime / video.getDurationSeconds()) >= 0.95) {
                dto.resumeTime = 0.0;
            }
        } catch (Exception e) {
            LOG.warn("Could not load resumeTime for video {}: {}", videoId, e.getMessage());
        }
        return Response.ok(API.ApiResponse.success(dto)).build();
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

            String thumbnailUrl = thumbnailService.getThumbnailPathWithFallback(fullPath, video);

            if (thumbnailUrl != null && Files.exists(java.nio.file.Paths.get(thumbnailUrl))) {
                File thumbnailFile = java.nio.file.Paths.get(thumbnailUrl).toFile();
                return Response.ok(thumbnailFile)
                        .header("Content-Type", "image/webp")
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
    @Path("/stream/{videoId:[0-9]+}.mp4")
    public Response streamVideo(@PathParam("videoId") Long videoId, 
                               @HeaderParam("Range") String rangeHeader,
                               @HeaderParam("User-Agent") String userAgent,
                               @QueryParam("start") @DefaultValue("0") double startSeconds,
                               @QueryParam("audioTrack") @DefaultValue("-1") int audioTrackIndex,
                               @QueryParam("quality") @DefaultValue("0") int qualityHeight,
                                @QueryParam("trace") String traceId,
                                @QueryParam("nativeHevc") @DefaultValue("false") boolean nativeHevc) {
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

        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamVideo called: videoId={} start={}s audioTrack={} quality={} range={} isMKV={}", traceId, videoId, startSeconds, audioTrackIndex, qualityHeight, rangeHeader != null ? rangeHeader.substring(0, Math.min(50, rangeHeader.length())) : "none", isMKV);

        // Ensure we have metadata to make an informed transcoding decision
        if (video.videoCodec == null || video.audioCodec == null) {
            videoService.probeVideoMetadata(video);
        }

        // Default quality cap: when no explicit quality is requested, limit to
        // 720p maximum.  Higher resolutions are only used when the user
        // explicitly selects them from the quality menu.
        boolean isFastStart = !isMKV && hasFastStart(videoFile);
        boolean transcodeNeeded = transcodingService.isTranscodeNeededForWeb(video, userAgent);
        // Client-side native HEVC support override: when the browser can play HEVC
        // natively (e.g. Chrome with HEVC Video Extensions on Windows), skip the
        // server-side FFmpeg transcode and serve the HEVC stream directly via
        // the lightweight mkvmerge/FFmpeg-copy path.
        if (nativeHevc && video.videoCodec != null &&
            (video.videoCodec.toLowerCase(Locale.ROOT).contains("hevc") ||
             video.videoCodec.toLowerCase(Locale.ROOT).contains("h265"))) {
            transcodeNeeded = false;
        }
        if (qualityHeight <= 0) {
            int sourceHeight = parseSourceHeight(video.resolution);
            if (isFastStart && !transcodeNeeded && sourceHeight > 0) {
                // Faststart + native codec → serve directly at source resolution.
                // No quality cap is applied because re-encoding just to downscale
                // is CPU-intensive and counterproductive: transcoded H.264 at 720p
                // often uses more bandwidth than source HEVC at 1080p.
                LOG.info("[STREAM] videoId={} file={} codec={}/{} path=direct-faststart res={}",
                    videoId, videoFile.getName(), video.videoCodec, video.audioCodec, video.resolution);
                return streamDirectFile(videoFile, rangeHeader, traceId);
            }
            // File lacks faststart but codec is natively supported and
            // resolution is known — remux as fMP4 at source resolution.
            // Skipping the 720p cap means the transcode service will use
            // -c:v copy (no re-encode) at the native resolution.
            if (!transcodeNeeded && sourceHeight > 0) {
                qualityHeight = sourceHeight;
            } else {
                // Codec needs conversion, or resolution is unknown —
                // enforce the default quality cap.
                qualityHeight = DEFAULT_QUALITY_HEIGHT;
            }
        }

        LOG.info("[STREAM] videoId={} file={} codec={}/{} path=fragmented-mp4 isMKV={} transcodeNeeded={} quality={}",
            videoId, videoFile.getName(), video.videoCodec, video.audioCodec, isMKV,
            transcodeNeeded, qualityHeight);
        return streamRemuxedMKV(video, videoFile, startSeconds, userAgent, rangeHeader, audioTrackIndex, qualityHeight, traceId);
    }

    private Response streamRemuxedMKV(Models.Video video, File videoFile, double startSeconds, String userAgent, String rangeHeader, int audioTrackIndex, int qualityHeight, String traceId) {
        final Long videoId = video.id;
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamRemuxedMKV: videoId={} start={}s", traceId, videoId, startSeconds);

        // Pre-compute a stable estimated final size so that EVERY Content-Range response
        // reports the same total. Safari (and other clients) reject the stream if the total
        // changes between the probe (bytes=0-1) and subsequent range requests. Since we use
        // -c:v copy (video is bit-identical), the output size ≈ source size. 10% headroom
        // covers the audio re-encode (src audio → AAC 192k, often smaller than DTS/FLAC).
        final long estimatedFinalSize = (long)(videoFile.length() * 1.10);

        // iOS Safari sends a bytes=0-0 or bytes=0-1 probe to validate range support and
        // discover the total file size before it requests the real init segment.  Respond
        // immediately with an empty 206 so Safari never tries to parse partial MP4 data.
        // The empty body is intentional: the transcode hasn't produced usable fMP4 bytes
        // yet, but the 206 + Content-Range headers confirm that ranges are supported and
        // reveal the (estimated) total size.  Safari then follows up with a proper range
        // request (e.g. bytes 0-65535) by which time the transcode will have data ready.
        if (rangeHeader != null && (rangeHeader.startsWith("bytes=0-0") || rangeHeader.startsWith("bytes=0-1"))) {
            LOG.info("[trace:{}] iOS Safari bytes=0-1 probe for videoId={}, returning empty 206 (total={})", traceId != null ? traceId : "-", videoId, estimatedFinalSize);
            String etag = Integer.toHexString((video.id + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight).hashCode());
            return Response.status(Response.Status.PARTIAL_CONTENT)
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes 0-1/" + estimatedFinalSize)
                    .header("Content-Length", "0")
                    .header("Accept-Ranges", "bytes")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("ETag", etag)
                    .header("Cache-Control", "no-cache")
                    .build();
        }

        // Check for an existing cache file (from a prior pipe stream). If present with
        // enough data, serve from it instead of starting a new transcode.
        try {
            java.nio.file.Path cacheFile = transcodingService.getCacheFilePath(videoId, startSeconds, audioTrackIndex, qualityHeight);
            if (java.nio.file.Files.exists(cacheFile) && java.nio.file.Files.size(cacheFile) > 65536) {
                LOG.info("Serving from cache file for video {} (start={}s, audio={})", videoId, startSeconds, audioTrackIndex);
                try {
                    java.nio.file.Files.setLastModifiedTime(cacheFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException ignored) {}
                return streamFromTempFile(video, videoFile, cacheFile, startSeconds, rangeHeader, audioTrackIndex, qualityHeight, traceId, estimatedFinalSize);
            }
        } catch (IOException ignored) {
            LOG.debug("Cache file check failed for video {}, proceeding with transcode", videoId);
        }

        // Primary: stream from a temp file (supports HTTP Range seeking on all clients).
        // If the transcode infrastructure fails (no resources, FFmpeg unavailable, etc.),
        // fall back to a direct ffmpeg pipe.
        try {
            java.nio.file.Path tempFile = transcodingService.getOrCreateTranscode(video, videoFile, startSeconds, userAgent, audioTrackIndex, qualityHeight);
            return streamFromTempFile(video, videoFile, tempFile, startSeconds, rangeHeader, audioTrackIndex, qualityHeight, traceId, estimatedFinalSize);
        } catch (IOException e) {
            LOG.warn("Temp file transcode failed for video {}, falling back to direct pipe: {}", videoId, e.getMessage());
        }

        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] Falling back to direct remux for video {}", traceId, videoId);
        LOG.debug("Fallback direct remux stream for video {} (start={}s, audio={})",
                  videoId, startSeconds, audioTrackIndex >= 0 ? audioTrackIndex : "default");
        return streamRemuxedMKVDirect(video, videoFile, startSeconds, userAgent, audioTrackIndex, qualityHeight, traceId);
    }

    private Response streamRemuxedMKVDirect(Models.Video video, File videoFile, double startSeconds, String userAgent, int audioTrackIndex, int qualityHeight, String traceId) {
        final Long videoId = video.id;
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamRemuxedMKVDirect: videoId={} start={}s", traceId, videoId, startSeconds);
        java.nio.file.Path cacheFile = transcodingService.getCacheFilePath(videoId, startSeconds, audioTrackIndex, qualityHeight);

        StreamingOutput streamingOutput = output -> {
            try {
                transcodingService.streamRemuxedMKV(video, videoFile, startSeconds, userAgent, output, audioTrackIndex, qualityHeight, cacheFile);
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Direct remux stream error for video {}: {}", videoId, e.getMessage());
                }
            } finally {
                transcodingService.releaseTranscode(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            }
        };

        return Response.ok(streamingOutput)
                .header("Content-Type", "video/mp4")
                .header("Accept-Ranges", "bytes")
                .header("Cache-Control", "no-cache")
                .header("Access-Control-Allow-Origin", "*")
                .build();
    }

    private Response streamFromTempFile(Models.Video video, File videoFile, java.nio.file.Path tempFile, double startSeconds,
                                         String rangeHeader, int audioTrackIndex, int qualityHeight, String traceId, long estimatedFinalSize) {
        final Long videoId = video.id;
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamFromTempFile: videoId={} start={}s range={}", traceId, videoId, startSeconds, rangeHeader != null ? rangeHeader.substring(0, Math.min(50, rangeHeader.length())) : "none");

        // Fast-fail: if the transcode has already failed, return 503 instead of waiting 90s
        if (transcodingService.isTranscodeFailed(videoId, startSeconds, audioTrackIndex, qualityHeight, false)) {
            LOG.warn("Transcode already failed for video {} (start={}s, audio={}, quality={}), returning 503",
                     videoId, startSeconds, audioTrackIndex, qualityHeight);
            transcodingService.releaseTranscode(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }

        long fileLength;
        try {
            transcodingService.waitForFile(tempFile, 65536, videoId, startSeconds, audioTrackIndex, qualityHeight);
            fileLength = Files.size(tempFile);
        } catch (IOException e) {
            LOG.error("Cannot get size of temp file for video {}: {}", videoId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String rangeValue = rangeHeader.substring(6).trim();
                if (rangeValue.startsWith("-")) {
                    long suffix = Long.parseLong(rangeValue.substring(1));
                    start = Math.max(0, fileLength - suffix);
                    end = fileLength - 1;
                } else {
                    String[] parts = rangeValue.split("-", -1);
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        end = Long.parseLong(parts[1].trim());
                    } else {
                        end = fileLength - 1;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Invalid Range header '{}': {}", rangeHeader, e.getMessage());
                start = 0;
                end = fileLength - 1;
            }
        }

        // Validate range bounds — for streaming files, do NOT reset start=0.
        // That would send Safari duplicate data from byte 0 and freeze playback.
        // Save original end before clamping so waitForFile uses the correct target.
        long originalEnd = end;
        if (end >= fileLength && start < fileLength) {
            end = fileLength - 1;
        }
        if (start > end) {
            // Requested range starts past current EOF — preserve original end
            // as the wait target so waitForFile blocks until data is produced.
            end = originalEnd;
        }

        String etag = Integer.toHexString((video.id + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight).hashCode());

        LOG.debug("Stream: range {}-{} (len={}) for video {} (etag={})", start, end, end - start + 1, videoId, etag);

        // Wait for the requested range to be available before sending headers.
        // Add a 1MB write-ahead margin for growing fMP4 files so Firefox never
        // reads a moof atom whose referenced mdat data hasn't been written yet.
        try {
            boolean xcodeFinished = transcodingService.isTranscodeFinished(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            long waitTarget;
            if (start >= fileLength) {
                // File hasn't grown to the requested start offset yet.
                waitTarget = start + 1;
                if (!xcodeFinished) waitTarget += 1024 * 1024;
            } else {
                waitTarget = end + 1;
                if (!xcodeFinished) waitTarget += 1024 * 1024;
            }
            // Cap wait target: the streaming loop reads incrementally as the
            // transcode produces data, so we only need enough to start reading.
            // Without this cap, requesting the full file (bytes 0-2.5GB) would
            // block for 90s waiting on a file that's still being transcoded.
            if (!xcodeFinished) {
                waitTarget = Math.min(waitTarget, fileLength + 5 * 1024 * 1024);
            }
            transcodingService.waitForFile(tempFile, waitTarget, videoId, startSeconds, audioTrackIndex, qualityHeight);
        } catch (IOException e) {
            LOG.error("Timeout waiting for requested byte range {}-{} for video {}: {}", start, end, videoId, e.getMessage());
            transcodingService.releaseTranscode(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            long currentSize;
            try {
                currentSize = Files.size(tempFile);
            } catch (IOException ex) {
                currentSize = 0;
            }
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Content-Range", "bytes */" + currentSize)
                    .build();
        }

        long currentFileSize;
        try {
            currentFileSize = Files.size(tempFile);
        } catch (IOException e) {
            currentFileSize = fileLength;
        }

        // Content-Length: only up to what the file actually has after waitForFile
        long contentLength;
        if (start < currentFileSize) {
            long adjustedEnd = Math.min(end, currentFileSize - 1);
            contentLength = adjustedEnd - start + 1;
        } else {
            contentLength = 0;
        }

        boolean transcodeFinished = transcodingService.isTranscodeFinished(videoId, startSeconds, audioTrackIndex, qualityHeight, false);

        // Check for discontinuity
        boolean hasDiscontinuity = transcodingService.hasTranscodeDiscontinuity(videoId, startSeconds, audioTrackIndex, qualityHeight, false);

        final long finalStart = start;
        final long finalEnd = end;

        StreamingOutput streamingOutput = output -> {
            try {
                try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "r")) {
                    raf.seek(finalStart);
                    byte[] buffer = new byte[65536];
                    // For range requests, send exactly the requested range.
                    // For non-range (transcode in progress), send until transcode finishes.
                    long remaining = (rangeHeader != null || transcodeFinished) ? contentLength : Long.MAX_VALUE;
                    while (remaining > 0) {
                        int readSize = (remaining == Long.MAX_VALUE) ? buffer.length : (int) Math.min(buffer.length, remaining);
                        int read = raf.read(buffer, 0, readSize);
                        if (read == -1) {
                            // Check if transcode is done — no more data will come
                            if (transcodingService.isTranscodeFinished(videoId, startSeconds, audioTrackIndex, qualityHeight, false)) {
                                LOG.debug("Transcode finished, stopping stream for video {}", videoId);
                                break;
                            }
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            continue;
                        }
                        output.write(buffer, 0, read);
                        if (remaining != Long.MAX_VALUE) remaining -= read;
                    }
                }
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Streaming error for temp file of video {}: {}", videoId, e.getMessage());
                }
            } finally {
                transcodingService.releaseTranscode(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            }
        };

        // Cache policy: immutable once fully transcoded, no-cache while still growing
        String cacheControl = transcodeFinished
                ? "public, max-age=31536000, immutable"
                : "no-cache";
        Response.ResponseBuilder responseBuilder = Response.status(rangeHeader != null ? Response.Status.PARTIAL_CONTENT : Response.Status.OK)
                .entity(streamingOutput)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", "video/mp4")
                .header("Cache-Control", cacheControl)
                .header("ETag", "\"" + etag + "\"")
                .header("Access-Control-Allow-Origin", "*");

        boolean isRangeRequest = rangeHeader != null;
        if (transcodeFinished || isRangeRequest) {
            responseBuilder.header("Content-Length", contentLength);
        }

        if (isRangeRequest) {
            if (transcodeFinished) {
                long responseEnd = Math.min(finalEnd, currentFileSize - 1);
                // After transcode finishes, use actual file size (not inflated estimate).
                // Safari is already playing and handles this total adjustment mid-stream.
                long finishedTotal = currentFileSize;
                responseBuilder.header("Content-Range", "bytes " + finalStart + "-" + responseEnd + "/" + finishedTotal);
            } else {
                // Use current file size so every response has a real (if growing) total,
                // but never below estimatedFinalSize so the total stays consistent across requests.
                long reportedSize = Math.max(estimatedFinalSize, Math.max(currentFileSize, end + 1));
                responseBuilder.header("Content-Range", "bytes " + finalStart + "-" + Math.min(finalEnd, reportedSize - 1) + "/" + reportedSize);
            }
        }

        if (hasDiscontinuity) {
            responseBuilder.header("X-Stream-Discontinuity", "true");
        }

        return responseBuilder.build();
    }

    /**
     * Parses the video height from a resolution string (e.g. "1920x1080" → 1080).
     * Returns 0 if the resolution is unknown or unparseable.
     */
    private static int parseSourceHeight(String resolution) {
        if (resolution == null || !resolution.contains("x")) return 0;
        try {
            String[] parts = resolution.split("x");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean hasFastStart(File videoFile) {
        final int HEADER_SIZE = 65536;
        try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
            byte[] header = new byte[HEADER_SIZE];
            int read = raf.read(header);
            if (read < 8) return false;

            int offset = 0;
            while (offset + 8 <= read) {
                int boxSize = ((header[offset] & 0xFF) << 24)
                            | ((header[offset + 1] & 0xFF) << 16)
                            | ((header[offset + 2] & 0xFF) << 8)
                            | (header[offset + 3] & 0xFF);

                // ISO 14496-12: size=0 means box extends to end of file
                if (boxSize == 0) break;
                // ISO 14496-12: size=1 means 64-bit extended size follows
                if (boxSize == 1) {
                    if (offset + 16 > read) break;
                    offset += 16;
                    continue;
                }
                if (boxSize < 8) return false;

                String boxType = new String(header, offset + 4, 4, StandardCharsets.US_ASCII);
                if ("moov".equals(boxType)) return true;
                if ("mdat".equals(boxType)) return false;

                offset += boxSize;
            }
        } catch (IOException e) {
            LOG.warn("hasFastStart check failed for {}: {}", videoFile.getName(), e.getMessage());
        }
        return false;
    }

    private Response streamDirectFile(File videoFile, String rangeHeader, String traceId) {
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamDirectFile: file={} range={}", traceId, videoFile.getName(), rangeHeader != null ? rangeHeader.substring(0, Math.min(50, rangeHeader.length())) : "none");
        long fileLength = videoFile.length();
        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String rangeValue = rangeHeader.substring(6).trim();
                if (rangeValue.startsWith("-")) {
                    // Suffix range: bytes=-500 (last 500 bytes)
                    long suffix = Long.parseLong(rangeValue.substring(1));
                    start = Math.max(0, fileLength - suffix);
                    end = fileLength - 1;
                } else {
                    String[] parts = rangeValue.split("-", -1);
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        end = Long.parseLong(parts[1].trim());
                    } else {
                        end = fileLength - 1;
                    }
                }

                // Validation
                if (end >= fileLength) end = fileLength - 1;
                if (start > end) {
                    start = 0;
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
                .header("Cache-Control", "public, max-age=86400, immutable")
                .header("Access-Control-Allow-Origin", "*");

        if (rangeHeader != null) {
            responseBuilder.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        }

        return responseBuilder.build();
    }

    @GET
    @Path("/{videoId}/audio-tracks")
    @Transactional
    public Response getAudioTracks(@PathParam("videoId") Long videoId) {
        Video video = videoService.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Video not found")).build();
        }

        List<Models.AudioTrack> tracks = Models.AudioTrack.list("video.id", videoId);
        if (tracks == null) tracks = new ArrayList<>();
        
        return Response.ok(ApiResponse.success(tracks)).build();
    }

    @Inject
    private Services.VideoHistoryService videoHistoryService;

    @POST
    @Path("/scan")
    public Response scanVideoLibrary(@Context jakarta.ws.rs.core.HttpHeaders headers,
            @jakarta.ws.rs.QueryParam("mode") String mode) {
        if (!checkAdmin(headers)) {
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

                    List<Models.Video> videos = videoImportService.scanAndCreate(Paths.get(videoLibraryPath), forceFullScan);

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

        return Response.ok(ApiResponse.success("Video library scan started (" + scanModeDesc + " mode).")).build();
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
        List<Integer> seasonNumbers = Models.Video.<Models.Video>list("type = ?1 and seriesTitle = ?2", "episode", seriesTitle)
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
        List<Models.Video> episodes = Models.Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", "episode", seriesTitle, seasonNumber);
        List<Models.ExternalVideo> externalEpisodes = externalVideoService.findBySeriesAndSeason(seriesTitle, seasonNumber);
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.set("episodes", mapper.valueToTree(episodes));
        root.set("externalEpisodes", mapper.valueToTree(externalEpisodes));
        return Response.ok(root).build();
    }

    @GET
    @Path("/movies")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllMovies(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        List<Models.Video> movies = Models.Video.<Models.Video>list("type = ?1", "movie");
        List<Models.ExternalVideo> externalMovies = externalVideoService.findAllMovies();
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
    @Path("/progress/{videoId}/toggle-watched")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @Transactional
    public Response toggleWatched(@PathParam("videoId") Long videoId) {
        try {
            Models.Video video = Models.Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Video not found")).build();
            }

            Models.Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("No active profile")).build();
            }

            Models.VideoState state = videoStateService.getOrCreate(video);
            state.watched = !Boolean.TRUE.equals(state.watched);
            if (Boolean.TRUE.equals(state.watched)) {
                state.watchProgress = 1.0;
            } else {
                state.watchProgress = 0.0;
                state.currentTime = 0.0;
            }
            state.persist();

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("watched", state.watched);
            result.put("watchProgress", state.watchProgress);
            return Response.ok(ApiResponse.success(result)).build();
        } catch (Exception e) {
            LOG.error("Error toggling watched for video {}: {}", videoId, e.getMessage());
            return Response.serverError().entity(ApiResponse.error("Internal server error")).build();
        }
    }

    @POST
    @Path("/progress/{videoId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @jakarta.transaction.Transactional
    public Response reportProgress(@PathParam("videoId") Long videoId, @QueryParam("time") double timeSeconds) {
        try {
            // Update per-profile progress
            Models.Video video = Models.Video.findById(videoId);
            if (video != null) {
                // Update per-profile VideoState progress
                videoStateService.updateProgress(video, timeSeconds);
            }

            // Also update the ephemeral ProfileSessionState for real-time UI synchronization if needed
            try {
                Models.ProfileSessionState state = profileSessionStateService.getOrCreate();
                if (state != null && videoId.equals(state.currentVideoId)) {
                    state.currentTime = timeSeconds;
                    state = profileSessionStateService.save(state);
                }
            } catch (Exception e) {
                LOG.warn("Could not sync ProfileSessionState for video {}: {}", videoId, e.getMessage());
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
