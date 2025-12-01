package API.Rest;

import API.ApiResponse;
import Models.Video;
import Models.DTOs.PaginatedMovieResponse; // Import the new DTO
import Services.SettingsService;
import Services.VideoImportService;
import Services.VideoService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.HeaderParam;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    ManagedExecutor executor; // Inject ManagedExecutor

    private ExecutorService reloadExecutor; // Dedicated executor for long-running reload tasks

    @PostConstruct
    void init() {
        this.reloadExecutor = Executors.newSingleThreadExecutor(); // Single thread for orchestration
        LOG.info("Initialized reloadExecutor with a single thread.");
    }

    @PreDestroy
    void shutdownExecutor() {
        LOG.info("Shutting down reloadExecutor.");
        reloadExecutor.shutdownNow();
    }

    @GET
    @Path("/stream/{videoId}")
    @Produces("video/mp4")
    public Response streamVideo(@PathParam("videoId") Long videoId, @HeaderParam("Range") String rangeHeader) {
        Video video = videoService.find(videoId);
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

        java.nio.file.Path filePath = Paths.get(videoLibraryPath, video.getPath());
        File videoFile = filePath.toFile();

        if (!videoFile.exists() || !videoFile.isFile()) {
            LOG.warn("Video file not found: {}", filePath);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long fileLength = videoFile.length();
        long start = 0;
        long end = fileLength - 1;

        // ---- RANGE PARSING ----
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
        final long rangeEnd = end;

        // ---- STREAMING OUTPUT ----
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
                if (isClientDisconnect(e)) {
                } else {
                    LOG.error("Streaming error for {}: {}", filePath, e.getMessage(), e);
                }
            }
        };

        // ---- RANGE RESPONSE ----
        if (rangeHeader != null) {
            return Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(stream)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", contentLength)
                    .header("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + fileLength)
                    .build();
        }

        // ---- FULL FILE RESPONSE ----
        return Response.ok(stream)
                .header("Accept-Ranges", "bytes")
                .header("Content-Type", "video/mp4")
                .header("Content-Length", fileLength)
                .build();
    }

    @POST
    @Path("/scan")
    public Response scanVideoLibrary() {
        executor.submit(() -> {
            videoImportService.scanVideoLibrary();
        }, "VideoLibraryScanThread"); // Provide a thread name for debugging

        return Response.ok(ApiResponse.success("Video library scan started")).build();
    }

    @POST
    @Path("/reload-metadata")
    public Response reloadVideoMetadata() {
        reloadExecutor.submit(() -> {
            videoImportService.reloadAllVideoMetadata();
        }, "VideoMetadataReloadThread"); // Provide a thread name for debugging

        return Response.ok(ApiResponse.success("Video metadata reload started")).build();
    }

    @GET
    @Path("/videos")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllVideos(@QueryParam("mediaType") String mediaType) {
        List<Video> videos;
        if (mediaType != null && !mediaType.isBlank()) {
            videos = videoService.findByMediaType(mediaType);
        } else {
            videos = videoService.findAll();
        }
        return Response.ok(videos).build();
    }

    @GET
    @Path("/shows")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllSeriesTitles() {
        List<String> seriesTitles = videoService.findAllSeriesTitles();
        System.out.println("Returning series titles: " + seriesTitles);
        return Response.ok(seriesTitles).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSeasonsForSeries(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasonNumbers = videoService.findSeasonNumbersForSeries(seriesTitle);
        System.out.println("Returning season numbers for series '" + seriesTitle + "': " + seasonNumbers);
        return Response.ok(seasonNumbers).build();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEpisodesForSeason(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<Video> episodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
        System.out.println("Returning episodes for series '" + seriesTitle + "', season '" + seasonNumber + "': " + episodes);
        return Response.ok(episodes).build();
    }

    @GET
    @Path("/movies")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllMovies(
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("Movie", page, limit);
        List<Video> movies = paginatedVideos.videos();
        long totalItems = paginatedVideos.totalCount();

        int totalPages = (int) Math.ceil((double) totalItems / limit);

        PaginatedMovieResponse response = new PaginatedMovieResponse(movies, page, limit, totalItems, totalPages);
        return Response.ok(response).build();
    }

    private boolean isClientDisconnect(Throwable e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }

        msg = msg.toLowerCase();

        return msg.contains("broken pipe")
                || msg.contains("connection reset")
                || msg.contains("connection aborted")
                || msg.contains("connection has been closed") 
                || msg.contains("stream closed")
                || msg.contains("remote host closed")
                || msg.contains("closed channel")
                || msg.contains("socket closed")
                || msg.contains("failed to write");   
    }

}
