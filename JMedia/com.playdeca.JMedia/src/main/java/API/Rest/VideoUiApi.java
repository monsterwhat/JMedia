package API.Rest;

import API.ApiResponse;
import Controllers.VideoController;
import Services.VideoService;
import Services.VideoHistoryService;
import Services.VideoStateService;
import Models.Video;
import Models.VideoState;
import io.quarkus.qute.Template;
import io.quarkus.qute.ValueResolver;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoStateService videoStateService;

    // Qute Templates
    @Inject @io.quarkus.qute.Location("movieListContent.html")
    Template movieListContent;
    @Inject @io.quarkus.qute.Location("seriesListContent.html")
    Template seriesListContent;
    @Inject @io.quarkus.qute.Location("seasonListContent.html")
    Template seasonListContent;
    @Inject @io.quarkus.qute.Location("episodeListContent.html")
    Template episodeListContent;
    @Inject @io.quarkus.qute.Location("optimizedHeroFragment.html")
    Template optimizedHeroFragment;
    @Inject @io.quarkus.qute.Location("detailsFragment.html")
    Template detailsFragment;
    @Inject @io.quarkus.qute.Location("playbackFragment.html")
    Template playbackFragment;
    @Inject @io.quarkus.qute.Location("videoHistoryFragment.html")
    Template videoHistoryFragment;
    @Inject @io.quarkus.qute.Location("videoWatchlistFragment.html")
    Template videoWatchlistFragment;
    @Inject @io.quarkus.qute.Location("subtitleTrackSelector.html")
    Template subtitleTrackSelector;
    @Inject @io.quarkus.qute.Location("subtitleSettingsComponent.html")
    Template subtitleSettingsComponent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== FRAGMENT ENDPOINTS ====================

    @GET
    @Path("/hero-fragment")
    @Blocking
    public String getHeroFragment() {
        try {
            List<Models.Video> allVideos = videoService.findAll();
            LOG.info("Hero fragment: Total videos found: " + allVideos.size());
            
            List<Models.Video> featured = allVideos.stream()
                    .filter(v -> "movie".equalsIgnoreCase(v.type))
                    .sorted((v1, v2) -> (v1.description != null ? 0 : 1) - (v2.description != null ? 0 : 1))
                    .limit(5)
                    .collect(Collectors.toList());
            
            LOG.info("Hero fragment: Using " + featured.size() + " featured videos");
            
            String renderedHero = optimizedHeroFragment
                    .data("featured", featured)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("json", (Function<Object, String>) this::toJson)
                    .render();
            
            return "<div class='streaming-carousel-section'>" +
                   "<div class='carousel-header'><div class='carousel-title-section'>" +
                   "<i class='pi pi-star' style='color: #667eea'></i>" +
                   "<h2 class='carousel-title'>Featured</h2>" +
                   "</div></div>" + renderedHero + "</div>";
        } catch (Exception e) {
            LOG.error("Error generating hero fragment", e);
            return "";
        }
    }

    @GET
    @Path("/optimized-carousels")
    @Blocking
    public String getOptimizedCarousels() {
        try {
            Map<String, Object> carouselData = getCarouselData();
            
            // Print debug info like the original class
            System.out.println("DEBUG: Total videos found: " + videoService.findAll().size());
            System.out.println("DEBUG: Movies: " + ((List<?>)carouselData.get("movies")).size());
            System.out.println("DEBUG: New releases: " + ((List<?>)carouselData.get("newReleases")).size());
            System.out.println("DEBUG: Trending videos: " + ((List<?>)carouselData.get("trending")).size());
            System.out.println("DEBUG: TV Shows: " + ((List<?>)carouselData.get("tvShows")).size());

            StringBuilder html = new StringBuilder();
            
            html.append(createSimpleCarouselHTML("New Releases", (List<Models.Video>) carouselData.get("newReleases"), "pi pi-clock", "#48c774", "NEW", "new-releases-carousel"));
            
            List<Models.Video> trending = (List<Models.Video>) carouselData.get("trending");
            if (!trending.isEmpty()) {
                html.append(createSimpleCarouselHTML("Trending Now", trending, "pi pi-fire", "#ffa502", "TRENDING", "trending-carousel"));
            }
            
            html.append(createSimpleCarouselHTML("Movies", (List<Models.Video>) carouselData.get("movies"), "pi pi-video", "#5f27cd", "MOVIES", "movies-carousel"));
            html.append(createSimpleCarouselHTML("TV Shows", (List<Models.Video>) carouselData.get("tvShows"), "pi pi-desktop", "#00d2d3", "SERIES", "tv-shows-carousel"));
            
            return html.toString();
        } catch (Exception e) {
            LOG.error("Error getting optimized carousels", e);
            return "<div class='notification is-danger'>Failed to load carousels</div>";
        }
    }

    @GET
    @Path("/movies-fragment")
    @Blocking
    public String getMoviesFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("movie", page, limit);
        return movieListContent
                .data("movies", paginatedVideos.videos)
                .data("currentPage", page)
                .data("limit", limit)
                .data("totalItems", paginatedVideos.totalCount)
                .data("totalPages", (int) Math.ceil((double) paginatedVideos.totalCount / limit))
                .data("pageNumbers", getPaginationNumbers(page, (int) Math.ceil((double) paginatedVideos.totalCount / limit)))
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/shows-fragment")
    @Blocking
    public String getSeriesFragment() {
        List<String> seriesTitles = videoService.findAllSeriesTitles();
        
        // If empty, try finding with case-insensitive check if some are stored as 'Episode' or 'EPISODE'
        if (seriesTitles.isEmpty()) {
            seriesTitles = Models.Video.<Models.Video>listAll().stream()
                    .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode"))
                    .map(v -> v.seriesTitle)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (seriesTitles.isEmpty()) {
            return "<div class='library-header'><h1 class='library-title'>TV Shows</h1></div>" +
                   "<div class='carousel-empty-state'><i class='pi pi-desktop'></i><h3>No shows found</h3><p>Try scanning your library or check if your episodes have series titles.</p></div>";
        }

        List<Models.Video> allEpisodes = videoService.findEpisodes();
        // If findEpisodes returned nothing, try the case-insensitive version
        if (allEpisodes.isEmpty()) {
            allEpisodes = Models.Video.<Models.Video>listAll().stream()
                    .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode"))
                    .collect(Collectors.toList());
        }

        List<SeriesTitleEntry> entries = new ArrayList<>();
        for (String title : seriesTitles) {
            final String currentTitle = title;
            Models.Video sample = allEpisodes.stream()
                    .filter(v -> currentTitle.equals(v.seriesTitle))
                    .findFirst().orElse(null);
            
            if (sample != null) {
                entries.add(new SeriesTitleEntry(
                    title, 
                    URLEncoder.encode(title, StandardCharsets.UTF_8),
                    "series-" + Math.abs(title.hashCode()),
                    sample.id
                ));
            }
        }

        return seriesListContent
                .data("series", entries)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons-fragment")
    @Blocking
    public String getSeasonsFragment(@PathParam("seriesTitle") String seriesTitle) {
        List<Integer> seasonNumbers = videoService.findSeasonNumbersForSeries(seriesTitle);
        
        // Case-insensitive fallback for season numbers
        if (seasonNumbers.isEmpty()) {
             seasonNumbers = Models.Video.<Models.Video>listAll().stream()
                .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode") && seriesTitle.equalsIgnoreCase(v.seriesTitle))
                .map(v -> v.seasonNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        }

        return seasonListContent
                .data("seriesTitle", seriesTitle)
                .data("encodedSeriesTitle", URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8))
                .data("seasonNumbers", seasonNumbers)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment")
    @Blocking
    public String getEpisodesFragment(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        List<Models.Video> episodes = videoService.findEpisodesForSeason(seriesTitle, seasonNumber);
        
        // Case-insensitive fallback
        if (episodes.isEmpty()) {
            episodes = Models.Video.<Models.Video>listAll().stream()
                .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode") && 
                        seriesTitle.equalsIgnoreCase(v.seriesTitle) && 
                        seasonNumber.equals(v.seasonNumber))
                .sorted(Comparator.comparingInt(v -> v.episodeNumber != null ? v.episodeNumber : 0))
                .collect(Collectors.toList());
        }

        return episodeListContent
                .data("episodes", episodes)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("encodedSeriesTitle", URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8))
                .render();
    }

    @GET
    @Path("/history-fragment")
    @Blocking
    public String getHistoryFragment() {
        List<Models.VideoHistory> history = Models.VideoHistory.list("order by playedAt desc");
        
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        List<Models.Video> videos = new ArrayList<>();
        
        for (Models.VideoHistory h : history) {
            if (h.mediaFile != null && seenPaths.add(h.mediaFile.path)) {
                Models.Video v = Models.Video.find("path", h.mediaFile.path).firstResult();
                if (v != null) videos.add(v);
            }
            if (videos.size() >= 50) break;
        }
        
        return videoHistoryFragment
                .data("videos", videos)
                .render();
    }

    @GET
    @Path("/watchlist-fragment")
    @Blocking
    public String getWatchlistFragment() {
        List<Models.Video> watchlist = Models.Video.list("favorite", true);
        return videoWatchlistFragment
                .data("videos", watchlist)
                .render();
    }

    @GET
    @Path("/details-fragment/{videoId}")
    @Blocking
    public String getDetailsFragment(@PathParam("videoId") Long videoId) {
        Models.Video item = videoService.find(videoId);
        if (item == null) return "<div class='notification is-danger'>Video not found</div>";
        
        return detailsFragment
                .data("item", item)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("json", (ValueResolver) (ctx) -> {
                    try { return java.util.concurrent.CompletableFuture.completedFuture(objectMapper.writeValueAsString(ctx.getBase())); }
                    catch (Exception e) { return java.util.concurrent.CompletableFuture.completedFuture("{}"); }
                }).render();
    }

    @GET
    @Path("/playback-fragment")
    @Blocking
    public String getPlaybackFragment(@QueryParam("videoId") Long videoId) {
        Models.Video item = videoService.find(videoId);
        if (item == null) return "<div class='notification is-warning'>No video available for playback</div>";
        
        VideoState state = videoStateService.getOrCreateState();
        double resumeTime = 0;
        
        if (state != null && videoId.equals(state.getCurrentVideoId())) {
            resumeTime = state.getCurrentTime();
        } else if (item.watchProgress != null && item.watchProgress > 0 && item.watchProgress < 0.98) {
            resumeTime = item.watchProgress * (item.getDurationSeconds());
        }

        return playbackFragment
                .data("item", item)
                .data("resumeTime", resumeTime)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("json", (ValueResolver) (ctx) -> {
                    try { return java.util.concurrent.CompletableFuture.completedFuture(objectMapper.writeValueAsString(ctx.getBase())); }
                    catch (Exception e) { return java.util.concurrent.CompletableFuture.completedFuture("{}"); }
                }).render();
    }

    @GET
    @Path("/subtitle-selector-fragment")
    @Blocking
    public String getSubtitleSelectorFragment() { return subtitleTrackSelector.render(); }

    @GET
    @Path("/subtitle-settings-fragment")
    @Blocking
    public String getSubtitleSettingsFragment() { return subtitleSettingsComponent.render(); }

    // ==================== HELPERS ====================

    private String createSimpleCardHTML(Models.Video item) {
        String title = item.title != null ? item.title : (item.seriesTitle != null ? item.seriesTitle : "Unknown");
        return String.format(
            "<div class='streaming-card' onclick=\"window.selectItem(%d, 'details')\">" +
            "<div class='card-image-container'><img class='card-image' src='/api/video/thumbnail/%d' loading='lazy'>" +
            "<div class='card-play-overlay'><div class='card-play-btn' onclick=\"event.stopPropagation(); window.selectItem(%d, 'play')\"><i class='pi pi-play'></i></div></div>" +
            "</div><div class='card-content'><div class='card-title'>%s</div><div class='card-meta'>%s</div></div></div>",
            item.id, item.id, item.id, escapeHtml(title), item.releaseYear != null ? item.releaseYear : ""
        );
    }

    private String createSimpleCarouselHTML(String title, List<Models.Video> items, String iconClass, String iconColor, String badge, String carouselId) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<div class='streaming-carousel-section'><div class='carousel-header'><div class='carousel-title-section'>");
        html.append("<i class='").append(iconClass).append("' style='color: ").append(iconColor).append("'></i>");
        html.append("<h2 class='carousel-title'>").append(escapeHtml(title)).append("</h2>");
        html.append("</div></div><div class='carousel-container'>");
        html.append("<button class='carousel-nav-btn carousel-nav-left' onclick=\"window.scrollCarousel('").append(carouselId).append("', 'left')\"><i class='pi pi-chevron-left'></i></button>");
        html.append("<button class='carousel-nav-btn carousel-nav-right' onclick=\"window.scrollCarousel('").append(carouselId).append("', 'right')\"><i class='pi pi-chevron-right'></i></button>");
        html.append("<div class='streaming-carousel' id='").append(carouselId).append("'>");
        for (Models.Video item : items) html.append(createSimpleCardHTML(item));
        html.append("</div></div></div>");
        return html.toString();
    }

    private Map<String, Object> getCarouselData() {
        List<Models.Video> all = videoService.findAll();
        Map<String, Object> data = new HashMap<>();
        data.put("newReleases", all.stream().sorted((v1, v2) -> (v2.dateAdded != null ? v2.dateAdded : java.time.LocalDateTime.MIN).compareTo(v1.dateAdded != null ? v1.dateAdded : java.time.LocalDateTime.MIN)).limit(20).collect(Collectors.toList()));
        data.put("movies", all.stream().filter(v -> v.type != null && "movie".equalsIgnoreCase(v.type)).limit(20).collect(Collectors.toList()));
        java.util.Set<String> seenShows = new java.util.HashSet<>();
        data.put("tvShows", all.stream()
            .filter(v -> v.type != null && "episode".equalsIgnoreCase(v.type) && v.seriesTitle != null)
            .filter(v -> seenShows.add(v.seriesTitle))
            .limit(20).collect(Collectors.toList()));
        data.put("trending", all.stream().skip(Math.min(10, all.size())).limit(15).collect(Collectors.toList()));
        return data;
    }

    private String formatDuration(Integer s) { return s == null ? "0:00" : String.format("%d:%02d", s / 60, s % 60); }
    private String toJson(Object o) { try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return "{}"; } }
    private String escapeHtml(String t) { return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    
    private List<Integer> getPaginationNumbers(int c, int t) {
        List<Integer> res = new ArrayList<>();
        if (t <= 0) return res;
        for (int i = 1; i <= t; i++) if (i == 1 || i == t || Math.abs(i - c) <= 2) res.add(i);
        return res;
    }

    // Helper record for passing series title (raw and encoded) to templates
    public record SeriesTitleEntry(String rawTitle, String encodedTitle, String cssId, Long sampleVideoId) {}
}
