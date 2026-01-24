package API.Rest;

import API.ApiResponse;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
public class VideoUiApi {

    private static final Logger LOG = LoggerFactory.getLogger(VideoUiApi.class);

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

    @Inject @io.quarkus.qute.Location("optimizedHeroFragment.html")
    Template optimizedHeroFragment;

    @Inject @io.quarkus.qute.Location("simpleCarouselsTemplate.html")
    Template simpleCarouselsTemplate;

    @Inject @io.quarkus.qute.Location("videoQueueFragment.html")
    Template videoQueueFragment;

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

String html = videoQueueFragment
                .data("queue", queueWithIndex)
                .data("currentPage", currentPage)
                .data("limit", limit)
                .data("totalQueueSize", totalQueueSize)
                .data("totalPages", totalPages)
                .data("pageNumbers", pageNumbers)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("json", (Function<Object, String>) this::toJson)
                .render();


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
    
    // Escape methods for HTML/JS safety
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }
    
    private String escapeJs(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("'", "\\'")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
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
    @Path("/hero-fragment")
    @Blocking
    public String getHeroFragment() {
        try {
            List<VideoService.VideoDTO> featured = new ArrayList<>();
            
            // Get all videos as base
            List<VideoService.VideoDTO> allVideos = videoService.findAll();
            LOG.info("Hero fragment: Total videos found: " + allVideos.size());
            
            if (allVideos.isEmpty()) {
                // No videos in library at all
                LOG.warn("Hero fragment: No videos available");
                featured = new ArrayList<>();
            } else {
                // Use first 5 videos as featured
                featured = allVideos.stream().limit(5).collect(Collectors.toList());
                LOG.info("Hero fragment: Using " + featured.size() + " featured videos");
            }
            
            return optimizedHeroFragment
                    .data("featured", featured)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("json", (Function<Object, String>) this::toJson)
                    .render();
                    
        } catch (Exception e) {
            LOG.error("Error generating hero fragment", e);
            
            // Return fallback hero content on error
            List<VideoService.VideoDTO> fallback = new ArrayList<>();
            return optimizedHeroFragment
                    .data("featured", fallback)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("json", (Function<Object, String>) this::toJson)
                    .render();
        }
    }

    @GET
    @Path("/test")
    @Blocking
    public String testEndpoint() {
        return "Video API is working";
}
    
    private String createSimpleCarousel(String title, List<VideoService.VideoDTO> items, String iconClass, String iconColor, String badge) {
        if (items == null || items.isEmpty()) {
            String emptyHtml = "<div class='streaming-carousel-section'>" +
                   "<div class='carousel-header'>" +
                   "<div class='carousel-title-section'>" +
                   "<i class='" + iconClass + "' style='color: " + iconColor + "'></i>" +
                   "<h2 class='carousel-title'>" + escapeHtml(title) + "</h2>" +
                   (badge != null ? "<span class='carousel-badge'>" + escapeHtml(badge) + "</span>" : "") +
                   "</div>" +
                   "</div>" +
                   "<div class='carousel-container'>" +
                   "<div class='streaming-carousel'>" +
                   "<div class='carousel-empty-state'>" +
                   "<i class='pi pi-video'></i>" +
                   "<h3>No " + escapeHtml(title).toLowerCase() + " found</h3>" +
                   "<p>Try scanning your library or adjusting filters</p>" +
                   "</div>" +
                   "</div>" +
                   "</div>" +
                   "</div>";
            return emptyHtml;
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<div class='streaming-carousel-section'>");
        html.append("<div class='carousel-header'>");
        html.append("<div class='carousel-title-section'>");
        html.append("<i class='").append(iconClass).append("' style='color: ").append(iconColor).append("'></i>");
        html.append("<h2 class='carousel-title'>").append(escapeHtml(title)).append("</h2>");
        if (badge != null) {
            html.append("<span class='carousel-badge'>").append(escapeHtml(badge)).append("</span>");
        }
        html.append("</div>");
        html.append("<div class='carousel-controls'>");
        html.append("<button class='carousel-nav-btn carousel-nav-left' onclick=\"scrollCarousel(this, 'left')\"><i class='pi pi-chevron-left'></i></button>");
        html.append("<button class='carousel-nav-btn carousel-nav-right' onclick=\"scrollCarousel(this, 'right')\"><i class='pi pi-chevron-right'></i></button>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='carousel-container'>");
        html.append("<div class='streaming-carousel'>");
        
        for (VideoService.VideoDTO item : items) {
            html.append(createSimpleCard(item));
        }
        
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }
    
    private String createSimpleCard(VideoService.VideoDTO item) {
        try {
            String title = item.title() != null ? item.title() : 
                         item.seriesTitle() != null ? item.seriesTitle() : "Unknown Title";
            String thumbnail = "/api/video/thumbnail/" + item.id();
            String itemJson = toJson(item);
            
            StringBuilder cardHtml = new StringBuilder();
            cardHtml.append("<div class='streaming-card' data-video-id='").append(item.id()).append("' onclick='handleCardClick(").append(itemJson).append(", event)' tabindex='0' role='button' aria-label='").append(escapeHtml(title)).append("'>");
            cardHtml.append("<div class='card-image-container'>");
            cardHtml.append("<img class='card-image' src='").append(thumbnail).append("' alt='").append(escapeHtml(title)).append("' loading='lazy'>");
            cardHtml.append("<div class='card-play-overlay'>");
            cardHtml.append("<button class='card-play-btn'><i class='pi pi-play'></i></button>");
            cardHtml.append("</div>");
            cardHtml.append("</div>");
            cardHtml.append("<div class='card-content'>");
            cardHtml.append("<div class='card-badges'>");
            
            if ("Movie".equals(item.type())) {
                cardHtml.append("<span class='card-badge movie-badge'>MOVIE</span>");
            } else if ("Episode".equals(item.type())) {
                cardHtml.append("<span class='card-badge episode-badge'>S").append(item.seasonNumber()).append("E").append(item.episodeNumber()).append("</span>");
            }
            
            cardHtml.append("</div>");
            cardHtml.append("<h3 class='card-title'>").append(escapeHtml(title)).append("</h3>");
            cardHtml.append("<p class='card-meta'>");
            
            if (item.releaseYear() != null && item.releaseYear() > 0) {
                cardHtml.append(item.releaseYear()).append(" • ");
            }
            if (item.durationSeconds() > 0) {
                cardHtml.append(formatDuration(item.durationSeconds()));
            }
            
            cardHtml.append("</p>");
            cardHtml.append("</div>");
            cardHtml.append("<div class='card-actions'>");
            cardHtml.append("<button class='card-action-btn primary' onclick=\"window.selectItem(").append(itemJson).append(", 'play')\"><i class='pi pi-play'></i><span>Play</span></button>");
            cardHtml.append("<button class='card-action-btn secondary' onclick=\"window.selectItem(").append(itemJson).append(", 'details')\"><i class='pi pi-info-circle'></i><span>Info</span></button>");
            cardHtml.append("<button class='card-action-btn tertiary' onclick=\"window.addToWatchlist(").append(itemJson).append(")\"><i class='pi pi-plus'></i></button>");
            cardHtml.append("</div>");
            cardHtml.append("</div>");
            
            return cardHtml.toString();
        } catch (Exception e) {
            return "<div class='card-error'>Error rendering card</div>";
        }
    }
    
    private String createSimpleCarouselHTML(String title, List<VideoService.VideoDTO> items, String iconClass, String iconColor, String badge, String carouselId) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='streaming-carousel-section'>");
        html.append("<div class='carousel-header'>");
        html.append("<div class='carousel-title-section'>");
        html.append("<i class='").append(iconClass).append("' style='color: ").append(iconColor).append("'></i>");
        html.append("<h2 class='carousel-title'>").append(escapeHtml(title)).append("</h2>");
        if (badge != null) {
            html.append("<span class='carousel-badge'>").append(escapeHtml(badge)).append("</span>");
        }
        html.append("</div>");
        html.append("<div class='carousel-controls'>");
        html.append("<button class='carousel-nav-btn carousel-nav-left' onclick=\"scrollCarousel('").append(carouselId).append("', 'left')\"><i class='pi pi-chevron-left'></i></button>");
        html.append("<button class='carousel-nav-btn carousel-nav-right' onclick=\"scrollCarousel('").append(carouselId).append("', 'right')\"><i class='pi pi-chevron-right'></i></button>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='carousel-container'>");
        html.append("<div class='streaming-carousel' id='").append(carouselId).append("'>");
        
        for (VideoService.VideoDTO item : items) {
            html.append(createSimpleCardHTML(item));
        }
        
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }
    
    private String createSimpleCardHTML(VideoService.VideoDTO item) {
        try {
            String title = item.title() != null ? item.title() : 
                         item.seriesTitle() != null ? item.seriesTitle() : "Unknown Title";
            String thumbnail = "/api/video/thumbnail/" + item.id();
            String itemJson = toJson(item);
            
            StringBuilder html = new StringBuilder();
            html.append("<div class='streaming-card' data-video-id='").append(item.id()).append("' onclick='handleCardClick(").append(itemJson).append(", event)' tabindex='0' role='button' aria-label='").append(escapeHtml(title)).append("'>");
            
            html.append("<div class='card-image-container'>");
            html.append("<img class='card-image' src='").append(thumbnail).append("' alt='").append(escapeHtml(title)).append("' loading='lazy'>");
            html.append("<div class='card-play-overlay'>");
            html.append("<button class='card-play-btn'><i class='pi pi-play'></i></button>");
            html.append("</div>");
            html.append("</div>");
            
            html.append("<div class='card-content'>");
            html.append("<div class='card-badges'>");
            
            if ("Movie".equals(item.type())) {
                html.append("<span class='card-badge movie-badge'>MOVIE</span>");
            } else if ("Episode".equals(item.type())) {
                html.append("<span class='card-badge episode-badge'>S").append(item.seasonNumber()).append("E").append(item.episodeNumber()).append("</span>");
            }
            
            html.append("</div>");
            html.append("<h3 class='card-title'>").append(escapeHtml(title)).append("</h3>");
            html.append("<p class='card-meta'>");
            
            if (item.releaseYear() != null && item.releaseYear() > 0) {
                html.append(item.releaseYear()).append(" • ");
            }
            if (item.durationSeconds() > 0) {
                html.append(formatDuration(item.durationSeconds()));
            }
            
            html.append("</p>");
            html.append("</div>");
            
            html.append("<div class='card-actions'>");
            html.append("<button class='card-action-btn primary' onclick='window.selectItem(").append(itemJson).append(", \"play\")'><i class='pi pi-play'></i><span>Play</span></button>");
            html.append("<button class='card-action-btn secondary' onclick='window.selectItem(").append(itemJson).append(", \"details\")'><i class='pi pi-info-circle'></i><span>Info</span></button>");
            html.append("<button class='card-action-btn tertiary' onclick='window.addToWatchlist(").append(itemJson).append(")'><i class='pi pi-plus'></i></button>");
            html.append("</div>");
            html.append("</div>");
            
            return html.toString();
        } catch (Exception e) {
            return "<div class='card-error'>Error rendering card</div>";
        }
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
            
            // Test videoService methods directly
            try {
                List<VideoService.VideoDTO> allVideos = videoService.findAll();
                debug.put("videoService_findAll_size", allVideos.size());
                debug.put("videoService_findAll_sample", allVideos.stream().limit(3).map(v -> Map.of(
                    "id", v.id(),
                    "title", v.title(),
                    "type", v.type()
                )).collect(Collectors.toList()));
            } catch (Exception e) {
                debug.put("videoService_findAll_error", e.getMessage());
            }
            
            // Test getFeaturedContent method
            try {
                Map<Long, Integer> emptyPlayCounts = new HashMap<>();
                List<VideoService.VideoDTO> featured = videoService.getFeaturedContent(5, emptyPlayCounts);
                debug.put("videoService_getFeatured_size", featured.size());
                debug.put("videoService_getFeatured_sample", featured.stream().limit(2).map(v -> Map.of(
                    "id", v.id(),
                    "title", v.title()
                )).collect(Collectors.toList()));
            } catch (Exception e) {
                debug.put("videoService_getFeatured_error", e.getMessage());
            }
            
            // Test trending methods
            try {
                List<Long> trendingIds = videoHistoryService.getTrendingVideoIds(30, 5);
                debug.put("trending_ids", trendingIds);
                debug.put("trending_ids_size", trendingIds.size());
            } catch (Exception e) {
                debug.put("trending_ids_error", e.getMessage());
            }
            
            // Get actual data for carousels
            Map<String, Object> carouselData = getCarouselData();
            debug.put("carousel_featured_size", ((List<?>)carouselData.get("featured")).size());
            debug.put("carousel_newReleases_size", ((List<?>)carouselData.get("newReleases")).size());
            debug.put("carousel_continueWatching_size", ((List<?>)carouselData.get("continueWatching")).size());
            debug.put("carousel_trending_size", ((List<?>)carouselData.get("trending")).size());
            debug.put("carousel_movies_size", ((List<?>)carouselData.get("movies")).size());
            debug.put("carousel_tvShows_size", ((List<?>)carouselData.get("tvShows")).size());
            
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

    @GET
    @Path("/optimized-carousels")
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    public String getOptimizedCarousels(
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("8") int limit,
            @jakarta.ws.rs.QueryParam("offset") @jakarta.ws.rs.DefaultValue("0") int offset) {
        
        try {
            // Get carousel data and apply limits
            Map<String, Object> carouselData = getCarouselData();
            List<VideoService.VideoDTO> featured = ((List<VideoService.VideoDTO>) carouselData.get("featured"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            List<VideoService.VideoDTO> newReleases = ((List<VideoService.VideoDTO>) carouselData.get("newReleases"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            List<VideoService.VideoDTO> continueWatching = ((List<VideoService.VideoDTO>) carouselData.get("continueWatching"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            List<VideoService.VideoDTO> trending = ((List<VideoService.VideoDTO>) carouselData.get("trending"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            List<VideoService.VideoDTO> movies = ((List<VideoService.VideoDTO>) carouselData.get("movies"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            List<VideoService.VideoDTO> tvShows = ((List<VideoService.VideoDTO>) carouselData.get("tvShows"))
                    .stream().skip(offset).limit(limit).collect(Collectors.toList());
            
            // Generate simple HTML carousels without template
            StringBuilder html = new StringBuilder();
            
            // Featured Carousel
            html.append(createSimpleCarouselHTML("Featured", featured, "pi pi-star", "#667eea", null, "featured-carousel"));
            
            // New Releases Carousel  
            html.append(createSimpleCarouselHTML("New Releases", newReleases, "pi pi-clock", "#ff4757", "NEW", "new-releases-carousel"));
            
            // Trending Carousel (only if not empty)
            if (!trending.isEmpty()) {
                html.append(createSimpleCarouselHTML("Trending Now", trending, "pi pi-fire", "#ffa502", "TRENDING", "trending-carousel"));
            }
            
            // Movies Carousel
            html.append(createSimpleCarouselHTML("Movies", movies, "pi pi-video", "#5f27cd", "MOVIES", "movies-carousel"));
            
            // TV Shows Carousel
            html.append(createSimpleCarouselHTML("TV Shows", tvShows, "pi pi-tv", "#00d2d3", "SERIES", "tv-shows-carousel"));
            
            return html.toString();
            
        } catch (Exception e) {
            LOG.error("Error getting optimized carousels", e);
            return "<div class='notification is-danger'>Failed to load carousels: " + e.getMessage() + "</div>";
        }
    }
    
    private String createCarouselHTML(String title, List<VideoService.VideoDTO> items, String iconClass, String iconColor, String badge) {
        try {
            String itemsJson = objectMapper.writeValueAsString(items);
            StringBuilder html = new StringBuilder();
            html.append("<div class=\"streaming-carousel-section\" ")
                  .append("x-data=\"initStreamingCarousel('").append(escapeJs(title)).append("', ")
                  .append(itemsJson).append(", '").append(iconClass).append("', 'color: ").append(iconColor).append("', '").append(badge != null ? badge : "").append("')\">");
            
            // Header
            html.append("<div class=\"carousel-header\">")
                  .append("<div class=\"carousel-title-section\">")
                  .append("<i class=\"").append(iconClass).append("\" style=\"color: ").append(iconColor).append("\"></i>")
                  .append("<h2 class=\"carousel-title\">").append(escapeHtml(title)).append("</h2>");
            if (badge != null) {
                html.append("<span class=\"carousel-badge\">").append(escapeHtml(badge)).append("</span>");
            }
            html.append("</div>")
                  .append("<div class=\"carousel-controls\">")
                  .append("<button class=\"carousel-nav-btn carousel-nav-left\" onclick=\"scrollCarousel(this, 'left')\"><i class=\"pi pi-chevron-left\"></i></button>")
                  .append("<button class=\"carousel-nav-btn carousel-nav-right\" onclick=\"scrollCarousel(this, 'right')\"><i class=\"pi pi-chevron-right\"></i></button>")
                  .append("</div>")
                  .append("</div>");
            
            // Carousel container
            html.append("<div class=\"carousel-container\">")
                  .append("<div class=\"streaming-carousel\">");
            
            for (VideoService.VideoDTO item : items) {
                html.append(createCardHTML(item));
            }
            
            html.append("</div></div></div>");
            return html.toString();
        } catch (Exception e) {
            LOG.error("Error creating carousel HTML for " + title, e);
            return "<div class=\"notification is-danger\">Error loading " + escapeHtml(title) + " carousel</div>";
        }
    }
    
    private String createCardHTML(VideoService.VideoDTO item) {
        try {
            String itemJson = objectMapper.writeValueAsString(item);
            String title = item.title() != null ? item.title() : 
                         item.seriesTitle() != null ? item.seriesTitle() : "Unknown Title";
            String thumbnail = item.thumbnailPath() != null ? item.thumbnailPath() : 
                          "https://picsum.photos/300/450?random=" + item.id();
            
            StringBuilder html = new StringBuilder();
            html.append("<div class='streaming-card' data-video-id='").append(item.id()).append("' ")
                  .append("onclick='handleCardClick(").append(itemJson).append(", event)' ")
                  .append("tabindex='0' role='button' aria-label='").append(title).append("'>");
            
            // Card image
            html.append("<div class='card-image-container'>")
                  .append("<img class='card-image' src='").append(thumbnail).append("' alt='").append(title).append("' loading='lazy'>")
                  .append("<div class='card-play-overlay'>")
                  .append("<button class='card-play-btn'><i class='pi pi-play'></i></button>")
                  .append("</div></div>");
            
            // Card content
            html.append("<div class='card-content'>")
                  .append("<div class='card-badges'>");
            if ("Movie".equals(item.type())) {
                html.append("<span class='card-badge movie-badge'>MOVIE</span>");
            } else if ("Episode".equals(item.type())) {
                html.append("<span class='card-badge episode-badge'>S").append(item.seasonNumber()).append("E").append(item.episodeNumber()).append("</span>");
            }
            html.append("</div>")
                  .append("<h3 class='card-title'>").append(title).append("</h3>")
                  .append("<p class='card-meta'>");
            if (item.releaseYear() != null && item.releaseYear() > 0) {
                html.append(item.releaseYear()).append(" • ");
            }
            if (item.durationSeconds() > 0) {
                html.append(formatDuration(item.durationSeconds())).append(" • ");
            }
            html.append("</p></div>");
            
            // Card actions
            html.append("<div class='card-actions'>")
                  .append("<button class='card-action-btn primary' onclick='window.selectItem(").append(itemJson).append(", \"play\")'>")
                  .append("<i class='pi pi-play'></i><span>Play</span></button>")
                  .append("<button class='card-action-btn secondary' onclick='window.selectItem(").append(itemJson).append(", \"details\")'>")
                  .append("<i class='pi pi-info-circle'></i><span>Info</span></button>")
                  .append("<button class='card-action-btn tertiary' onclick='window.addToWatchlist(").append(itemJson).append(")'>")
                  .append("<i class='pi pi-plus'></i></button>")
                  .append("</div></div>");
            
            return html.toString();
        } catch (Exception e) {
            return "<div class='card-error'>Error rendering card</div>";
        }
    }

    @GET
    @Path("/search-suggest")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSearchSuggestions(
            @jakarta.ws.rs.QueryParam("q") String query,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("10") int limit) {
        
        try {
            if (query == null || query.isBlank() || query.length() < 2) {
                return Response.ok(ApiResponse.success(Collections.emptyList())).build();
            }
            
            // Get search results
            List<VideoService.VideoDTO> allVideos = videoService.findAll();
            List<VideoService.VideoDTO> suggestions = allVideos.stream()
                    .filter(video -> {
                        String title = video.title() != null ? video.title().toLowerCase() : "";
                        String seriesTitle = video.seriesTitle() != null ? video.seriesTitle().toLowerCase() : "";
                        String queryLower = query.toLowerCase();
                        
                        return title.contains(queryLower) || 
                               seriesTitle.contains(queryLower) ||
                               title.startsWith(queryLower) ||
                               seriesTitle.startsWith(queryLower);
                    })
                    .limit(limit)
                    .collect(Collectors.toList());
            
            // Enrich suggestions with thumbnail URLs
            List<Map<String, Object>> enrichedSuggestions = suggestions.stream()
                    .map(video -> {
                        Map<String, Object> enriched = new HashMap<>();
                        enriched.put("id", video.id());
                        enriched.put("title", video.title());
                        enriched.put("seriesTitle", video.seriesTitle());
                        enriched.put("type", video.type());
                        enriched.put("durationSeconds", video.durationSeconds());
                        enriched.put("seasonNumber", video.seasonNumber());
                        enriched.put("episodeNumber", video.episodeNumber());
                        enriched.put("episodeTitle", video.episodeTitle());
                        enriched.put("releaseYear", video.releaseYear());
                        // Description field not available in VideoDTO
                        
                        // Add thumbnail URL
                        String thumbnailUrl = "/api/video/thumbnail/" + video.id();
                        enriched.put("thumbnail", thumbnailUrl);
                        enriched.put("thumbnailPath", video.thumbnailPath() != null && !video.thumbnailPath().isBlank() ? video.thumbnailPath() : thumbnailUrl);
                        
                        // Add display helpers
                        enriched.put("displayTitle", video.title() != null ? video.title() : 
                                         video.seriesTitle() != null ? video.seriesTitle() : "Unknown Title");
                        
                        List<String> metaParts = new ArrayList<>();
                        if (video.type() != null) metaParts.add(video.type());
                        if (video.releaseYear() != null && video.releaseYear() > 0) 
                            metaParts.add(video.releaseYear().toString());
                        if (video.durationSeconds() > 0) 
                            metaParts.add(formatDuration(video.durationSeconds()));
                        if ("Episode".equals(video.type()) && video.seasonNumber() != null && video.episodeNumber() != null)
                            metaParts.add("S" + video.seasonNumber() + "E" + video.episodeNumber());
                        
                        enriched.put("displayMeta", String.join(" • ", metaParts));
                        
                        return enriched;
                    })
                    .collect(Collectors.toList());
            
            return Response.ok(ApiResponse.success(enrichedSuggestions))
                    .header("Cache-Control", "public, max-age=60") // Cache for 1 minute
                    .build();
            
        } catch (Exception e) {
            LOG.error("Error getting search suggestions for query: " + query, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get suggestions"))
                    .build();
        }
    }

    /**
     * Get carousel data using simplified approach
     */
    private Map<String, Object> getCarouselData() {
        Map<String, Object> data = new HashMap<>();

        try {
            // Get all videos first - this is our reliable base
            List<VideoService.VideoDTO> allVideos = videoService.findAll();
            System.out.println("DEBUG: Total videos found: " + allVideos.size());
            
            if (allVideos.isEmpty()) {
                System.out.println("DEBUG: No videos found in library");
                // No videos at all
                data.put("featured", Collections.emptyList());
                data.put("newReleases", Collections.emptyList());
                data.put("continueWatching", Collections.emptyList());
                data.put("trending", Collections.emptyList());
                data.put("movies", Collections.emptyList());
                data.put("tvShows", Collections.emptyList());
                return data;
            }

            // Try to get some videos using simple methods
            List<VideoService.VideoDTO> featured = new ArrayList<>();
            List<VideoService.VideoDTO> newReleases = new ArrayList<>();
            List<VideoService.VideoDTO> trending = new ArrayList<>();
            List<VideoService.VideoDTO> movies = new ArrayList<>();
            List<VideoService.VideoDTO> tvShows = new ArrayList<>();

            // Try paginated movies first (simplest method)
            try {
                VideoService.PaginatedVideos moviesPaginated = videoService.findPaginatedByMediaType("Movie", 1, 20);
                movies = moviesPaginated.videos();
                System.out.println("DEBUG: Movies from paginated query: " + movies.size());
                data.put("movies", movies);
            } catch (Exception e) {
                System.err.println("Error getting movies: " + e.getMessage());
                data.put("movies", Collections.emptyList());
            }

            // Use first few videos for other carousels
            try {
                featured = allVideos.stream().limit(Math.min(5, allVideos.size())).collect(Collectors.toList());
                System.out.println("DEBUG: Featured videos: " + featured.size());
                data.put("featured", featured);
            } catch (Exception e) {
                System.err.println("Error setting featured: " + e.getMessage());
                data.put("featured", Collections.emptyList());
            }

            try {
                newReleases = allVideos.stream().skip(Math.min(5, allVideos.size())).limit(Math.min(8, allVideos.size() - 5)).collect(Collectors.toList());
                System.out.println("DEBUG: New releases: " + newReleases.size());
                data.put("newReleases", newReleases);
            } catch (Exception e) {
                System.err.println("Error setting new releases: " + e.getMessage());
                data.put("newReleases", Collections.emptyList());
            }

            try {
                trending = allVideos.stream().skip(Math.min(13, allVideos.size())).limit(Math.min(8, allVideos.size() - 13)).collect(Collectors.toList());
                System.out.println("DEBUG: Trending videos: " + trending.size());
                data.put("trending", trending);
            } catch (Exception e) {
                System.err.println("Error setting trending: " + e.getMessage());
                data.put("trending", Collections.emptyList());
            }

            // TV Shows - episodes only
            try {
                tvShows = allVideos.stream()
                        .filter(v -> "Episode".equals(v.type()))
                        .limit(Math.min(8, allVideos.size()))
                        .collect(Collectors.toList());
                System.out.println("DEBUG: TV Shows (episodes): " + tvShows.size());
                data.put("tvShows", tvShows);
            } catch (Exception e) {
                System.err.println("Error setting tv shows: " + e.getMessage());
                data.put("tvShows", Collections.emptyList());
            }

            // Continue watching - empty for now
            data.put("continueWatching", Collections.emptyList());

        } catch (Exception e) {
            System.err.println("Critical error in getCarouselData: " + e.getMessage());
            // Provide empty lists to avoid template errors
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
