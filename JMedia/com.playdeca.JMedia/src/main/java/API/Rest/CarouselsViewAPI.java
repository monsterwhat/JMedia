package API.Rest;

import Services.VideoService;
import Services.VideoHistoryService;
import Services.VideoStateService;
import io.quarkus.qute.Template;
import io.quarkus.qute.ValueResolver;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
public class CarouselsViewAPI {

    @Inject
    private VideoService videoService;

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoStateService videoStateService;

    // Qute Templates
    @Inject
    @io.quarkus.qute.Location("carouselsFragment.html")
    Template carouselsFragment;

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
                .data("formatDuration", (Function<Integer, String>) this::formatDurationForTemplate)
                .data("json", (Function<Object, String>) this::toJson)
                .render();
    }

    @GET
    @Path("/carousels-fragment")
    @Blocking
    public String getCarouselsFragment() {
        // Same as view method for consistency
        return getCarouselsView();
    }

    private String formatDurationForTemplate(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds < 0) {
            return "0:00";
        }
        int minutes = totalSeconds / 60;
        int remainingSeconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
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

        } catch (Exception e) {
            // If any carousel data fails, provide empty lists to avoid template errors
            System.err.println("Error loading carousel data: " + e.getMessage());
            data.put("featured", List.of());
            data.put("newReleases", List.of());
            data.put("continueWatching", List.of());
            data.put("trending", List.of());
            data.put("movies", List.of());
            data.put("tvShows", List.of());
        }

        return data;
    }
}
