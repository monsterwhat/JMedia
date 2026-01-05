package API.Rest;

import Controllers.VideoController;
import Services.VideoService;
import Services.VideoHistoryService;
import Services.VideoStateService;
import io.quarkus.qute.Template;
import io.quarkus.qute.ValueResolver;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
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
    Template videoEntryFragment;

    @Inject @io.quarkus.qute.Location("carouselsFragment.html")
    Template carouselsFragment;

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoStateService videoStateService;

    // Jackson ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper = new ObjectMapper();


    // Helper functions for Qute templates
    private String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int remainingSeconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }
    
    // Helper record for passing series title (raw and encoded) to templates
    public record SeriesTitleEntry(String rawTitle, String encodedTitle, String cssId) {}


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
        List<VideoService.VideoDTO> movies = paginatedVideos.videos();
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
        AtomicInteger counter = new AtomicInteger(0);
        List<SeriesTitleEntry> seriesTitleEntries = seriesTitles.stream()
                .map(title -> new SeriesTitleEntry(
                    title, 
                    URLEncoder.encode(title, StandardCharsets.UTF_8),
                    "show_" + counter.getAndIncrement()
                ))
                .collect(Collectors.toList());
        
        return seriesListContent
                .data("seriesTitleEntries", seriesTitleEntries)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons-fragment")
    @Blocking
    public String getSeasonsFragment(@PathParam("seriesTitle") String seriesTitle) {
        // JAX-RS automatically decodes path parameters, so seriesTitle here is already decoded.
        System.out.println("DEBUG: getSeasonsFragment called with seriesTitle: " + seriesTitle);
        List<Integer> seasonNumbers = videoService.findSeasonNumbersForSeries(seriesTitle);
        System.out.println("DEBUG: Found season numbers: " + seasonNumbers);
        return seasonListContent
                .data("seriesTitle", seriesTitle) // Pass raw seriesTitle for display in template
                .data("encodedSeriesTitle", URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8)) // Pass encoded for URLs
                .data("seasonNumbers", seasonNumbers)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment")
    @Blocking
    public String getEpisodesFragment(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        // JAX-RS automatically decodes path parameters, so seriesTitle here is already decoded.
        List<VideoService.VideoDTO> episodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
        return episodeListContent
                .data("episodes", episodes)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("encodedSeriesTitle", URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8)) // Pass encoded for URLs
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
        List<VideoService.VideoDTO> queuePage = paginatedQueue.videos();
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
public record VideoWithIndex(VideoService.VideoDTO video, int index) {

    }
 
    
    // JSON serialization helper for templates
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @GET
    @Path("/carousels-view")
    @Blocking
    public String getCarouselsView() {
        // Get data for carousels using algorithms
        Map<String, Object> carouselData = getCarouselData();

        return carouselsFragment
                .data("featured", carouselData.get("featured"))
                .data("newReleases", carouselData.get("newReleases"))
                .data("continueWatching", carouselData.get("continueWatching"))
                .data("trending", carouselData.get("trending"))
                .data("movies", carouselData.get("movies"))
                .data("tvShows", carouselData.get("tvShows"))
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("json", (Function<Object, String>) this::toJson)
                .render();
    }

    @GET
    @Path("/test")
    @Blocking
    public String testEndpoint() {
        return "Video API is working";
    }

    @GET
    @Path("/debug")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public String debugEndpoint() {
        Map<String, Object> debug = new HashMap<>();
        
        try {
            // Count database records
            debug.put("mediaFiles_count", Models.MediaFile.count());
            debug.put("movies_count", Models.Movie.count());
            debug.put("episodes_count", Models.Episode.count());
            debug.put("shows_count", Models.Show.count());
            debug.put("seasons_count", Models.Season.count());
            debug.put("videoHistory_count", Models.VideoHistory.count());
            debug.put("videoState_count", Models.VideoState.count());
            
            // Get actual data for carousels
            Map<String, Object> carouselData = getCarouselData();
            debug.put("carousel_featured_size", ((List<?>)carouselData.get("featured")).size());
            debug.put("carousel_newReleases_size", ((List<?>)carouselData.get("newReleases")).size());
            debug.put("carousel_continueWatching_size", ((List<?>)carouselData.get("continueWatching")).size());
            debug.put("carousel_trending_size", ((List<?>)carouselData.get("trending")).size());
            debug.put("carousel_movies_size", ((List<?>)carouselData.get("movies")).size());
            debug.put("carousel_tvShows_size", ((List<?>)carouselData.get("tvShows")).size());
            
            // Sample some data
            List<Models.MediaFile> mediaFiles = Models.MediaFile.list("type", "video");
            debug.put("sample_mediafiles", mediaFiles.stream().limit(5).map(mf -> Map.of(
                "id", mf.id,
                "path", mf.path,
                "type", mf.type,
                "durationSeconds", mf.durationSeconds,
                "lastModified", mf.lastModified
            )).collect(Collectors.toList()));
            
            List<Models.Movie> movies = Models.Movie.findAll().page(0, 5).list();
            debug.put("sample_movies", movies.stream().map(m -> Map.of(
                "id", m.id,
                "title", m.title,
                "releaseYear", m.releaseYear,
                "videoPath", m.videoPath
            )).collect(Collectors.toList()));
            
            List<Models.Episode> episodes = Models.Episode.findAll().page(0, 5).list();
            debug.put("sample_episodes", episodes.stream().map(e -> Map.of(
                "id", e.id,
                "title", e.title,
                "seasonNumber", e.seasonNumber,
                "episodeNumber", e.episodeNumber,
                "videoPath", e.videoPath
            )).collect(Collectors.toList()));
            
        } catch (Exception e) {
            debug.put("error", e.getMessage());
            debug.put("stackTrace", Arrays.toString(e.getStackTrace()));
        }
        
        try {
            return objectMapper.writeValueAsString(debug);
        } catch (Exception e) {
            return "{\"error\": \"Failed to serialize debug info\"}";
        }
    }



    @GET
    @Path("/carousels-fragment")
    @Blocking
    public String getCarouselsFragment() {
        // Same as view method for consistency
        return getCarouselsView();
    }

    /**
     * Get carousel data using algorithms
     */
    private Map<String, Object> getCarouselData() {
        Map<String, Object> data = new HashMap<>();

        try {
            // Featured content: Based on trending + new releases
            List<Long> trendingIds = videoHistoryService.getTrendingVideoIds(30, 5);
            Map<Long, Integer> playCounts = videoHistoryService.getPlayCountsForVideos(trendingIds, 30);
            List<VideoService.VideoDTO> featured = videoService.getFeaturedContent(5, playCounts);
            data.put("featured", featured);

            // New releases: Content added in last 14 days
            List<VideoService.VideoDTO> newReleases = videoService.getNewReleases(14, 15);
            data.put("newReleases", newReleases);

            // Continue watching: Partially watched content
            List<VideoService.VideoDTO> continueWatching = videoStateService.getContinueWatching(10);
            data.put("continueWatching", continueWatching);

            // Trending: Most watched content in last 7 days
            List<Long> lastWeekTrending = videoHistoryService.getTrendingVideoIds(7, 15);
            List<VideoService.VideoDTO> trending = videoService.findByIds(lastWeekTrending);
            data.put("trending", trending);

            // Movies: Latest movies (20 items)
            VideoService.PaginatedVideos moviesPaginated = videoService.findPaginatedByMediaType("Movie", 1, 20);
            data.put("movies", moviesPaginated.videos());

            // TV Shows: Latest episodes by series
            List<VideoService.VideoDTO> tvShows = videoService.getLatestEpisodesBySeries(15);
            data.put("tvShows", tvShows);
            
            // FALLBACK: If all carousels are empty, provide basic video content
            boolean allEmpty = featured.isEmpty() && newReleases.isEmpty() && continueWatching.isEmpty() 
                             && trending.isEmpty() && moviesPaginated.videos().isEmpty() && tvShows.isEmpty();
            
            if (allEmpty) {
                System.out.println("DEBUG: All carousels empty, providing fallback content from all videos");
                List<VideoService.VideoDTO> allVideos = videoService.findAll();
                if (!allVideos.isEmpty()) {
                    // Use some videos as featured
                    data.put("featured", allVideos.stream().limit(5).collect(Collectors.toList()));
                    // Use some as new releases
                    data.put("newReleases", allVideos.stream().skip(5).limit(15).collect(Collectors.toList()));
                    // Use some as trending
                    data.put("trending", allVideos.stream().skip(20).limit(15).collect(Collectors.toList()));
                    // Use all as movies fallback
                    data.put("movies", allVideos.stream().limit(20).collect(Collectors.toList()));
                    System.out.println("DEBUG: Provided fallback content - total videos: " + allVideos.size());
                }
            }

        } catch (Exception e) {
            // If any carousel data fails, provide empty lists to avoid template errors
            System.err.println("Error loading carousel data: " + e.getMessage());
            data.put("featured", Collections.emptyList());
            data.put("newReleases", Collections.emptyList());
            data.put("continueWatching", Collections.emptyList());
            data.put("trending", Collections.emptyList());
            data.put("movies", Collections.emptyList());
            data.put("tvShows", Collections.emptyList());
        }

        return data;
    }
}
