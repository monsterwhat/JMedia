package API.Rest;

import API.ApiResponse;
import Controllers.VideoController;
import Services.VideoService;
import Services.VideoHistoryService;
import Services.VideoStateService;
import Services.CollectionService;
import Services.CollectionWatchProgressService;
import Services.GenreService;
import Models.Video;
import Models.VideoHistory;
import Models.Profile;
import Models.VideoState;
import Models.CollectionWatchProgress;
import Services.VideoSuggestionService;
import Services.ExternalVideoService;
import io.quarkus.qute.Template;
import io.quarkus.qute.ValueResolver;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    GenreService genreService;

    @Inject
    Services.TranscodingService transcodingService;

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoStateService videoStateService;

    @Inject
    CollectionWatchProgressService collectionWatchProgressService;

    @Inject
    Services.SettingsService settingsService;

    @Inject
    VideoSuggestionService videoSuggestionService;

    @Inject
    ExternalVideoService externalVideoService;

    @Inject
    CollectionService collectionService;

    @Inject @io.quarkus.qute.Location("suggestionFragment.html")
    Template suggestionFragment;

    @Inject @io.quarkus.qute.Location("adminSuggestionsFragment.html")
    Template adminSuggestionsFragment;

    // Qute Templates
    @Inject @io.quarkus.qute.Location("movieListContent.html")
    Template movieListContent;
    @Inject @io.quarkus.qute.Location("seriesListContent.html")
    Template seriesListContent;
    @Inject @io.quarkus.qute.Location("seasonListContent.html")
    Template seasonListContent;
    @Inject @io.quarkus.qute.Location("episodeListContent.html")
    Template episodeListContent;
    @Inject @io.quarkus.qute.Location("folderEpisodesContent.html")
    Template folderEpisodesContent;
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
    @Inject @io.quarkus.qute.Location("adminVideoHistoryFragment.html")
    Template adminVideoHistoryFragment;
    @Inject @io.quarkus.qute.Location("movieItemsFragment.html")
    Template movieItemsFragment;
    @Inject @io.quarkus.qute.Location("seriesItemsFragment.html")
    Template seriesItemsFragment;
    @Inject @io.quarkus.qute.Location("historyItemsFragment.html")
    Template historyItemsFragment;
    @Inject @io.quarkus.qute.Location("adminHistoryItemsFragment.html")
    Template adminHistoryItemsFragment;
    @Inject @io.quarkus.qute.Location("watchlistItemsFragment.html")
    Template watchlistItemsFragment;
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
            
            List<Models.Video> continueWatching = (List<Models.Video>) carouselData.get("continueWatching");
            if (!continueWatching.isEmpty()) {
                html.append(createSimpleCarouselHTML("Continue Watching", continueWatching, "pi pi-replay", "#fdcb6e", "RESUME", "continue-watching-carousel"));
            }

            // Collection progress carousel
            {
                List<CollectionWatchProgress> collectionProgress = collectionWatchProgressService.getInProgress();
                if (!collectionProgress.isEmpty()) {
                    html.append(createCollectionCarouselHTML(collectionProgress));
                }
            }
            
            // Build Recently Updated carousel — merge regular and external entries sorted by date
            {
                List<Models.Video> newReleases = (List<Models.Video>) carouselData.get("newReleases");
                List<Models.ExternalVideo> externalVideos = Models.ExternalVideo.list("order by lastUpdated desc");
                // Build list of (html, timestamp) pairs
                List<Object[]> cardEntries = new ArrayList<>();
                for (Models.Video v : newReleases) {
                    java.time.LocalDateTime ts = v.dateAdded != null ? v.dateAdded : java.time.LocalDateTime.MIN;
                    cardEntries.add(new Object[]{createSimpleCardHTML(v), ts});
                }
                for (Models.ExternalVideo ev : externalVideos) {
                    java.time.LocalDateTime ts = ev.lastUpdated != null ? ev.lastUpdated : java.time.LocalDateTime.MIN;
                    cardEntries.add(new Object[]{createExternalCardHTML(ev), ts});
                }
                cardEntries.sort((a, b) -> ((java.time.LocalDateTime) b[1]).compareTo((java.time.LocalDateTime) a[1]));
                // Limit to 40 items
                if (cardEntries.size() > 40) cardEntries = cardEntries.subList(0, 40);

                StringBuilder carouselHtml = new StringBuilder();
                carouselHtml.append("<div class='streaming-carousel-section'>");
                carouselHtml.append("<div class='carousel-header'>");
                carouselHtml.append("<div class='carousel-title-section'>");
                carouselHtml.append("<i class='pi pi-clock' style='color: #48c774'></i>");
                carouselHtml.append("<h2 class='carousel-title'>Recently Updated</h2>");
                carouselHtml.append("<span class='carousel-badge'>UPDATED</span>");
                carouselHtml.append("</div>");
                carouselHtml.append("<div class='carousel-controls'>");
                carouselHtml.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('new-releases-carousel', 'left')\"><i class='pi pi-chevron-left'></i></button>");
                carouselHtml.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('new-releases-carousel', 'right')\"><i class='pi pi-chevron-right'></i></button>");
                carouselHtml.append("</div>");
                carouselHtml.append("</div>");
                carouselHtml.append("<div class='carousel-container'>");
                carouselHtml.append("<div class='streaming-carousel' id='new-releases-carousel'>");
                for (Object[] entry : cardEntries) {
                    carouselHtml.append((String) entry[0]);
                }
                carouselHtml.append("</div></div></div>");
                html.append(carouselHtml.toString());
            }

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
            @QueryParam("sortDirection") @DefaultValue("desc") String sortDirection,
            @QueryParam("search") String search) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("movie", page, limit, sortBy, sortDirection, search);

        List<Models.ExternalVideo> externalMovies;
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.toLowerCase() + "%";
            externalMovies = Models.ExternalVideo.list("entryType = ?1 and LOWER(title) like ?2",
                    Models.ExistingVideo.MOVIE, s);
        } else {
            externalMovies = Models.ExternalVideo.list("entryType = ?1", Models.ExistingVideo.MOVIE);
        }

        long totalItems = paginatedVideos.totalCount + externalMovies.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);

        // Enrich movies with per-profile progress (batch)
        Map<Long, Models.VideoState> movieStates = videoStateService.getOrCreateBatch(paginatedVideos.videos);
        for (Models.Video movie : paginatedVideos.videos) {
            Models.VideoState vs = movieStates.get(movie.id);
            if (vs != null) {
                movie.watchProgress = vs.watchProgress;
                movie.watchProgressPercent = vs.watchProgress != null ? (int) Math.round(vs.watchProgress * 100) : 0;
                movie.watched = vs.watched;
            }
        }

        boolean hasMore = page < totalPages;
        int nextPage = page + 1;

        return movieListContent
                .data("movies", paginatedVideos.videos)
                .data("externalMovies", externalMovies)
                .data("currentPage", page)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("search", search)
                .data("totalItems", totalItems)
                .data("totalPages", totalPages)
                .data("pageNumbers", getPaginationNumbers(page, totalPages))
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/movies-fragment-more")
    @Blocking
    public String getMoviesFragmentMore(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("sortBy") @DefaultValue("dateAdded") String sortBy,
            @QueryParam("sortDirection") @DefaultValue("desc") String sortDirection,
            @QueryParam("search") String search) {

        VideoService.PaginatedVideos paginatedVideos = videoService.findPaginatedByMediaType("movie", page, limit, sortBy, sortDirection, search);

        List<Models.ExternalVideo> externalMovies;
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.toLowerCase() + "%";
            externalMovies = Models.ExternalVideo.list("entryType = ?1 and LOWER(title) like ?2",
                    Models.ExistingVideo.MOVIE, s);
        } else {
            externalMovies = Models.ExternalVideo.list("entryType = ?1", Models.ExistingVideo.MOVIE);
        }

        long totalItems = paginatedVideos.totalCount + externalMovies.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;

        // Enrich movies with per-profile progress (batch)
        Map<Long, Models.VideoState> movieStates = videoStateService.getOrCreateBatch(paginatedVideos.videos);
        for (Models.Video movie : paginatedVideos.videos) {
            Models.VideoState vs = movieStates.get(movie.id);
            if (vs != null) {
                movie.watchProgress = vs.watchProgress;
                movie.watchProgressPercent = vs.watchProgress != null ? (int) Math.round(vs.watchProgress * 100) : 0;
                movie.watched = vs.watched;
            }
        }

        return movieItemsFragment
                .data("movies", paginatedVideos.videos)
                .data("externalMovies", externalMovies)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("search", search)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    @GET
    @Path("/shows-fragment")
    @Blocking
    public String getSeriesFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
@QueryParam("sortBy") @DefaultValue("dateAdded") String sortBy,
@QueryParam("sortDirection") @DefaultValue("desc") String sortDirection,
            @QueryParam("search") String search) {
        
        VideoService.PaginatedSeries paginatedSeries = videoService.findPaginatedSeriesTitles(page, limit, sortBy, sortDirection, search);
        
        if (paginatedSeries.titles.isEmpty()) {
            String emptyState = "<div class='library-header'><h1 class='library-title'>TV Shows</h1></div>";
            if (search != null && !search.isEmpty()) {
                emptyState += "<div class='carousel-empty-state'><i class='pi pi-search'></i><h3>No results for \"" + escapeHtml(search) + "\"</h3><p>Try a different search term.</p></div>";
            } else {
                emptyState += "<div class='carousel-empty-state'><i class='pi pi-desktop'></i><h3>No shows found</h3><p>Try scanning your library or check if your episodes have series titles.</p></div>";
            }
            return emptyState;
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

        // Merge external series titles
        List<String> externalSeriesTitles = externalVideoService.findAllSeriesTitles();
        Set<String> existingTitles = entries.stream().map(e -> e.rawTitle().toLowerCase()).collect(Collectors.toSet());
        for (String extTitle : externalSeriesTitles) {
            if (existingTitles.contains(extTitle.toLowerCase())) continue;
            if (search != null && !search.trim().isEmpty()) {
                if (!extTitle.toLowerCase().contains(search.toLowerCase())) continue;
            }
            entries.add(new SeriesTitleEntry(
                extTitle,
                URLEncoder.encode(extTitle, StandardCharsets.UTF_8),
                "series-ext-" + Math.abs(extTitle.hashCode()),
                null // no sample video ID for external series
            ));
        }

        // Compute per-show watch progress using batch state loading
        Map<String, SeriesProgress> showProgress = new HashMap<>();
        Map<String, List<Models.Video>> episodesBySeries = allEpisodes.stream()
                .filter(v -> v.seriesTitle != null)
                .collect(Collectors.groupingBy(v -> v.seriesTitle.toLowerCase()));
        Map<Long, Models.VideoState> allStates = videoStateService.getOrCreateBatch(allEpisodes);
        for (SeriesTitleEntry entry : entries) {
            String key = entry.rawTitle().toLowerCase();
            List<Models.Video> seriesEps = episodesBySeries.getOrDefault(key, Collections.emptyList());
            int total = seriesEps.size();
            int watched = 0;
            for (Models.Video ep : seriesEps) {
                Models.VideoState vs = allStates.get(ep.id);
                if (vs != null && Boolean.TRUE.equals(vs.watched)) {
                    watched++;
                }
            }
            showProgress.put(entry.rawTitle(), new SeriesProgress(watched, total));
        }

        boolean hasMore = page < totalPages;
        int nextPage = page + 1;

        return seriesListContent
                .data("series", entries)
                .data("showProgress", showProgress)
                .data("currentPage", page)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("search", search)
                .data("totalItems", totalItems)
                .data("totalPages", totalPages)
                .render();
    }

    @GET
    @Path("/shows-fragment-more")
    @Blocking
    public String getSeriesFragmentMore(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("sortBy") @DefaultValue("dateAdded") String sortBy,
            @QueryParam("sortDirection") @DefaultValue("desc") String sortDirection,
            @QueryParam("search") String search) {

        VideoService.PaginatedSeries paginatedSeries = videoService.findPaginatedSeriesTitles(page, limit, sortBy, sortDirection, search);

        if (paginatedSeries.titles.isEmpty()) {
            return "";
        }

        int totalItems = (int) paginatedSeries.totalCount;
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;

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

        // Merge external series titles
        List<String> externalSeriesTitles = externalVideoService.findAllSeriesTitles();
        Set<String> existingTitles = entries.stream().map(e -> e.rawTitle().toLowerCase()).collect(Collectors.toSet());
        for (String extTitle : externalSeriesTitles) {
            if (existingTitles.contains(extTitle.toLowerCase())) continue;
            if (search != null && !search.trim().isEmpty()) {
                if (!extTitle.toLowerCase().contains(search.toLowerCase())) continue;
            }
            entries.add(new SeriesTitleEntry(
                extTitle,
                URLEncoder.encode(extTitle, StandardCharsets.UTF_8),
                "series-ext-" + Math.abs(extTitle.hashCode()),
                null
            ));
        }

        // Compute per-show watch progress
        Map<String, SeriesProgress> showProgress = new HashMap<>();
        Map<String, List<Models.Video>> episodesBySeries = allEpisodes.stream()
                .filter(v -> v.seriesTitle != null)
                .collect(Collectors.groupingBy(v -> v.seriesTitle.toLowerCase()));
        Map<Long, Models.VideoState> allStates = videoStateService.getOrCreateBatch(allEpisodes);
        for (SeriesTitleEntry entry : entries) {
            String key = entry.rawTitle().toLowerCase();
            List<Models.Video> seriesEps = episodesBySeries.getOrDefault(key, Collections.emptyList());
            int total = seriesEps.size();
            int watched = 0;
            for (Models.Video ep : seriesEps) {
                Models.VideoState vs = allStates.get(ep.id);
                if (vs != null && Boolean.TRUE.equals(vs.watched)) {
                    watched++;
                }
            }
            showProgress.put(entry.rawTitle(), new SeriesProgress(watched, total));
        }

        return seriesItemsFragment
                .data("series", entries)
                .data("showProgress", showProgress)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("sortBy", sortBy)
                .data("sortDirection", sortDirection)
                .data("search", search)
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
                String seasonName = sample != null ? sample.seasonName : null;
                seasons.add(new SeasonEntry(sn, sample != null ? sample.id : null, seasonName));
            }

            // Merge external season numbers
            List<Integer> externalSeasonNumbers = externalVideoService.findSeasonNumbersForSeries(decodedTitle);
            Set<Integer> existingSeasonNums = seasons.stream().map(s -> s.seasonNumber()).collect(Collectors.toSet());
            for (Integer extSn : externalSeasonNumbers) {
                if (!existingSeasonNums.contains(extSn)) {
                    seasons.add(new SeasonEntry(extSn, null, null));
                }
            }
            seasons.sort(Comparator.comparingInt(SeasonEntry::seasonNumber));

            Models.Video sampleVideo = finalEpisodes.isEmpty() ? null : finalEpisodes.get(0);
            
            // Find the last played video (or first one)
            Models.Video lastPlayedVideo = finalEpisodes.stream()
                    .filter(v -> v.lastWatched != null)
                    .sorted(Comparator.comparing(v -> ((Models.Video)v).lastWatched).reversed())
                    .findFirst()
                    .orElse(sampleVideo);

            // Compute per-season watch progress (batch)
            Map<Long, Models.VideoState> seasonStates = videoStateService.getOrCreateBatch(finalEpisodes);
            Map<Integer, SeasonProgress> seasonProgress = new HashMap<>();
            for (SeasonEntry entry : seasons) {
                int total = 0;
                int watched = 0;
                for (Models.Video ep : finalEpisodes) {
                    int sn = ep.seasonNumber != null ? ep.seasonNumber : 1;
                    if (sn == entry.seasonNumber()) {
                        total++;
                        Models.VideoState vs = seasonStates.get(ep.id);
                        if (vs != null && Boolean.TRUE.equals(vs.watched)) {
                            watched++;
                        }
                    }
                }
                seasonProgress.put(entry.seasonNumber(), new SeasonProgress(watched, total));
            }

            return seasonListContent
                    .data("seriesTitle", decodedTitle)
                    .data("encodedSeriesTitle", seriesTitle) // Keep original encoded for HTMX sub-requests
                    .data("seasons", seasons)
                    .data("seasonProgress", seasonProgress)
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
                            (seasonNumber.equals(v.seasonNumber) || (seasonNumber == 1 && v.seasonNumber == null)) &&
                            (v.folder == null || v.folder.isEmpty()))
                    .sorted(Comparator.comparingInt(v -> v.episodeNumber != null ? v.episodeNumber : 0))
                    .collect(Collectors.toList());
            }

            // Enrich episodes with per-profile progress (batch)
            Map<Long, Models.VideoState> epStates = videoStateService.getOrCreateBatch(episodes);
            for (Models.Video ep : episodes) {
                Models.VideoState vs = epStates.get(ep.id);
                if (vs != null) {
                    ep.watchProgress = vs.watchProgress;
                    ep.watchProgressPercent = vs.watchProgress != null ? (int) Math.round(vs.watchProgress * 100) : 0;
                    ep.watched = vs.watched;
                }
            }

            // Get sub-folders within this season
            List<String> subFolders = videoService.findSubFoldersForSeason(decodedTitle, seasonNumber);
            List<java.util.Map<String, Object>> folderEntries = new ArrayList<>();
            for (String folder : subFolders) {
                long count = videoService.countEpisodesInFolder(decodedTitle, seasonNumber, folder);
                java.util.Map<String, Object> entry = new java.util.HashMap<>();
                entry.put("name", folder);
                entry.put("count", count);
                folderEntries.add(entry);
            }

            List<Models.ExternalVideo> externalEpisodes = externalVideoService.findBySeriesAndSeason(decodedTitle, seasonNumber);

            return episodeListContent
                    .data("seriesTitle", decodedTitle)
                    .data("seasonNumber", seasonNumber)
                    .data("episodes", episodes)
                    .data("subFolders", folderEntries)
                    .data("externalEpisodes", externalEpisodes)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("encodedSeriesTitle", seriesTitle)
                    .render();
        } catch (Exception e) {
            LOG.error("Error rendering episodes fragment for show {} season {}: {}", seriesTitle, seasonNumber, e.getMessage(), e);
            return "<div class='carousel-empty-state'><i class='pi pi-exclamation-circle'></i><h3>Error loading episodes</h3><p>" + e.getMessage() + "</p></div>";
        }
    }

    @GET
    @Path("/shows/{seriesTitle}/seasons/{seasonNumber}/folders/{folderName}/episodes-fragment")
    @Blocking
    public String getFolderEpisodesFragment(
            @PathParam("seriesTitle") String seriesTitle,
            @PathParam("seasonNumber") Integer seasonNumber,
            @PathParam("folderName") String folderName) {
        try {
            String decodedTitle = java.net.URLDecoder.decode(seriesTitle, StandardCharsets.UTF_8);
            String decodedFolder = java.net.URLDecoder.decode(folderName, StandardCharsets.UTF_8);
            LOG.info("Loading episodes for series: {}, season: {}, folder: {}", decodedTitle, seasonNumber, decodedFolder);
            
            List<Models.Video> episodes = videoService.findEpisodesForSeasonAndFolder(decodedTitle, seasonNumber, decodedFolder);

            // Enrich episodes with per-profile progress (batch)
            Map<Long, Models.VideoState> folderEpStates = videoStateService.getOrCreateBatch(episodes);
            for (Models.Video ep : episodes) {
                Models.VideoState vs = folderEpStates.get(ep.id);
                if (vs != null) {
                    ep.watchProgress = vs.watchProgress;
                    ep.watchProgressPercent = vs.watchProgress != null ? (int) Math.round(vs.watchProgress * 100) : 0;
                    ep.watched = vs.watched;
                }
            }

            return folderEpisodesContent
                    .data("seriesTitle", decodedTitle)
                    .data("seasonNumber", seasonNumber)
                    .data("folderName", decodedFolder)
                    .data("episodes", episodes)
                    .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                    .data("encodedSeriesTitle", seriesTitle)
                    .render();
        } catch (Exception e) {
            LOG.error("Error rendering folder episodes for {} season {} folder {}: {}", seriesTitle, seasonNumber, folderName, e.getMessage(), e);
            return "<div class='carousel-empty-state'><i class='pi pi-exclamation-circle'></i><h3>Error loading folder</h3><p>" + e.getMessage() + "</p></div>";
        }
    }

    @GET
    @Path("/history-fragment")
    @Blocking
    public String getHistoryFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedVideos paginated = videoService.findHistoryPaginated(search, page, limit);
        
        // Enrich history videos with per-profile progress
        for (Models.Video video : paginated.videos) {
            Models.VideoState vs = videoStateService.getOrCreate(video);
            if (vs != null && vs.watchProgress != null && vs.watchProgress > 0) {
                video.watchProgress = vs.watchProgress;
                video.watchProgressPercent = (int) Math.round(vs.watchProgress * 100);
            }
        }
        
        List<Models.ExternalVideo> externalHistoryRaw;
        if (search != null && !search.trim().isEmpty()) {
            String s = search.toLowerCase();
            externalHistoryRaw = Models.ExternalVideo.<Models.ExternalVideo>list("watchProgress > 0 and (LOWER(title) like ?1 or LOWER(seriesTitle) like ?1 or LOWER(episodeTitle) like ?1 or LOWER(description) like ?1)",
                    "%" + s + "%").stream().limit(limit).collect(java.util.stream.Collectors.toList());
        } else {
            externalHistoryRaw = Models.ExternalVideo.list("watchProgress > 0 order by lastUpdated desc");
        }
        List<Map<String, Object>> externalHistory = new java.util.ArrayList<>();
        for (Models.ExternalVideo ev : externalHistoryRaw) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", ev.id);
            m.put("title", ev.title);
            m.put("seasonNumber", ev.seasonNumber);
            m.put("episodeNumber", ev.episodeNumber);
            m.put("entryType", ev.entryType != null ? ev.entryType.name() : "");
            m.put("watchProgress", ev.watchProgress);
            m.put("progressPercent", ev.watchProgress != null ? (int) Math.round(ev.watchProgress * 100) : 0);
            externalHistory.add(m);
        }
        
        int totalItems = (int) paginated.totalCount + externalHistory.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        
        return videoHistoryFragment
                .data("videos", paginated.videos)
                .data("externalHistory", externalHistory)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
                .data("threshold", 0.95)
                .render();
    }
    
    @GET
    @Path("/history-fragment-more")
    @Blocking
    public String getHistoryFragmentMore(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedVideos paginated = videoService.findHistoryPaginated(search, page, limit);
        
        for (Models.Video video : paginated.videos) {
            Models.VideoState vs = videoStateService.getOrCreate(video);
            if (vs != null && vs.watchProgress != null && vs.watchProgress > 0) {
                video.watchProgress = vs.watchProgress;
                video.watchProgressPercent = (int) Math.round(vs.watchProgress * 100);
            }
        }
        
        List<Models.ExternalVideo> externalHistoryRaw;
        if (search != null && !search.trim().isEmpty()) {
            String s = search.toLowerCase();
            externalHistoryRaw = Models.ExternalVideo.<Models.ExternalVideo>list("watchProgress > 0 and (LOWER(title) like ?1 or LOWER(seriesTitle) like ?1 or LOWER(episodeTitle) like ?1 or LOWER(description) like ?1)",
                    "%" + s + "%").stream().limit(limit).collect(java.util.stream.Collectors.toList());
        } else {
            externalHistoryRaw = Models.ExternalVideo.list("watchProgress > 0 order by lastUpdated desc");
        }
        List<Map<String, Object>> externalHistory = new java.util.ArrayList<>();
        for (Models.ExternalVideo ev : externalHistoryRaw) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", ev.id);
            m.put("title", ev.title);
            m.put("seasonNumber", ev.seasonNumber);
            m.put("episodeNumber", ev.episodeNumber);
            m.put("entryType", ev.entryType != null ? ev.entryType.name() : "");
            m.put("watchProgress", ev.watchProgress);
            m.put("progressPercent", ev.watchProgress != null ? (int) Math.round(ev.watchProgress * 100) : 0);
            externalHistory.add(m);
        }
        
        int totalItems = (int) paginated.totalCount + externalHistory.size();
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        
        return historyItemsFragment
                .data("videos", paginated.videos)
                .data("externalHistory", externalHistory)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
                .data("threshold", 0.95)
                .render();
    }

    @GET
    @Path("/admin-history-fragment")
    @Blocking
    public String getAdminHistoryFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedHistoryEntries paginated = videoService.findAllHistoryPaginated(search, page, limit);
        
        boolean hasMore = page * limit < paginated.totalCount;
        int nextPage = page + 1;
        
        return adminVideoHistoryFragment
                .data("history", paginated.entries)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
                .data("getProfileInitials", (java.util.function.Function<String, String>) this::getProfileInitials)
                .data("formatDateTime", (java.util.function.Function<java.time.LocalDateTime, String>) dt -> dt == null ? "" : dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")))
                .data("formatDateTimeISO", (java.util.function.Function<java.time.LocalDateTime, String>) dt -> dt == null ? "" : dt.atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .render();
    }
    
    @GET
    @Path("/admin-history-fragment-more")
    @Blocking
    public String getAdminHistoryFragmentMore(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedHistoryEntries paginated = videoService.findAllHistoryPaginated(search, page, limit);
        
        boolean hasMore = page * limit < paginated.totalCount;
        int nextPage = page + 1;
        
        return adminHistoryItemsFragment
                .data("history", paginated.entries)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
                .data("getProfileInitials", (java.util.function.Function<String, String>) this::getProfileInitials)
                .data("formatDateTime", (java.util.function.Function<java.time.LocalDateTime, String>) dt -> dt == null ? "" : dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")))
                .data("formatDateTimeISO", (java.util.function.Function<java.time.LocalDateTime, String>) dt -> dt == null ? "" : dt.atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .render();
    }

    @GET
    @Path("/suggestion-fragment")
    @Blocking
    public String getSuggestionFragment() {
        return suggestionFragment.render();
    }

    @POST
    @Path("/suggestion")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitSuggestion(@FormParam("content") String content) {
        if (content == null || content.trim().isEmpty()) {
            return Response.ok(ApiResponse.error("Content is required")).build();
        }
        videoSuggestionService.addSuggestion(content);
        return Response.ok(ApiResponse.success("Suggestion submitted")).build();
    }

    @GET
    @Path("/admin-suggestions-fragment")
    @Blocking
    public String getAdminSuggestionsFragment() {
        List<Models.VideoSuggestion> suggestions = videoSuggestionService.findAll();
        return adminSuggestionsFragment
                .data("suggestions", suggestions)
                .data("getProfileInitials", (java.util.function.Function<String, String>) this::getProfileInitials)
                .data("formatDateTime", (java.util.function.Function<java.time.LocalDateTime, String>) dt -> dt == null ? "" : dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")))
                .render();
    }

    @DELETE
    @Path("/suggestion/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSuggestion(@PathParam("id") Long id) {
        videoSuggestionService.delete(id);
        return Response.ok(ApiResponse.success("Suggestion deleted")).build();
    }

    @GET
    @Path("/watchlist-fragment")
    @Blocking
    public String getWatchlistFragment(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedVideos paginated = videoService.findWatchlistPaginated(search, page, limit);
        
        long totalItems = paginated.totalCount;
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        
        return videoWatchlistFragment
                .data("videos", paginated.videos)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
                .render();
    }
    
    @GET
    @Path("/watchlist-fragment-more")
    @Blocking
    public String getWatchlistFragmentMore(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit,
            @QueryParam("search") String search) {
        VideoService.PaginatedVideos paginated = videoService.findWatchlistPaginated(search, page, limit);
        
        long totalItems = paginated.totalCount;
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        
        return watchlistItemsFragment
                .data("videos", paginated.videos)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .data("search", search)
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
    @Transactional
    public String getPlaybackFragment(
            @QueryParam("videoId") Long videoId,
            @QueryParam("collectionId") Long collectionId,
            @QueryParam("entryId") Long entryId,
            @HeaderParam("User-Agent") String userAgent) {
        Models.Video item = videoService.find(videoId);
        if (item == null) return "<div class='notification is-warning'>No video available for playback</div>";

        double resumeTime = 0;

        // Get per-profile progress
        Models.VideoState progress = videoStateService.getOrCreate(item);
        if (progress != null) {
            if (progress.currentTime > 0) {
                resumeTime = progress.currentTime;
            } else if (progress.watchProgress != null && progress.watchProgress > 0 && progress.watchProgress < 0.95) {
                resumeTime = progress.watchProgress * (item.getDurationSeconds());
            }
        }

        // If the video is nearly finished (over 95%), start from the beginning
        double durationSeconds = item.getDurationSeconds();
        if (durationSeconds > 0 && (resumeTime / durationSeconds) >= 0.95) {
            resumeTime = 0;
        }

        Models.Video nextEpisode = videoService.findNextEpisode(item);
        Models.Video prevEpisode = videoService.findPreviousEpisode(item);

        boolean isMKV = item.path != null && item.path.toLowerCase().endsWith(".mkv");
        boolean needsTranscoding = isMKV || transcodingService.isTranscodeNeededForWeb(item, userAgent);

        // Load settings (auto-skip + default player)
        Models.Settings settings = settingsService.getOrCreateSettings();
        boolean autoSkipIntro = settings.getAutoSkipIntro();
        boolean autoSkipRecap = settings.getAutoSkipRecap();
        boolean autoSkipOutro = settings.getAutoSkipOutro();
        String defaultPlayer = settings.getDefaultPlayer();

        List<Map<String, Object>> carouselItems = new ArrayList<>();
        int currentCarouselIndex = 0;
        String carouselTitle = "";
        Map<String, Object> infoSection = new LinkedHashMap<>();

        buildInfoSection(infoSection, item);

        if (collectionId != null) {
            Models.MediaCollection coll = collectionService.getCollection(collectionId);
            if (coll != null) {
                carouselTitle = coll.name;
                var entries = collectionService.getEntries(collectionId);
                int idx = 0;
                for (var entry : entries) {
                    Map<String, Object> ci = new LinkedHashMap<>();
                    if (entry.video != null) {
                        ci.put("id", entry.video.id);
                        ci.put("title", entry.video.title != null ? entry.video.title : "");
                        ci.put("seriesTitle", entry.video.seriesTitle != null ? entry.video.seriesTitle : "");
                        ci.put("seasonNumber", entry.video.seasonNumber != null ? entry.video.seasonNumber : 0);
                        ci.put("episodeNumber", entry.video.episodeNumber != null ? entry.video.episodeNumber : 0);
                        ci.put("type", entry.video.type != null ? entry.video.type : "");
                        ci.put("mediaType", "video");
                        ci.put("thumbnailPath", entry.video.thumbnailPath);
                        boolean isCurrent = entry.video.id.equals(videoId);
                        ci.put("isCurrent", isCurrent);
                        if (isCurrent) currentCarouselIndex = idx;
                    } else if (entry.externalVideo != null) {
                        ci.put("id", entry.externalVideo.id);
                        ci.put("title", entry.externalVideo.title != null ? entry.externalVideo.title : "");
                        ci.put("seriesTitle", entry.externalVideo.seriesTitle != null ? entry.externalVideo.seriesTitle : "");
                        ci.put("seasonNumber", entry.externalVideo.seasonNumber != null ? entry.externalVideo.seasonNumber : 0);
                        ci.put("episodeNumber", entry.externalVideo.episodeNumber != null ? entry.externalVideo.episodeNumber : 0);
                        ci.put("type", entry.externalVideo.entryType == Models.ExistingVideo.EPISODE ? "Episode" : "");
                        ci.put("mediaType", "external");
                        ci.put("thumbnailPath", null);
                        ci.put("isCurrent", false);
                    }
                    ci.put("entryId", entry.id);
                    ci.put("collectionId", collectionId);
                    carouselItems.add(ci);
                    idx++;
                }
            }
        } else {
            String videoType = item.type != null ? item.type.toLowerCase() : "";
            if (videoType.contains("episode") && item.seriesTitle != null && !item.seriesTitle.isBlank()) {
                carouselTitle = "Episodes — " + item.seriesTitle;
                List<Video> episodes = videoService.findEpisodesForSeries(item.seriesTitle);
                int idx = 0;
                for (Video ep : episodes) {
                    Map<String, Object> ci = new LinkedHashMap<>();
                    ci.put("id", ep.id);
                    ci.put("title", ep.title != null ? ep.title : "");
                    ci.put("seriesTitle", ep.seriesTitle != null ? ep.seriesTitle : "");
                    ci.put("seasonNumber", ep.seasonNumber != null ? ep.seasonNumber : 0);
                    ci.put("episodeNumber", ep.episodeNumber != null ? ep.episodeNumber : 0);
                    ci.put("type", ep.type != null ? ep.type : "");
                    ci.put("mediaType", "video");
                    ci.put("thumbnailPath", ep.thumbnailPath);
                    boolean isCurrent = ep.id.equals(videoId);
                    ci.put("isCurrent", isCurrent);
                    if (isCurrent) currentCarouselIndex = idx;
                    ci.put("entryId", null);
                    ci.put("collectionId", null);
                    carouselItems.add(ci);
                    idx++;
                }
            } else {
                carouselTitle = "Recommended";
                List<Video> trending = videoService.findTrending(20);
                int idx = 0;
                for (Video v : trending) {
                    Map<String, Object> ci = new LinkedHashMap<>();
                    ci.put("id", v.id);
                    ci.put("title", v.title != null ? v.title : "");
                    ci.put("seriesTitle", v.seriesTitle != null ? v.seriesTitle : "");
                    ci.put("seasonNumber", v.seasonNumber != null ? v.seasonNumber : 0);
                    ci.put("episodeNumber", v.episodeNumber != null ? v.episodeNumber : 0);
                    ci.put("type", v.type != null ? v.type : "");
                    ci.put("mediaType", "video");
                    ci.put("thumbnailPath", v.thumbnailPath);
                    boolean isCurrent = v.id.equals(videoId);
                    ci.put("isCurrent", isCurrent);
                    if (isCurrent) currentCarouselIndex = idx;
                    ci.put("entryId", null);
                    ci.put("collectionId", null);
                    carouselItems.add(ci);
                    idx++;
                }
            }
        }

        boolean hasCarousel = !carouselItems.isEmpty();

        return playbackFragment
                .data("item", item)
                .data("resumeTime", resumeTime)
                .data("needsTranscoding", needsTranscoding)
                .data("nextEpisodeId", nextEpisode != null ? nextEpisode.id : null)
                .data("prevEpisodeId", prevEpisode != null ? prevEpisode.id : null)
                .data("autoSkipIntro", autoSkipIntro)
                .data("autoSkipRecap", autoSkipRecap)
                .data("autoSkipOutro", autoSkipOutro)
                .data("defaultPlayer", defaultPlayer)
                .data("carouselItems", carouselItems)
                .data("currentCarouselIndex", currentCarouselIndex)
                .data("carouselTitle", carouselTitle)
                .data("hasCarousel", hasCarousel)
                .data("collectionId", collectionId)
                .data("infoSection", infoSection)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .data("json", (ValueResolver) (ctx) -> {
                    try { return java.util.concurrent.CompletableFuture.completedFuture(objectMapper.writeValueAsString(ctx.getBase())); }
                    catch (Exception e) { return java.util.concurrent.CompletableFuture.completedFuture("{}"); }
                }).render();
    }

    private void buildInfoSection(Map<String, Object> info, Models.Video item) {
        info.put("infoType", item.type != null && item.type.equalsIgnoreCase("episode") ? "episode" : "movie");
        info.put("title", item.title != null ? item.title : "");
        info.put("seriesTitle", item.seriesTitle != null ? item.seriesTitle : "");
        info.put("seasonNumber", item.seasonNumber != null ? item.seasonNumber : 0);
        info.put("episodeNumber", item.episodeNumber != null ? item.episodeNumber : 0);
        info.put("episodeTitle", item.episodeTitle != null ? item.episodeTitle : "");
        info.put("releaseYear", item.releaseYear);
        info.put("runtimeMins", item.runtimeMins);
        info.put("mpaaRating", item.mpaaRating != null ? item.mpaaRating : "");
        info.put("overview", item.overview != null ? item.overview : (item.description != null ? item.description : ""));
        info.put("tagline", item.tagline != null ? item.tagline : "");
        info.put("genres", item.genres);
        info.put("imdbRating", item.imdbRating);
        info.put("tmdbRating", item.tmdbRating);
        info.put("metacriticRating", item.metacriticRating);
        info.put("cast", item.cast);
        info.put("directors", item.directors);
        info.put("writers", item.writers);
        info.put("productionCompanies", item.productionCompanies);
        info.put("awards", item.awards != null ? item.awards : "");
        info.put("budget", item.budget);
        info.put("revenue", item.revenue);
        info.put("originalLanguage", item.originalLanguage != null ? item.originalLanguage : "");
        info.put("productionCountries", item.productionCountries != null ? item.productionCountries : "");
        info.put("releaseDate", item.releaseDate != null ? item.releaseDate : "");
        info.put("trailerUrl", item.trailerUrl != null ? item.trailerUrl : "");
        info.put("parentsGuide", item.parentsGuide != null ? item.parentsGuide : "");
        info.put("collectionName", item.collectionName != null ? item.collectionName : "");
        info.put("franchiseName", item.franchiseName != null ? item.franchiseName : "");
        info.put("resolution", item.resolution != null ? item.resolution : "");
        info.put("displayResolution", item.displayResolution != null ? item.displayResolution : "");
        info.put("videoCodec", item.videoCodec != null ? item.videoCodec : "");
        info.put("audioChannels", item.audioChannels);
        info.put("status", item.status != null ? item.status : "");
        info.put("networks", item.networks);
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
        boolean isEpisode = item.type != null && "episode".equalsIgnoreCase(item.type);
        String dataAttrs = isEpisode && item.seriesTitle != null
            ? "data-video-id='" + item.id + "' data-series-title='" + escapeHtml(item.seriesTitle) + "' data-type='Episode'"
            : "data-video-id='" + item.id + "' data-type='" + (item.type != null ? item.type : "Video") + "'";

        // Build meta - episode number or release year
        String meta = "";
        if (isEpisode) {
            meta = "S" + (item.seasonNumber != null ? item.seasonNumber : "?") + "E" + (item.episodeNumber != null ? item.episodeNumber : "?");
        } else if (item.releaseYear != null) {
            meta = String.valueOf(item.releaseYear).replace("%", "%%");
        }

        // Progress bar HTML - get per-profile watch progress
        String progressBar = "";
        Models.VideoState progress = videoStateService.getOrCreate(item);
        if (progress != null && progress.watchProgress != null && progress.watchProgress > 0) {
            int progressPercent = (int)(progress.watchProgress * 100);
            progressBar = "<div class='card-progress-container'><div class='card-progress-bar' style='width: " + progressPercent + "%%'></div></div>";
        }

        return String.format(
            "<div class='streaming-card' %s onclick=\"window.selectItem(%d, 'details')\">" +
            "<div class='card-image-container'><img class='card-image' src='/api/video/thumbnail/%d' loading='lazy'>" +
            "<div class='card-play-overlay'><div class='card-play-btn' onclick=\"event.stopPropagation(); window.selectItem(%d, 'play')\"><i class='pi pi-play'></i></div></div>" +
            progressBar +
            "</div><div class='card-content'><div class='card-title'>%s</div><div class='card-meta'>%s</div></div></div>",
            dataAttrs, item.id, item.id, item.id, escapeHtml(title), meta
        );
    }

    private String createExternalCardHTML(Models.ExternalVideo ev) {
        String title = ev.title != null ? escapeHtml(ev.title) : "External";
        boolean isEpisode = ev.entryType == Models.ExistingVideo.EPISODE;
        String meta = isEpisode
            ? "S" + (ev.seasonNumber != null ? ev.seasonNumber : "?") + "E" + (ev.episodeNumber != null ? ev.episodeNumber : "?")
            : "External";
        // Try to use the series thumbnail for external episodes
        Long thumbnailId = null;
        if (isEpisode && ev.seriesTitle != null && !ev.seriesTitle.isBlank()) {
            Models.Video sample = Models.Video.find("type = ?1 and seriesTitle = ?2 and isActive = ?3",
                    "episode", ev.seriesTitle, true).firstResult();
            if (sample != null) thumbnailId = sample.id;
        }
        if (thumbnailId != null) {
            return "<div class='streaming-card' onclick=\"playExternalEntry(" + ev.id + ")\">" +
                   "<div class='card-image-container'>" +
                   "<img class='card-image' src='/api/video/thumbnail/" + thumbnailId + "' loading='lazy'>" +
                   "<div class='card-play-overlay'><div class='card-play-btn' onclick=\"event.stopPropagation(); playExternalEntry(" + ev.id + ")\"><i class='pi pi-play'></i></div></div>" +
                   "<div style='position:absolute;top:8px;right:8px;z-index:2;'><span class='tag is-warning is-light is-small' style='font-size:0.6rem;'>Ext</span></div>" +
                   "</div>" +
                   "<div class='card-content'><div class='card-title'>" + title + "</div><div class='card-meta'>" + escapeHtml(meta) + "</div></div></div>";
        }
        // Fallback: stylized placeholder
        String icon = isEpisode ? "pi pi-desktop" : "pi pi-video";
        return "<div class='streaming-card' onclick=\"playExternalEntry(" + ev.id + ")\">" +
               "<div class='card-image-container'>" +
               "<div class='carousel-empty-state' style='height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;background:rgba(255,255,255,0.03);'>" +
               "<i class='" + icon + "' style='font-size:2rem;opacity:0.4;color:" + (isEpisode ? "#00d2d3" : "#5f27cd") + ";'></i>" +
               "<span class='tag is-small is-light mt-2' style='font-size:0.6rem;opacity:0.6;'>" + (isEpisode ? "Series" : "External") + "</span>" +
               "</div>" +
               "</div>" +
               "<div class='card-content'><div class='card-title'>" + title + "</div><div class='card-meta'>" + escapeHtml(meta) + "</div></div></div>";
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

    private String createCollectionCarouselHTML(List<CollectionWatchProgress> items) {
        StringBuilder html = new StringBuilder("<div class='streaming-carousel-section'>");
        html.append("<div class='carousel-header'>");
        html.append("<div class='carousel-title-section'>");
        html.append("<i class='pi pi-th-large' style='color: #00b894'></i>");
        html.append("<h2 class='carousel-title'>Continue Watching Collections</h2>");
        html.append("<span class='carousel-badge'>COLLECTIONS</span>");
        html.append("</div>");
        html.append("<div class='carousel-controls'>");
        html.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('collection-progress-carousel', 'left')\"><i class='pi pi-chevron-left'></i></button>");
        html.append("<button class='carousel-nav-btn' onclick=\"window.scrollCarousel('collection-progress-carousel', 'right')\"><i class='pi pi-chevron-right'></i></button>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='carousel-container'>");
        html.append("<div class='streaming-carousel' id='collection-progress-carousel'>");
        for (CollectionWatchProgress p : items) {
            if (p.collection == null) continue;
            String name = escapeHtml(p.collection.name != null ? p.collection.name : "Collection");
            int pct = (int) Math.round(p.progress * 100);
            Long thumbnailId = null;
            if (p.lastVideoId != null) thumbnailId = p.lastVideoId;
            if (thumbnailId == null && p.collection.coverVideoId != null) thumbnailId = p.collection.coverVideoId;
            if (thumbnailId == null) {
                Models.CollectionEntry sample = Models.CollectionEntry.find("collection = ?1 order by orderIndex", p.collection).firstResult();
                if (sample != null && sample.video != null) thumbnailId = sample.video.id;
            }
            String imgTag = thumbnailId != null
                ? "<img class='card-image' src='/api/video/thumbnail/" + thumbnailId + "' loading='lazy'>"
                : "<div class='carousel-empty-state' style='height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;background:rgba(255,255,255,0.04);'><i class='pi pi-th-large' style='font-size:2rem;opacity:0.3;color:#00b894;'></i></div>";
            html.append("<div class='streaming-card' onclick=\"window.playCollection(")
                .append(p.collection.id).append(", ").append(p.lastEntryIndex).append(")\">")
                .append("<div class='card-image-container'>").append(imgTag)
                .append("<div class='card-play-overlay'><div class='card-play-btn' onclick=\"event.stopPropagation(); window.playCollection(")
                .append(p.collection.id).append(", ").append(p.lastEntryIndex).append(")\"><i class='pi pi-play'></i></div></div>")
                .append("<div class='continue-progress'><div class='progress-bar' style='width:").append(pct).append("%;'></div></div>")
                .append("</div>")
                .append("<div class='card-content'><div class='card-title'>").append(name).append("</div>")
                .append("<div class='card-meta'>").append(p.completedEntries).append("/").append(p.totalEntries).append(" watched</div></div>")
                .append("</div>");
        }
        html.append("</div></div></div>");
        return html.toString();
    }

    private Map<String, Object> getCarouselData() {
        List<Models.Video> all = Models.Video.list("isActive", true);
        Map<String, Object> data = new HashMap<>();
        
        // Continue Watching - based on per-profile VideoState progress
        java.util.Set<String> seenContinue = new java.util.HashSet<>();
        List<Models.Video> continueWatching = new java.util.ArrayList<>();
        
        List<Models.VideoState> inProgress = videoStateService.getInProgressVideos();
        for (Models.VideoState vs : inProgress) {
            if (vs.video != null && vs.video.isActive) {
                String key = getDedupeKey(vs.video);
                if (seenContinue.add(key)) {
                    continueWatching.add(vs.video);
                    // Attach per-profile progress to video for UI display
                    vs.video.watchProgress = vs.watchProgress;
                }
                if (continueWatching.size() >= 10) break;
            }
        }
        data.put("continueWatching", continueWatching);
        
        // New releases - dedupe by show/movie to avoid multiple episodes of same show
        java.util.Set<String> seenNewReleases = new java.util.HashSet<>();
        data.put("newReleases", all.stream()
            .sorted((v1, v2) -> (v2.dateAdded != null ? v2.dateAdded : java.time.LocalDateTime.MIN).compareTo(v1.dateAdded != null ? v1.dateAdded : java.time.LocalDateTime.MIN))
            .filter(v -> {
                String key = getDedupeKey(v);
                return seenNewReleases.add(key);
            })
            .limit(20).collect(Collectors.toList()));
        
        data.put("movies", all.stream().filter(v -> v.type != null && "movie".equalsIgnoreCase(v.type)).limit(20).collect(Collectors.toList()));
        
        // TV Shows - dedupe by series title
        java.util.Set<String> seenShows = new java.util.HashSet<>();
        data.put("tvShows", all.stream()
            .filter(v -> v.type != null && "episode".equalsIgnoreCase(v.type) && v.seriesTitle != null)
            .filter(v -> {
                String normalized = v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                return seenShows.add(normalized);
            })
            .limit(20).collect(Collectors.toList()));
        
        // Trending - dedupe by show/movie
        java.util.Set<String> seenTrending = new java.util.HashSet<>();
        data.put("trending", all.stream()
            .skip(Math.min(10, all.size()))
            .filter(v -> {
                String key = getDedupeKey(v);
                return seenTrending.add(key);
            })
            .limit(15).collect(Collectors.toList()));
        return data;
    }
    
    private String getDedupeKey(Models.Video v) {
        if (v.type != null && "episode".equalsIgnoreCase(v.type) && v.seriesTitle != null) {
            return "show:" + v.seriesTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        return "video:" + v.id;
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
    public record SeasonEntry(Integer seasonNumber, Long sampleVideoId, String seasonName) {}
    public static class SeasonProgress {
        public int watched;
        public int total;
        public int percent;
        public SeasonProgress(int watched, int total) {
            this.watched = watched;
            this.total = total;
            this.percent = total > 0 ? watched * 100 / total : 0;
        }
    }
    public static class SeriesProgress {
        public int watched;
        public int total;
        public int percent;
        public SeriesProgress(int watched, int total) {
            this.watched = watched;
            this.total = total;
            this.percent = total > 0 ? watched * 100 / total : 0;
        }
    }
    
    private String getProfileInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
    
    private String formatDateTime(java.time.LocalDateTime dt) {
        if (dt == null) return "";
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
        return dt.format(formatter);
    }
}
