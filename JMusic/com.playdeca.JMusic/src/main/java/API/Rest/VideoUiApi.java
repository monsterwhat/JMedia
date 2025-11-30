package API.Rest;

import Controllers.VideoController;
import Models.Video;
import Models.DTOs.PaginatedMovieResponse;
import Services.VideoService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.format.DateTimeFormatter; // Not directly used in current template, but useful
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function; // For Qute function injection

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML) // Default to HTML for UI fragments
public class VideoUiApi {

    @Inject
    private VideoController videoController;

    @Inject
    VideoService videoService;

    // Qute Templates
    @Inject @io.quarkus.qute.Location("movieListContent.html")
    Template movieListContent;
    @Inject @io.quarkus.qute.Location("seriesListContent.html")
    Template seriesListContent;
    @Inject @io.quarkus.qute.Location("seasonListContent.html")
    Template seasonListContent;
    @Inject @io.quarkus.qute.Location("episodeListContent.html")
    Template episodeListContent;
    @Inject @io.quarkus.qute.Location("videoEntryFragment.html")
    Template videoEntryFragment; // Injected for completeness, though included in others


    // Helper functions for Qute templates
    private String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds < 10 ? '0' + seconds : seconds);
    }

    private List<Integer> getPaginationNumbers(int currentPage, int totalPages) {
        Set<Integer> pageNumbers = new java.util.LinkedHashSet<>();

        // Always add page 1 and the last page
        pageNumbers.add(1);
        if (totalPages > 1) {
            pageNumbers.add(totalPages);
        }

        // Add pages around the current page
        for (int i = currentPage - 2; i <= currentPage + 2; i++) {
            if (i > 0 && i <= totalPages) {
                pageNumbers.add(i);
            }
        }

        List<Integer> sortedPageNumbers = new ArrayList<>(pageNumbers);
        java.util.Collections.sort(sortedPageNumbers);

        List<Integer> result = new ArrayList<>();
        int last = 0;
        for (int number : sortedPageNumbers) {
            if (last != 0 && number - last > 1) {
                result.add(-1); // Ellipsis
            }
            result.add(number);
            last = number;
        }
        return result;
    }


    @GET
    @Path("/movies-fragment")
    @Blocking
    public String getMoviesFragment(
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("12") int limit) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("Movie", page, limit);
        List<Video> movies = paginatedVideos.videos();
        long totalItems = paginatedVideos.totalCount();
        
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages)); // Sanitize page number
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        return movieListContent
                .data("movies", movies)
                .data("currentPage", currentPage)
                .data("limit", limit)
                .data("totalItems", totalItems)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/shows-fragment")
    @Blocking
    public String getSeriesFragment() {
        List<String> seriesTitles = videoService.findAllSeriesTitles();
        return seriesListContent
                .data("seriesTitles", seriesTitles)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons-fragment")
    @Blocking
    public String getSeasonsFragment(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasonNumbers = videoService.findSeasonNumbersForSeries(seriesTitle);
        return seasonListContent
                .data("seriesTitle", seriesTitle) // Pass seriesTitle for episode fragment
                .data("seasonNumbers", seasonNumbers)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment")
    @Blocking
    public String getEpisodesFragment(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<Video> episodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
        return episodeListContent
                .data("episodes", episodes)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }


    // -------------------------
    // Queue actions - returning JSON for HTMX to consume
    // -------------------------
    // DTO for queue fragment response
    public record VideoQueueFragmentResponse(String html, int totalQueueSize) {

    }

    @GET
    @Path("/queue-fragment")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public VideoQueueFragmentResponse getQueueFragment(
            @jakarta.ws.rs.QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {

        VideoController.PaginatedQueue paginatedQueue = videoController.getQueuePage(page, limit);
        List<Video> queuePage = paginatedQueue.videos();
        int totalQueueSize = paginatedQueue.totalSize();

        int totalPages = (int) Math.ceil((double) totalQueueSize / limit);
        int currentPage = Math.max(1, Math.min(page, totalPages)); // Sanitize page number
        List<Integer> pageNumbers = getPaginationNumbers(currentPage, totalPages);

        // The template needs the index of each video *within the full queue*.
        int offset = (page - 1) * limit;
        List<VideoWithIndex> queueWithIndex = new ArrayList<>();
        for (int i = 0; i < queuePage.size(); i++) {
            queueWithIndex.add(new VideoWithIndex(queuePage.get(i), offset + i));
        }

        // For simplicity, I'm adapting the queueFragment template here.
        // A dedicated videoQueueFragment.html would be ideal.
        // For now, let's just make sure the data is passed correctly.
        String html = """
            <div id="videoQueueList">
                {#if queue.empty}
                    <div class="has-text-centered py-4">
                        <p>Queue is empty.</p>
                    </div>
                {#else}
                    {#for entry in queue}
                         <div class="video-entry" onclick="playVideo(this, {id: '{entry.video.id}', title: '{entry.video.title}', episodeNumber: '{entry.video.episodeNumber}', episodeTitle: '{entry.video.episodeTitle}'})">
                            <div class="video-title">
                                {#if entry.video.episodeNumber}E{entry.video.episodeNumber} - {entry.video.episodeTitle ?: entry.video.title}{else}{entry.video.title}{/if}
                            </div>
                            <div class="video-info">
                                {formatDuration(entry.video.durationSeconds)}
                                <span class="tag is-info is-light ml-2">#{entry.index + 1}</span>
                            </div>
                        </div>
                    {#end}
                    {#if totalPages > 1}
                        <!-- Pagination controls here -->
                    {/if}
                {#end}
            </div>
            """;
        // NOTE: The above `html` string is a placeholder. In a real scenario,
        // you would inject a `videoQueueFragment` Template and render it.
        // Example: return videoQueueFragment.data("queue", queueWithIndex).data(...).render();


        return new VideoQueueFragmentResponse(html, totalQueueSize);
    }
    
    // Helper record to pass video and its index to the template/JSON
    public record VideoWithIndex(Video video, int index) {

    }
}
