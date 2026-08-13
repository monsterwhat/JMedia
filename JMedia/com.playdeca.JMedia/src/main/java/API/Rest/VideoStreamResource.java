package API.Rest;

import Models.Video.Video;
import Services.SegmentCacheService;
import Services.SettingsService;
import Services.TranscodingService;
import Services.VideoConversionService;
import Services.VideoService;
import Utils.FragmentedMp4Seeker;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/video")

public class VideoStreamResource {

    private static final Logger LOG = LoggerFactory.getLogger(VideoStreamResource.class);
    private static final int DEFAULT_QUALITY_HEIGHT = 720;

    // Suppress per-range-request spam: log hot-path stream events once per session.
    private static final long STREAM_SESSION_GAP_MS = 120_000;
    private static final int STREAM_LOG_MAX_ENTRIES = 4096;
    private static final Map<String, Long> STREAM_LAST_REQUEST = new ConcurrentHashMap<>();

    // Unreadable-file failure cache: videoId -> StreamFailure. Once ffprobe proves
    // a file has no readable video stream (e.g. a partial download missing its
    // moov atom), repeated stream requests short-circuit here instead of re-probing
    // metadata and spawning a doomed FFmpeg transcode for every retry.
    private static final long STREAM_FAILURE_TTL_MS = 5 * 60_000L;
    private static final Map<Long, StreamFailure> STREAM_FAILURES = new ConcurrentHashMap<>();

    /** A cached unreadable-file failure: expires, and is invalidated when the file changes. */
    private static final class StreamFailure {
        final long expiresAt;
        final long fileSize;
        final long fileMtime;

        StreamFailure(long expiresAt, long fileSize, long fileMtime) {
            this.expiresAt = expiresAt;
            this.fileSize = fileSize;
            this.fileMtime = fileMtime;
        }
    }

    /** True while a cached failure still applies to this exact file. */
    private boolean isStreamFailureCached(Long videoId, File videoFile) {
        StreamFailure failure = STREAM_FAILURES.get(videoId);
        if (failure == null) {
            return false;
        }
        if (System.currentTimeMillis() >= failure.expiresAt
                || failure.fileSize != videoFile.length()
                || failure.fileMtime != videoFile.lastModified()) {
            // Expired, or the file changed on disk (download completed) — re-evaluate.
            STREAM_FAILURES.remove(videoId);
            return false;
        }
        return true;
    }

    private void cacheStreamFailure(Long videoId, File videoFile) {
        STREAM_FAILURES.put(videoId, new StreamFailure(
                System.currentTimeMillis() + STREAM_FAILURE_TTL_MS,
                videoFile.length(), videoFile.lastModified()));
        if (STREAM_FAILURES.size() > STREAM_LOG_MAX_ENTRIES) {
            long cutoff = System.currentTimeMillis() - STREAM_FAILURE_TTL_MS;
            STREAM_FAILURES.entrySet().removeIf(e -> e.getValue().expiresAt < cutoff);
        }
    }

    @Inject
    TranscodingService transcodingService;

    @Inject
    Services.SegmentCacheService segmentCacheService;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    Services.VideoConversionService videoConversionService;

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

    private String streamSessionKey(Long videoId, double startSeconds, int audioTrackIndex, int qualityHeight) {
        return videoId + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight;
    }

    private boolean shouldLogStreamStart(String sessionKey) {
        long now = System.currentTimeMillis();
        Long last = STREAM_LAST_REQUEST.put(sessionKey, now);
        boolean firstOrNewSession = last == null || now - last > STREAM_SESSION_GAP_MS;
        if (STREAM_LAST_REQUEST.size() > STREAM_LOG_MAX_ENTRIES) {
            long cutoff = now - 30 * 60_000L;
            STREAM_LAST_REQUEST.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        return firstOrNewSession;
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

        // Complete a conversion finalize deferred while the video was streaming
        // — must run BEFORE findById so the path resolves to the converted file.
        videoConversionService.finalizePendingIfIdle(videoId);

        Models.Video.Video video = Models.Video.Video.findById(videoId);
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

        // Fast-fail: a previous request proved this file unreadable (e.g. a partial
        // download missing its moov atom). Short-circuit repeated identical requests
        // instead of re-probing and spawning a doomed FFmpeg transcode each time.
        if (isStreamFailureCached(videoId, videoFile)) {
            LOG.warn("[STREAM] videoId={} file={} short-circuited (cached unreadable-file failure)", videoId, videoFile.getName());
            return Response.status(422)
                    .entity("Video file is unreadable or still downloading").build();
        }

        String filename = videoFile.getName().toLowerCase();
        boolean isMKV = filename.endsWith(".mkv");

        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamVideo called: videoId={} start={}s audioTrack={} quality={} range={} isMKV={}", traceId, videoId, startSeconds, audioTrackIndex, qualityHeight, rangeHeader != null ? rangeHeader.substring(0, Math.min(50, rangeHeader.length())) : "none", isMKV);

        // Ensure we have metadata to make an informed transcoding decision
        if (video.videoCodec == null || video.audioCodec == null) {
            videoService.probeVideoMetadata(video);
        }

        // The file exists but ffprobe found no video stream — unreadable/corrupt
        // file (e.g. a partial *.tmp.mp4 download with no moov atom). Fail fast and
        // cache the failure so the retry storm short-circuits at the top instead of
        // forcing a transcode of an undecodable input.
        if (video.videoCodec == null) {
            cacheStreamFailure(videoId, videoFile);
            LOG.warn("[STREAM] videoId={} file={} has no readable video stream (unreadable/partial file); returning 422", videoId, videoFile.getName());
            return Response.status(422)
                    .entity("Video file is unreadable or still downloading").build();
        }

        // Default quality cap: when no explicit quality is requested, limit to
        // 720p maximum.  Higher resolutions are only used when the user
        // explicitly selects them from the quality menu.
        boolean isFastStart = !isMKV && hasFastStart(videoFile);
        boolean transcodeNeeded = transcodingService.isTranscodeNeededForWeb(video, userAgent);
        // Client-side native HEVC support override: when the browser can play HEVC
        // natively (e.g. Chrome with HEVC Video Extensions on Windows), skip the
        // server-side FFmpeg transcode and serve the HEVC stream directly via
        // the lightweight FFmpeg-copy path.
        if (nativeHevc && video.videoCodec != null &&
            (video.videoCodec.toLowerCase(Locale.ROOT).contains("hevc") ||
             video.videoCodec.toLowerCase(Locale.ROOT).contains("h265"))) {
            transcodeNeeded = false;
        }
        if (qualityHeight <= 0) {
            int sourceHeight = parseSourceHeight(video.resolution);
            if (isFastStart && !transcodeNeeded && sourceHeight > 0 && audioTrackIndex < 0) {
                // Faststart + native codec → serve directly at source resolution.
                // No re-encoding to downscale is CPU-intensive and counterproductive:
                // transcoded H.264 at 720p often uses more bandwidth than source HEVC
                // at 1080p. BUT only when no specific audio track is requested —
                // streamDirectFile serves the raw file and would play the file's
                // default audio stream regardless of ?audioTrack=.
                if (shouldLogStreamStart(streamSessionKey(videoId, startSeconds, audioTrackIndex, qualityHeight))) {
                    LOG.info("[STREAM] videoId={} file={} codec={}/{} path=direct-faststart res={}",
                        videoId, videoFile.getName(), video.videoCodec, video.audioCodec, video.resolution);
                }
                return streamDirectFile(videoFile, rangeHeader, traceId);
            }
            // Never downscale: serve at the source resolution whether the codec is
            // natively supported (remux/-c copy) or needs a web-compatible transcode.
            // A lower quality is only used when the client explicitly requests one.
            if (sourceHeight > 0) {
                qualityHeight = sourceHeight;
            } else {
                // Resolution unknown — fall back to the default cap.
                qualityHeight = DEFAULT_QUALITY_HEIGHT;
            }
        }

        if (shouldLogStreamStart(streamSessionKey(videoId, startSeconds, audioTrackIndex, qualityHeight))) {
            LOG.info("[STREAM] videoId={} file={} codec={}/{} path=fragmented-mp4 isMKV={} transcodeNeeded={} quality={}",
                videoId, videoFile.getName(), video.videoCodec, video.audioCodec, isMKV,
                transcodeNeeded, qualityHeight);
        }
        // Latest-seek-wins: a drag-seek abandons the previous ?start= stream, but its
        // FFmpeg process can keep running until it exits, holding a transcode permit.
        // Kill superseded transcodes for this video so the new request never queues
        // behind a stale process (permits exhausted during rapid seeks).
        transcodingService.releaseStaleTranscodesForVideo(videoId, startSeconds, audioTrackIndex, qualityHeight, filePath);
        return streamRemuxedMKV(video, videoFile, startSeconds, userAgent, rangeHeader, audioTrackIndex, qualityHeight, traceId, transcodeNeeded);
    }

    private Response streamRemuxedMKV(Models.Video.Video video, File videoFile, double startSeconds, String userAgent, String rangeHeader, int audioTrackIndex, int qualityHeight, String traceId, boolean transcodeNeeded) {
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
            // Safari rejects changing totals: probe reports the same total + ETag the follow-up
            // range request will use — the segment's byte-relative total when one covers the seek.
            Services.SegmentCacheService.SegmentCoverage probeCoverage = segmentCacheService
                    .findCoveringSegment(videoId, startSeconds, audioTrackIndex, qualityHeight, videoFile.toPath());
            long probeTotal = estimatedFinalSize;
            String etag = Integer.toHexString((video.id + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight).hashCode());
            if (probeCoverage != null) {
                etag = Integer.toHexString((probeCoverage.file.getFileName().toString() + "|" + String.format(java.util.Locale.ROOT, "%.3f", probeCoverage.startSeconds)).hashCode());
                // Same effective-offset rule as the serve path: a segment whose head is
                // at the seek point is served from byte 0, so the probe total must match
                // (Safari rejects changing totals between the probe and the range request).
                Long effectiveOffset = probeCoverage.byteOffset;
                if (effectiveOffset != null && startSeconds - probeCoverage.startSeconds < 0.5) {
                    effectiveOffset = 0L;
                }
                if (effectiveOffset != null) {
                    try {
                        // Match the serve path: mid-segment serves prefix the init segment, so the
                        // probe total must include it too (Safari rejects changing totals).
                        long probeInit = 0;
                        if (effectiveOffset > 0) {
                            probeInit = FragmentedMp4Seeker.firstFragmentOffset(probeCoverage.file);
                            if (probeInit > effectiveOffset) probeInit = effectiveOffset;
                        }
                        probeTotal = Math.max(1L, probeInit + (java.nio.file.Files.size(probeCoverage.file) - effectiveOffset));
                    } catch (IOException e) {
                        LOG.debug("Failed to size segment {} for Safari probe: {}", probeCoverage.file, e.getMessage());
                    }
                }
            }
            LOG.info("[trace:{}] iOS Safari bytes=0-1 probe for videoId={}, returning empty 206 (total={})", traceId != null ? traceId : "-", videoId, probeTotal);
            return Response.status(Response.Status.PARTIAL_CONTENT)
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes 0-1/" + probeTotal)
                    .header("Content-Length", "0")
                    .header("Accept-Ranges", "bytes")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("ETag", etag)
                    .header("Cache-Control", "no-cache")
                    .build();
        }

        // Segment-aware coverage lookup: if a segment chain covers the requested time, serve from
        // the segment at the byte offset of the fragment containing the seek point. findCoveringSegment
        // also purges stale chains internally (source-file change detected via sidecar identity).
        java.nio.file.Path sourcePath = videoFile.toPath();
        Services.SegmentCacheService.SegmentCoverage coverage = segmentCacheService
                .findCoveringSegment(videoId, startSeconds, audioTrackIndex, qualityHeight, sourcePath);
        if (coverage != null) {
            Long off = coverage.byteOffset;
            if (off == null) {
                // Target lies beyond the segment's written end (still growing): wait for the
                // fragment containing the seek point, then re-probe.
                if (segmentCacheService.waitForTimeCovered(coverage.file, startSeconds, 10_000L)) {
                    try {
                        // Segments are 0-based relative; convert the video-absolute target.
                        double relTarget = Math.max(0.0, startSeconds - coverage.startSeconds);
                        off = FragmentedMp4Seeker.byteOffsetForTime(coverage.file, relTarget);
                    } catch (IOException e) {
                        LOG.warn("Failed to re-probe segment {} for time {}s: {}", coverage.file, startSeconds, e.getMessage());
                        off = null;
                    }
                } else {
                    LOG.info("Segment {} did not cover time {}s within timeout; falling back to direct transcode",
                             coverage.file.getFileName(), startSeconds);
                }
            }
            if (off != null) {
                // Serve from byte 0 when the seek target sits at the segment's head: the
                // transcode created this segment from byte 0 (moov + fragments), so the
                // browser-visible total equals the file size — the same coordinate view
                // the initial raw-path response used. Only mid-segment seeks need the
                // fragment offset (baseOffset > 0). Segments carry a 0-based relative
                // timeline (tfdt starts at 0), so lookups convert to relative first.
                if (startSeconds - coverage.startSeconds < 0.5) {
                    off = 0L;
                }
                if (shouldLogStreamStart(streamSessionKey(videoId, startSeconds, audioTrackIndex, qualityHeight) + "|segment")) {
                    LOG.info("Serving from segment {} (start={}s, baseOffset={}) for video {} (start={}s)",
                             coverage.file.getFileName(), coverage.startSeconds, off, videoId, startSeconds);
                }
                // Touch the segment mtime so the LRU tier keeps it fresh.
                try {
                    java.nio.file.Files.setLastModifiedTime(coverage.file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException e) {
                    LOG.debug("Failed to refresh mtime of segment {}: {}", coverage.file, e.getMessage());
                }
                return streamFromSegment(video, videoFile, coverage.file, startSeconds, rangeHeader, audioTrackIndex, qualityHeight, traceId, estimatedFinalSize, off, coverage.startSeconds);
            }
        }

        // Temporary path: instead of blocking with 409 CONVERTING and forcing an
        // in-place conversion of the source file, serve the stream on-the-fly.
        // TranscodingService performs the codec conversion (video → H.264,
        // audio → AAC) live and caches the result in the temp dir; the original
        // MKV/MP4 source file is never modified.
        if (transcodeNeeded) {
            LOG.info("[STREAM] videoId={} codec not playable by this client — serving on-the-fly transcode (source file untouched)", videoId);
        }

        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] Falling back to direct remux for video {}", traceId, videoId);
        LOG.debug("Fallback direct remux stream for video {} (start={}s, audio={})",
                  videoId, startSeconds, audioTrackIndex >= 0 ? audioTrackIndex : "default");
        return streamRemuxedMKVDirect(video, videoFile, startSeconds, userAgent, audioTrackIndex, qualityHeight, traceId, rangeHeader);
    }

    private Response streamRemuxedMKVDirect(Models.Video.Video video, File videoFile, double startSeconds, String userAgent, int audioTrackIndex, int qualityHeight, String traceId, String rangeHeader) {
        final Long videoId = video.id;
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamRemuxedMKVDirect: videoId={} start={}s", traceId, videoId, startSeconds);
        // Create (or reuse) the growing segment file + sidecar; the transcode writes into it.
        java.nio.file.Path cacheFile = segmentCacheService.startSegment(videoId, startSeconds, audioTrackIndex, qualityHeight, videoFile.toPath());
        if (cacheFile == null) {
            LOG.error("Failed to create segment for video {} at {}s", videoId, startSeconds);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // The transcode runs in the background writing ONLY to the segment file. The browser
        // is served from that file via streamFromSegment (write-ahead wait + 206/Content-Range),
        // the same path every subsequent range request uses. Firefox's MoofParser cannot decode
        // a raw growing pipe (empty_moov + default_base_moof, no write-ahead): a moof is read
        // before its mdat has been written, so sample ranges are garbage -> "Invalid H264 content".
        // Chrome tolerates the raw pipe; Firefox does not, so the first request must not stream it.
        final java.nio.file.Path fCacheFile = cacheFile;
        Thread transcodeThread = new Thread(() -> {
            try {
                transcodingService.streamRemuxedMKV(video, videoFile, startSeconds, userAgent,
                        java.io.OutputStream.nullOutputStream(), audioTrackIndex, qualityHeight, fCacheFile);
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Background transcode for video {} failed: {}", videoId, e.getMessage());
                }
            } finally {
                transcodingService.releaseTranscode(videoId, startSeconds, audioTrackIndex, qualityHeight, false);
            }
        }, "direct-transcode-" + videoId);
        transcodeThread.setDaemon(true);
        transcodeThread.start();

        final long estimatedFinalSize = (long) (videoFile.length() * 1.10);
        return streamFromSegment(video, videoFile, cacheFile, startSeconds, rangeHeader, audioTrackIndex, qualityHeight, traceId, estimatedFinalSize, 0L, startSeconds);
    }

    private Response streamFromSegment(Models.Video.Video video, File videoFile, java.nio.file.Path tempFile, double startSeconds,
                                       String rangeHeader, int audioTrackIndex, int qualityHeight, String traceId, long estimatedFinalSize,
                                       long baseOffset, double segmentStart) {
        final Long videoId = video.id;
        if (traceId != null && !traceId.isBlank()) LOG.info("[trace:{}] streamFromSegment: videoId={} start={}s segmentStart={}s baseOffset={} range={}", traceId, videoId, startSeconds, segmentStart, baseOffset, rangeHeader != null ? rangeHeader.substring(0, Math.min(50, rangeHeader.length())) : "none");

        // Register as a reader immediately, before any wait: the segment's writer
        // transcode must never be killed (disconnect handler / supersede guard) while
        // this request is in flight, even during the waitForFile window. Released on
        // every exit path below, and by the StreamingOutput's finally for the normal
        // stream (the async body outlives this method call).
        segmentCacheService.acquireReader(tempFile);

        // Fast-fail: if the segment's writer transcode has failed, return 503 instead of waiting 90s.
        // Also delete the dead segment's partial file so the client's retry falls through to a fresh
        // transcode (which clears the failed flag) instead of 503-ing again.
        if (transcodingService.isTranscodeFailed(videoId, segmentStart, audioTrackIndex, qualityHeight, false)) {
            LOG.warn("Transcode for segment {} already failed for video {} (segmentStart={}s, audio={}, quality={}), returning 503",
                     tempFile.getFileName(), videoId, segmentStart, audioTrackIndex, qualityHeight);
            transcodingService.releaseTranscode(videoId, segmentStart, audioTrackIndex, qualityHeight, false);
            transcodingService.deleteCacheFile(tempFile);
            segmentCacheService.releaseReader(tempFile);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }

        long fileLength;
        try {
            transcodingService.waitForFile(tempFile, 65536, videoId, segmentStart, audioTrackIndex, qualityHeight);
            fileLength = Files.size(tempFile);
        } catch (IOException e) {
            LOG.error("Cannot get size of temp file for video {}: {}", videoId, e.getMessage());
            segmentCacheService.releaseReader(tempFile);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Mid-segment serves (baseOffset > 0) must prepend the file's init segment (ftyp + moov,
        // bytes [0, initLen)) so the browser can parse track metadata — a bare moof makes Firefox
        // fail metadata parsing (NS_ERROR_DOM_MEDIA_METADATA_ERR). The browser-visible stream is
        // then [init][payload from baseOffset], so browser byte B maps to file byte
        // (B < initLen ? B : baseOffset + (B - initLen)), and the visible total is
        // initLen + (fileSize - baseOffset). When baseOffset == 0 the file already starts with the
        // init segment, so no prefix is added and the total stays the plain file size.
        long initLen = 0;
        if (baseOffset > 0) {
            try {
                initLen = FragmentedMp4Seeker.firstFragmentOffset(tempFile);
            } catch (IOException e) {
                LOG.warn("Cannot determine init segment length for {}: {}", tempFile.getFileName(), e.getMessage());
                initLen = 0;
            }
            if (initLen > baseOffset) {
                // Seek landed inside the header region: prefixing [0, baseOffset) and serving from
                // baseOffset reproduces the whole file from byte 0 — always parseable.
                initLen = baseOffset;
            }
        }
        long browserTotal = Math.max(0, initLen + (fileLength - baseOffset));

        long start = 0;
        long end = browserTotal - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String rangeValue = rangeHeader.substring(6).trim();
                if (rangeValue.startsWith("-")) {
                    long suffix = Long.parseLong(rangeValue.substring(1));
                    start = Math.max(0, browserTotal - suffix);
                    end = browserTotal - 1;
                } else {
                    String[] parts = rangeValue.split("-", -1);
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        end = Long.parseLong(parts[1].trim());
                    } else {
                        end = browserTotal - 1;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Invalid Range header '{}': {}", rangeHeader, e.getMessage());
                start = 0;
                end = browserTotal - 1;
            }
        }

        // A client sends a trailing continuation (bytes=<size>-) once the stream ends.
        // On a COMPLETE segment the file never grows, so a start at/past the
        // browser-visible total is unsatisfiable: answer 416 immediately with the final
        // total. Waiting out waitForFile (then 503) makes Firefox treat the stream as
        // failed and restart playback from byte 0; a 416 is the RFC-correct "no more
        // data" EOF signal and plays as a clean end of stream.
        if (transcodingService.isCacheFileComplete(tempFile) && start >= browserTotal) {
            LOG.info("Client requested bytes {} >= total {} on completed segment {} — returning 416",
                     start, browserTotal, tempFile.getFileName());
            transcodingService.releaseTranscode(videoId, segmentStart, audioTrackIndex, qualityHeight, false);
            segmentCacheService.releaseReader(tempFile);
            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + browserTotal)
                    .header("Content-Length", "0")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Type", "video/mp4")
                    .header("Access-Control-Allow-Origin", "*")
                    .build();
        }

        // Validate range bounds — for streaming files, do NOT reset start=0.
        // That would send Safari duplicate data from byte 0 and freeze playback.
        // Save original end before clamping so waitForFile uses the correct target.
        long originalEnd = end;
        if (end >= browserTotal && start < browserTotal) {
            end = browserTotal - 1;
        }
        if (start > end) {
            // Requested range starts past current EOF — preserve original end
            // as the wait target so waitForFile blocks until data is produced.
            end = originalEnd;
        }

        String etag;
        if (baseOffset == 0) {
            // Non-segment (exact-key cache) case — keep the legacy ETag.
            etag = Integer.toHexString((video.id + "|" + String.format(java.util.Locale.ROOT, "%.3f", startSeconds) + "|" + audioTrackIndex + "|" + qualityHeight).hashCode());
        } else {
            // Segment-served ETag: segmentId + segment start, so a switch between
            // segment-served and transcode-served invalidates browser range caches.
            etag = Integer.toHexString((tempFile.getFileName().toString() + "|" + String.format(java.util.Locale.ROOT, "%.3f", segmentStart)).hashCode());
        }

        LOG.debug("Stream: range {}-{} (len={}) for video {} (etag={})", start, end, end - start + 1, videoId, etag);

        // Wait for the requested range to be available before sending headers.
        // Add a 1MB write-ahead margin for growing segments so Firefox never reads a
        // moof atom whose referenced mdat data hasn't been written yet. The grow target
        // is file-relative: map the browser coordinate back to the file byte it lives at.
        try {
            boolean xcodeFinished = transcodingService.isCacheFileComplete(tempFile);
            // For a growing segment, wait only until the requested START byte exists —
            // the streaming loop below feeds the rest incrementally as the transcode
            // produces it. Waiting on `end` makes every range response block until the
            // segment physically grows to that byte, so playback stalls behind the
            // transcode ("won't play until it finishes"). A finished segment is served
            // whole, so waiting on `end` is then a no-op.
            long targetByte = (xcodeFinished || start >= browserTotal) ? end : start;
            long waitTarget = (targetByte < initLen)
                    ? targetByte + 1
                    : baseOffset + (targetByte - initLen) + 1;
            if (!xcodeFinished) waitTarget += 1024 * 1024;
            // Cap wait target: the streaming loop reads incrementally as the transcode
            // produces data, so we only need enough to start reading. Without this cap,
            // requesting the full file (bytes 0-2.5GB) would block for 90s waiting on a
            // file that's still being transcoded.
            if (!xcodeFinished) {
                waitTarget = Math.min(waitTarget, fileLength + 5 * 1024 * 1024);
            }
            transcodingService.waitForFile(tempFile, waitTarget, videoId, segmentStart, audioTrackIndex, qualityHeight);
        } catch (IOException e) {
            LOG.error("Timeout waiting for requested byte range {}-{} for video {}: {}", start, end, videoId, e.getMessage());
            transcodingService.releaseTranscode(videoId, segmentStart, audioTrackIndex, qualityHeight, false);
            long currentSize;
            try {
                currentSize = Files.size(tempFile);
            } catch (IOException ex) {
                currentSize = 0;
            }
            // If the segment's writer died while we waited, its partial file is poison — the next
            // request would 503 again. Delete it so the client's retry falls through to a fresh
            // transcode (which clears the failed flag). A live but slow transcode is left untouched.
            if (transcodingService.isTranscodeFailed(videoId, segmentStart, audioTrackIndex, qualityHeight, false)) {
                LOG.info("Transcode failed for video {} (segmentStart={}s) while waiting for range {}-{} — deleting stale segment {}",
                         videoId, segmentStart, start, end, tempFile.getFileName());
                transcodingService.deleteCacheFile(tempFile);
            }
            segmentCacheService.releaseReader(tempFile);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Content-Range", "bytes */" + Math.max(0, initLen + (currentSize - baseOffset)))
                    .build();
        }

        boolean transcodeFinished = transcodingService.isCacheFileComplete(tempFile);
        long currentFileSize;
        try {
            if (transcodeFinished) {
                currentFileSize = Files.size(tempFile);
            } else {
                // The transcode writes moof+mdat incrementally, so the trailing box is often
                // partial. Firefox's MoofParser rejects a stream whose sample table points at
                // mdat bytes that aren't on disk ("H264ChangeMonitor: Invalid H264 content"),
                // so never serve past the last fully-written box. Wait (bounded) for the
                // fragment containing the requested byte to be complete, then clamp to it.
                long startFileByte = baseOffset + Math.max(0, start - initLen);
                long deadline = System.currentTimeMillis() + 30_000L;
                long complete;
                while (true) {
                    complete = FragmentedMp4Seeker.completeLength(tempFile);
                    if (complete > startFileByte) {
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("Timeout waiting for fragment containing byte " + startFileByte);
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for fragment", ie);
                    }
                }
                currentFileSize = complete;
            }
        } catch (IOException e) {
            LOG.error("Segment {} not ready for range {}-{} for video {}: {}", tempFile.getFileName(), start, end, videoId, e.getMessage());
            segmentCacheService.releaseReader(tempFile);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Content-Range", "bytes */" + Math.max(0, initLen + (fileLength - baseOffset)))
                    .build();
        }
        long currentBrowserTotal = Math.max(0, initLen + (currentFileSize - baseOffset));

        // Content-Length: only up to what the file actually has after waitForFile
        long contentLength;
        if (start < currentBrowserTotal) {
            long adjustedEnd = Math.min(end, currentBrowserTotal - 1);
            contentLength = adjustedEnd - start + 1;
        } else {
            contentLength = 0;
        }

        // Check for discontinuity
        boolean hasDiscontinuity = transcodingService.hasTranscodeDiscontinuity(videoId, segmentStart, audioTrackIndex, qualityHeight, false);

        final long finalStart = start;
        final long finalEnd = end;
        final long finalBaseOffset = baseOffset;
        final long finalInitLen = initLen;

        StreamingOutput streamingOutput = output -> {
            // The reader was acquired at method entry; release it only when this async
            // body finishes (the stream outlives the method call), so the merge pass
            // never deletes the segment mid-serve.
            try {
                try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "r")) {
                    byte[] buffer = new byte[65536];
                    // For range requests, send exactly the requested range.
                    // For non-range (segment still growing), send until the writer finishes.
                    long remaining = (rangeHeader != null || transcodeFinished) ? contentLength : Long.MAX_VALUE;

                    // Region 1: init segment prefix (browser bytes [0, finalInitLen) == file bytes).
                    // Only served when the request starts inside it; the moov is written first, so
                    // these bytes exist as soon as the file does.
                    long headerEnd = Math.min(finalInitLen - 1, finalEnd);
                    if (finalStart <= headerEnd) {
                        raf.seek(finalStart);
                        long headerBytes = headerEnd - finalStart + 1;
                        while (headerBytes > 0 && remaining > 0) {
                            int readSize = (int) Math.min(buffer.length, headerBytes);
                            if (remaining != Long.MAX_VALUE) readSize = (int) Math.min(readSize, remaining);
                            int read = raf.read(buffer, 0, readSize);
                            if (read <= 0) break;
                            output.write(buffer, 0, read);
                            headerBytes -= read;
                            if (remaining != Long.MAX_VALUE) remaining -= read;
                        }
                    }

                    // Region 2: payload from baseOffset. Browser byte B >= finalInitLen lives at
                    // file byte (finalBaseOffset + (B - finalInitLen)).
                    long payloadStart = Math.max(finalStart, finalInitLen);
                    if (remaining > 0 && payloadStart <= finalEnd) {
                        raf.seek(finalBaseOffset + (payloadStart - finalInitLen));
                        while (remaining > 0) {
                            int readSize = (remaining == Long.MAX_VALUE) ? buffer.length : (int) Math.min(buffer.length, remaining);
                            int read = raf.read(buffer, 0, readSize);
                            if (read == -1) {
                                // Check if the segment's writer is done — no more data will come.
                                if (transcodingService.isCacheFileComplete(tempFile) || !transcodingService.isCacheBeingWritten(tempFile)) {
                                    LOG.debug("Segment writer finished or abandoned, stopping stream for video {}", videoId);
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
                }
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    LOG.error("Streaming error for segment file of video {}: {}", videoId, e.getMessage());
                }
            } finally {
                segmentCacheService.releaseReader(tempFile);
                transcodingService.releaseTranscode(videoId, segmentStart, audioTrackIndex, qualityHeight, false);
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
                long responseEnd = Math.min(finalEnd, currentBrowserTotal - 1);
                // Segment total = actual file bytes past baseOffset (NOT the inflated estimate).
                long finishedTotal = currentBrowserTotal;
                responseBuilder.header("Content-Range", "bytes " + finalStart + "-" + responseEnd + "/" + finishedTotal);
            } else {
                // Report a STABLE total (the estimated final size) so Firefox's media cache
                // sees one consistent resource while the segment grows. A total that grows on
                // every request makes Firefox's MoofParser compute sample ranges against a
                // changing resource size → garbage samples → "Invalid H264 content". This is
                // the same convention the Safari probe already uses. Never report less than
                // what currently exists so the requested end stays satisfiable.
                long reportedSize = Math.max(Math.max(currentBrowserTotal, finalEnd + 1), estimatedFinalSize);
                responseBuilder.header("Content-Range", "bytes " + finalStart + "-" + Math.min(finalEnd, reportedSize - 1) + "/" + reportedSize);
            }
        }

        if (hasDiscontinuity) {
            responseBuilder.header("X-Stream-Discontinuity", "true");
        }

        return responseBuilder.build();
    }

    private Response streamFromTempFile(Models.Video.Video video, File videoFile, java.nio.file.Path tempFile, double startSeconds,
                                        String rangeHeader, int audioTrackIndex, int qualityHeight, String traceId, long estimatedFinalSize) {
        return streamFromSegment(video, videoFile, tempFile, startSeconds, rangeHeader, audioTrackIndex, qualityHeight, traceId, estimatedFinalSize, 0, startSeconds);
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
