package API.Rest;

import API.ApiResponse;
import Models.Video;
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

    @GET
    @Path("/stream/{videoId}")
    @Produces("video/mp4") // Assuming MP4 for simplicity, adjust if other formats are needed
    public Response streamVideo(@PathParam("videoId") Long videoId) {
        Video video = videoService.find(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            LOG.error("Video library path is not configured.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Video library path not configured.").build();
        }

        java.nio.file.Path filePath = Paths.get(videoLibraryPath, video.getPath());
        File videoFile = filePath.toFile();

        if (!videoFile.exists() || !videoFile.isFile()) {
            LOG.warn("Video file not found: {}", filePath);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        StreamingOutput streamingOutput = outputStream -> {
            try (InputStream fileInputStream = new FileInputStream(videoFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                LOG.error("Error streaming video file: {}", filePath, e);
                throw e; // Re-throw to indicate an an error during streaming
            }
        };

        return Response.ok(streamingOutput)
                .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                .header(HttpHeaders.CONTENT_LENGTH, videoFile.length())
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
        executor.submit(() -> {
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
    public Response getAllMovies() {
        List<Video> movies = videoService.findByMediaType("Movie");
        System.out.println("Returning all movies: " + movies);
        return Response.ok(movies).build();
    }
}


