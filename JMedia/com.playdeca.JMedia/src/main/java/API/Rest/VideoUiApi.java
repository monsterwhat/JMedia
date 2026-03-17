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
            List<Models.Video> allVideos = Models.Video.list("isActive", true);
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
            
            return renderedHero;
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
            System.out.println("DEBUG: Total videos found: " + Models.Video.count("isActive", true));
            System.out.println("DEBUG: Movies: " + ((List<?>)carouselData.get("movies")).size());
            System.out.println("DEBUG: New releases: " + ((List<?>)carouselData.get("newReleases")).size());
            System.out.println("DEBUG: Trending videos: " + ((List<?>)carouselData.get("trending")).size());
            System.out.println("DEBUG: TV Shows: " + ((List<?>)carouselData.get("tvShows")).size());

            StringBuilder html = new StringBuilder("<div class='carousels-container' style='padding: 2rem 0;'>");
            
            html.append(createSimpleCarouselHTML("New Releases", (List<Models.Video>) carouselData.get("newReleases"), "pi pi-clock", "#48c774", "NEW", "new-releases-carousel"));
            
            List<Models.Video> trending = (List<Models.Video>) carouselData.get("trending");
            if (!trending.isEmpty()) {
                html.append(createSimpleCarouselHTML("Trending Now", trending, "pi pi-fire", "#ffa502", "TRENDING", "trending-carousel"));
            }
            
            html.append(createSimpleCarouselHTML("Movies", (List<Models.Video>) carouselData.get("movies"), "pi pi-video", "#5f27cd", "MOVIES", "movies-carousel"));
            html.append(createSimpleCarouselHTML("TV Shows", (List<Models.Video>) carouselData.get("tvShows"), "pi pi-desktop", "#00d2d3", "SERIES", "tv-shows-carousel"));
            
            html.append("</div>");
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
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("sortBy") @DefaultValue("dateAdded") String sortBy,
            @QueryParam("sortDirection") @DefaultValue("desc") String sortDirection) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("movie", page, limit, sortBy, sortDirection);
        return movieListContent
                .data("movies", paginatedVideos.videos)
                .data("currentPage", page)
                .data("limit", limit)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("totalItems", paginatedVideos.totalCount)
                .data("totalPages", (int) Math.ceil((double) paginatedVideos.totalCount / limit))
                .data("pageNumbers", getPaginationNumbers(page, (int) Math.ceil((double) paginatedVideos.totalCount / limit)))
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/shows-fragment")
    @Blocking
    public String getSeriesFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("sortBy") @DefaultValue("seriesTitle") String sortBy,
            @QueryParam("sortDirection") @DefaultValue("asc") String sortDirection) {
        
        VideoService.PaginatedSeries paginatedSeries = videoService.findPaginatedSeriesTitles(page, limit, sortBy, sortDirection);
        
        if (paginatedSeries.titles.isEmpty()) {
            return "<div class='library-header'><h1 class='library-title'>TV Shows</h1></div>" +
                   "<div class='carousel-empty-state'><i class='pi pi-desktop'></i><h3>No shows found</h3><p>Try scanning your library or check if your episodes have series titles.</p></div>";
        }

        int totalItems = (int) paginatedSeries.totalCount;
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        
        List<Models.Video> allEpisodes = videoService.findEpisodes();
        if (allEpisodes.isEmpty()) {
            allEpisodes = Models.Video.<Models.Video>listAll().stream()
                    .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode"))
                    .collect(Collectors.toList());
        }

        List<SeriesTitleEntry> entries = new ArrayList<>();
        for (String title : paginatedSeries.titles) {
            final String currentTitle = title;
            Models.Video sample = allEpisodes.stream()
                    .filter(v -> currentTitle.equalsIgnoreCase(v.seriesTitle))
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
                .data("currentPage", page)
                .data("limit", limit)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("totalItems", totalItems)
                .data("totalPages", totalPages)
                .render();
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons-fragment")
    @Blocking
    public String getSeasonsFragment(@PathParam("seriesTitle") String seriesTitle) {
        try {
            // Path parameters are often not decoded automatically in all JAX-RS configurations
            String decodedTitle = java.net.URLDecoder.decode(seriesTitle, StandardCharsets.UTF_8);
            List<Models.Video> seriesEpisodes = videoService.findEpisodesForSeries(decodedTitle);
            
            // Case-insensitive fallback
            if (seriesEpisodes.isEmpty()) {
                seriesEpisodes = Models.Video.<Models.Video>listAll().stream()
                    .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode") && 
                            decodedTitle.equalsIgnoreCase(v.seriesTitle))
                    .collect(Collectors.toList());
            }

            final List<Models.Video> finalEpisodes = seriesEpisodes;
            List<Integer> seasonNumbers = finalEpisodes.stream()
                    .map(v -> v.seasonNumber != null ? v.seasonNumber : 1) // Treat null as Season 1
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            // If it's still empty but we have episodes, ensure we have at least season 1
            if (seasonNumbers.isEmpty() && !finalEpisodes.isEmpty()) {
                seasonNumbers = Collections.singletonList(1);
            }

            List<SeasonEntry> seasons = new ArrayList<>();
            for (Integer sn : seasonNumbers) {
                Models.Video sample = finalEpisodes.stream()
                        .filter(v -> (v.seasonNumber != null ? v.seasonNumber : 1) == sn)
                        .findFirst()
                        .orElse(null);
                seasons.add(new SeasonEntry(sn, sample != null ? sample.id : null));
            }

            Models.Video sampleVideo = finalEpisodes.isEmpty() ? null : finalEpisodes.get(0);
            
            // Find the last played video (or first one)
            Models.Video lastPlayedVideo = finalEpisodes.stream()
                    .filter(v -> v.lastWatched != null)
                    .sorted(Comparator.comparing(v -> ((Models.Video)v).lastWatched).reversed())
                    .findFirst()
                    .orElse(sampleVideo);

            return seasonListContent
                    .data("seriesTitle", decodedTitle)
                    .data("encodedSeriesTitle", seriesTitle) // Keep original encoded for HTMX sub-requests
                    .data("seasons", seasons)
                    .data("sampleVideo", sampleVideo)
                    .data("lastPlayedVideo", lastPlayedVideo)
                    .render();
        } catch (Exception e) {
            LOG.error("Error rendering seasons fragment for show {}: {}", seriesTitle, e.getMessage(), e);
            return "<div class='carousel-empty-state'><i class='pi pi-exclamation-circle'></i><h3>Error loading seasons</h3><p>" + e.getMessage() + "</p></div>";
        }
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/episodes-fragment")
    @Blocking
    public String getEpisodesFragment(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber) {
        try {
            String decodedTitle = java.net.URLDecoder.decode(seriesTitle, StandardCharsets.UTF_8);
            LOG.info("Loading episodes for series: {}, season: {}", decodedTitle, seasonNumber);
            
            List<Models.Video> episodes = videoService.findEpisodesForSeason(decodedTitle, seasonNumber);
            
            // Fallback for case-insensitivity or null season numbers (mapped to season 1)
            if (episodes.isEmpty()) {
                episodes = Models.Video.<Models.Video>listAll().stream()
                    .filter(v -> v.type != null && v.type.equalsIgnoreCase("episode") && 
                            decodedTitle.equalsIgnoreCase(v.seriesTitle) && 
                            (seasonNumber.equals(v.seasonNumber) || (seasonNumber == 1 && v.seasonNumber == null)))
                    .sorted(Comparator.comparingInt(v -> v.episodeNumber != null ? v.episodeNumber : 0))
                    .collect(Collectors.toList());
            }

            return episodeListContent
                    .data("seriesTitle", decodedTitle)
                    .data("seasonNumber", seasonNumber)
                    .data("episodes", episodes)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("encodedSeriesTitle", seriesTitle) // Use original encoded for sub-requests
                    .render();
        } catch (Exception e) {
            LOG.error("Error rendering episodes fragment for show {} season {}: {}", seriesTitle, seasonNumber, e.getMessage(), e);
            return "<div class='carousel-empty-state'><i class='pi pi-exclamation-circle'></i><h3>Error loading episodes</h3><p>" + e.getMessage() + "</p></div>";
        }
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
                .data("threshold", 0.95)
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
        
        if (item.resumeTime != null && item.resumeTime > 0) {
            resumeTime = item.resumeTime / 1000.0;
        } else if (state != null && videoId.equals(state.getCurrentVideoId())) {
            resumeTime = state.getCurrentTime();
        } else if (item.watchProgress != null && item.watchProgress > 0 && item.watchProgress < 0.98) {
            resumeTime = item.watchProgress * (item.getDurationSeconds());
        }

        Models.Video nextEpisode = videoService.findNextEpisode(item);
        Models.Video prevEpisode = videoService.findPreviousEpisode(item);

        return playbackFragment
                .data("item", item)
                .data("resumeTime", resumeTime)
                .data("nextEpisodeId", nextEpisode != null ? nextEpisode.id : null)
                .data("prevEpisodeId", prevEpisode != null ? prevEpisode.id : null)
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
        StringBuilder html = new StringBuilder("<div class='streaming-carousel-section'>");
        
        // Header with title and controls
        html.append("<div class='carousel-header'>");
        html.append("<div class='carousel-title-section'>");
        html.append("<i class='").append(iconClass).append("' style='color: ").append(iconColor).append("'></i>");
        html.append("<h2 class='carousel-title'>").append(escapeHtml(title)).append("</h2>");
        if (badge != null && !badge.isEmpty()) {
            html.append("<span class='carousel-badge'>").append(badge).append("</span>");
        }
        html.append("</div>");
        
        // Carousel controls moved to header
        html.append("<div class='carousel-controls'>");
        html.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('").append(carouselId).append("', 'left')\"><i class='pi pi-chevron-left'></i></button>");
        html.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('").append(carouselId).append("', 'right')\"><i class='pi pi-chevron-right'></i></button>");
        html.append("</div>");
        html.append("</div>"); // End carousel-header

        // Container for items
        html.append("<div class='carousel-container'>");
        html.append("<div class='streaming-carousel' id='").append(carouselId).append("'>");
        for (Models.Video item : items) html.append(createSimpleCardHTML(item));
        html.append("</div></div></div>");
        return html.toString();
    }

    private Map<String, Object> getCarouselData() {
        List<Models.Video> all = Models.Video.list("isActive", true);
        Map<String, Object> data = new HashMap<>();
        data.put("newReleases", all.stream().sorted((v1, v2) -> (v2.dateAdded != null ? v2.dateAdded : java.time.LocalDateTime.MIN).compareTo(v1.dateAdded != null ? v1.dateAdded : java.time.LocalDateTime.MIN)).limit(20).collect(Collectors.toList()));
        data.put("movies", all.stream().filter(v -> v.type != null && "movie".equalsIgnoreCase(v.type)).limit(20).collect(Collectors.toList()));
        java.util.Set<String> seenShows = new java.util.HashSet<>();
        data.put("tvShows", all.stream()
            .filter(v -> v.type != null && "episode".equalsIgnoreCase(v.type) && v.seriesTitle != null)
            .filter(v -> {
                String normalized = v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                return seenShows.add(normalized);
            })
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

    // Helper records for passing series and season info to templates
    public record SeriesTitleEntry(String rawTitle, String encodedTitle, String cssId, Long sampleVideoId) {}
    public record SeasonEntry(Integer seasonNumber, Long sampleVideoId) {}
}
