package Services;

import Models.Settings;
import Models.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class VideoMetadataService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoMetadataService.class);
    
    @Inject
    SettingsService settingsService;

    @Inject
    IMDbApiService imdbApiService;

    @Inject
    IntroDbService introDbService;

    @Inject
    VideoService videoService;

    // TMDb
    private static final String TMDB_SEARCH_MOVIE = "https://api.themoviedb.org/3/search/movie?api_key=%s&query=%s";
    private static final String TMDB_SEARCH_TV = "https://api.themoviedb.org/3/search/tv?api_key=%s&query=%s";
    private static final String TMDB_MOVIE_DETAILS = "https://api.themoviedb.org/3/movie/%s?api_key=%s&append_to_response=credits,release_dates,images";
    private static final String TMDB_TV_DETAILS = "https://api.themoviedb.org/3/tv/%s?api_key=%s&append_to_response=credits,external_ids";
    private static final String TMDB_EPISODE_DETAILS = "https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?api_key=%s&append_to_response=credits,images";
    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    private static final String TMDB_IMAGE_ORIGINAL = "https://image.tmdb.org/t/p/original";
    
    // OMDb
    private static final String OMDB_URL = "https://www.omdbapi.com/?apikey=%s&i=%s&plot=full";
    private static final String OMDB_SEARCH_URL = "https://www.omdbapi.com/?apikey=%s&t=%s&y=%s&plot=full";
    
    // Free IMDb Dev API
    private static final String IMDB_DEV_TITLE_URL = "https://api.imdbapi.dev/titles/%s";
    private static final String IMDB_DEV_SEARCH_URL = "https://api.imdbapi.dev/search/titles?query=%s";
    
    // TVMaze (Free, no key)
    private static final String TVMAZE_SEARCH = "https://api.tvmaze.com/singlesearch/shows?q=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache for show IDs to avoid rate limits during batch reloads
    private final Map<String, String> seriesImdbIdCache = new ConcurrentHashMap<>();

    @jakarta.transaction.Transactional
    public void enrichVideoWithIntroData(Models.Video video) {
        if (video == null || !"episode".equalsIgnoreCase(video.type)) return;
        
        // Ensure we are working with a managed entity
        final Models.Video managedVideo = Models.Video.findById(video.id);
        if (managedVideo == null) return;

        // 1. Ensure we have Show IMDb ID (Required for IntroDB TV lookups)
        if (managedVideo.showImdbId == null || managedVideo.showImdbId.isBlank()) {
            LOG.info("Show IMDb ID missing for {}, attempting to resolve...", managedVideo.seriesTitle);
            managedVideo.showImdbId = findSeriesImdbId(managedVideo);
        }
        
        // 2. Fetch Intro/Outro/Recap data if we have the show ID
        if (managedVideo.showImdbId != null && !managedVideo.showImdbId.isBlank() && 
            managedVideo.seasonNumber != null && managedVideo.episodeNumber != null) {
            
            LOG.info("Refreshing IntroDB data for video {} (S{}E{}) using series ID: {}", managedVideo.id, managedVideo.seasonNumber, managedVideo.episodeNumber, managedVideo.showImdbId);
            
            introDbService.fetchAllMetadata(managedVideo.showImdbId, managedVideo.seasonNumber, managedVideo.episodeNumber)
                .ifPresentOrElse(m -> {
                    m.intro.ifPresent(ts -> {
                        managedVideo.introStart = ts.start;
                        managedVideo.introEnd = ts.end;
                        LOG.info("Updated intro for video {}: {}-{}", managedVideo.id, ts.start, ts.end);
                    });
                    m.outro.ifPresent(ts -> {
                        managedVideo.outroStart = ts.start;
                        managedVideo.outroEnd = ts.end;
                        LOG.info("Updated outro for video {}: {}-{}", managedVideo.id, ts.start, ts.end);
                    });
                    m.recap.ifPresent(ts -> {
                        managedVideo.recapStart = ts.start;
                        managedVideo.recapEnd = ts.end;
                        LOG.info("Updated recap for video {}: {}-{}", managedVideo.id, ts.start, ts.end);
                    });
                }, () -> LOG.warn("IntroDB returned no data for series {} S{}E{}", managedVideo.showImdbId, managedVideo.seasonNumber, managedVideo.episodeNumber));
            
            managedVideo.persistAndFlush();
        } else {
            LOG.warn("Cannot fetch IntroDB data: Missing ShowImdbId ({}), Season ({}), or Episode ({})", 
                managedVideo.showImdbId, managedVideo.seasonNumber, managedVideo.episodeNumber);
        }
    }

    /**
     * Helper to find a Series IMDb ID using the free IMDb Dev API.
     */
    private String findSeriesImdbId(Models.Video video) {
        String searchTitle = "episode".equalsIgnoreCase(video.type) ? video.seriesTitle : video.title;
        if (searchTitle == null || searchTitle.isBlank()) return null;
        
        if (seriesImdbIdCache.containsKey(searchTitle)) {
            return seriesImdbIdCache.get(searchTitle);
        }
        
        try {
            LOG.info("Searching for Show IMDb ID for: {}", searchTitle);
            String searchUrl = String.format(IMDB_DEV_SEARCH_URL, URLEncoder.encode(searchTitle, StandardCharsets.UTF_8));
            JsonNode searchRoot = fetchJson(searchUrl);
            
            if (searchRoot != null && searchRoot.path("titles").isArray()) {
                for (JsonNode res : searchRoot.path("titles")) {
                    String type = res.path("type").asText();
                    
                    // Prefer TV Series matches for episodes
                    if ("episode".equalsIgnoreCase(video.type) && !type.toLowerCase().contains("tv")) continue;
                    
                    String id = res.path("id").asText();
                    if (id != null && !id.isBlank()) {
                        LOG.info("Matched series ID via IMDb Dev API: {} -> {}", searchTitle, id);
                        seriesImdbIdCache.put(searchTitle, id);
                        
                        // Update the series metadata if this is a series lookup
                        if ("episode".equalsIgnoreCase(video.type)) {
                            videoService.updateSeriesMetadata(video.seriesTitle, null, null, id);
                        }
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to find series IMDb ID: {}", e.getMessage());
        }
        
        // Final fallback: check the older API service if the new one failed
        Optional<String> fallbackId = imdbApiService.findShowImdbId(searchTitle);
        if (fallbackId.isPresent()) {
            seriesImdbIdCache.put(searchTitle, fallbackId.get());
            return fallbackId.get();
        }
        
        return null;
    }

    private String getApiKey() {
        String key = settingsService.getOrCreateSettings().getTmdbApiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("TMDB_API_KEY");
        }
        return key;
    }

    private String getOmdbApiKey() {
        String key = settingsService.getOrCreateSettings().getOmdbApiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("OMDB_API_KEY");
        }
        return key;
    }

    @jakarta.transaction.Transactional
    public void fetchAndEnrichMetadata(Models.Video video) {
        if (video == null) return;
        
        // Ensure we are working with a managed entity to avoid LazyInitializationException
        video = Models.Video.findById(video.id);
        if (video == null) return;

        LOG.info("DEBUG: fetchAndEnrichMetadata for '{}' (Type: {}, S{}E{})", 
                video.title, video.type, video.seasonNumber, video.episodeNumber);
        
        String tmdbKey = getApiKey();
        String omdbKey = getOmdbApiKey();

        try {
            // 1. TMDB Enrichment (Core metadata and images)
            if (tmdbKey != null && !tmdbKey.isBlank()) {
                if ("movie".equalsIgnoreCase(video.type)) {
                    enrichMovieMetadata(video, tmdbKey);
                } else if ("episode".equalsIgnoreCase(video.type)) {
                    enrichEpisodeMetadata(video, tmdbKey);
                }
            }

            // 2. OMDb Enrichment (Additional ratings, Rotten Tomatoes, detailed Plot)
            if (omdbKey != null && !omdbKey.isBlank()) {
                enrichWithOmdbMetadata(video, omdbKey);
            }
            
            // 3. Free IMDb Dev API Enrichment (Always attempt)
            enrichWithImdbDevMetadata(video);

            // 4. IntroDB Enrichment (Freshly sync intro/outro timestamps)
            if ("episode".equalsIgnoreCase(video.type)) {
                enrichVideoWithIntroData(video);
            }

            // Final safety check for title - aggressively fix technical names and prioritize official episode titles
            if ("episode".equalsIgnoreCase(video.type)) {
                // If we found an official episode title, use it as the primary title
                if (video.episodeTitle != null && !video.episodeTitle.isBlank()) {
                    LOG.info("Updating title to official episode name: {}", video.episodeTitle);
                    video.title = video.episodeTitle;
                } 
                // Otherwise, if the title is still technical noise, fall back to a clean S#E# format
                else if (video.title == null || video.title.isBlank() || video.title.startsWith(".") || 
                    video.title.toLowerCase().contains("720p") || video.title.toLowerCase().contains("1080p")) {
                    
                    video.title = video.seriesTitle + " - S" + video.seasonNumber + "E" + video.episodeNumber;
                }
            }

            // Ensure we merge the changes back to the database session
            video.getEntityManager().merge(video);
            LOG.info("DEBUG: Metadata enrichment successful for: {}", video.title);
        } catch (Exception e) {
            LOG.error("DEBUG: Metadata enrichment FAILED for {}: {}", video.title, e.getMessage(), e);
        }
    }

    private void enrichWithImdbDevMetadata(Models.Video video) {
        try {
            // 1. Ensure we have the Series/Show IMDb ID first
            if (video.showImdbId == null || video.showImdbId.isBlank()) {
                video.showImdbId = findSeriesImdbId(video);
            }
            
            String seriesId = video.showImdbId;

            // For movies, the "seriesId" is just the movie's own ID
            String targetId = ("episode".equalsIgnoreCase(video.type)) ? seriesId : video.imdbId;
            if (targetId == null || targetId.isBlank()) targetId = seriesId;

            // 2. If it's an episode, fetch the official episode title and specific ID
            if ("episode".equalsIgnoreCase(video.type) && seriesId != null && !seriesId.isBlank() && video.seasonNumber != null && video.episodeNumber != null) {
                String episodesUrl = String.format(IMDB_DEV_TITLE_URL + "/episodes", seriesId);
                LOG.info("Fetching episodes from IMDb: {}", episodesUrl);
                JsonNode episodesRoot = fetchJson(episodesUrl);
                if (episodesRoot != null && episodesRoot.path("episodes").isArray()) {
                    for (JsonNode ep : episodesRoot.path("episodes")) {
                        String epSeason = ep.path("season").asText();
                        int epNum = ep.path("episodeNumber").asInt();
                        
                        if (String.valueOf(video.seasonNumber).equals(epSeason) && epNum == video.episodeNumber) {
                            String epTitle = ep.path("title").asText();
                            if (epTitle != null && !epTitle.isBlank()) {
                                LOG.info("IMDb MATCH FOUND: S{}E{} = {}", epSeason, epNum, epTitle);
                                video.episodeTitle = epTitle;
                            }
                            
                            // Get the EPISODE's specific IMDb ID
                            String epId = ep.path("id").asText();
                            if (epId != null && !epId.isBlank()) {
                                video.imdbId = epId;
                                targetId = epId; 
                            }
                            break;
                        }
                    }
                }
                
                // FALLBACK: If IMDb didn't have the title, check if TMDB enrichment (which ran earlier) found it
                if ((video.episodeTitle == null || video.episodeTitle.isBlank()) && video.tmdbId != null) {
                    LOG.info("IMDb title missing, checking TMDB fallback...");
                    // (enrichEpisodeMetadata already sets video.episodeTitle)
                }
            }

            if (targetId != null && !targetId.isBlank()) {
                // 3. Fetch Full Title Details (Movie details or specific Episode details)
                String url = String.format(IMDB_DEV_TITLE_URL, targetId);
                JsonNode root = fetchJson(url);
                if (root != null && !root.has("errorMessage")) {
                    populateImdbDevFields(video, root);
                }

                // 4. Extended Metadata
                fetchAkas(video, targetId);
                fetchCredits(video, targetId);
                fetchParentsGuide(video, targetId);
                fetchTrailers(video, targetId);
                fetchReleaseDates(video, targetId);
                fetchCompanyCredits(video, targetId);

                LOG.info("DEBUG: Full IMDb Dev enrichment completed for: {}", video.title);
            }
        } catch (Exception e) {
            LOG.error("DEBUG: IMDb Dev enrichment failed: {}", e.getMessage(), e);
        }
    }

    private void populateImdbDevFields(Video video, JsonNode root) {
        // Rating
        JsonNode ratingNode = root.path("rating");
        if (video.imdbRating == null || video.imdbRating == 0.0) {
            double aggregateRating = ratingNode.path("aggregateRating").asDouble();
            if (aggregateRating > 0) {
                video.imdbRating = aggregateRating;
            }
        }
        if (video.voteCount == null || video.voteCount == 0) {
            video.voteCount = ratingNode.path("voteCount").asInt();
        }

        // Metacritic
        if (video.metacriticRating == null) {
            int score = root.path("metacritic").path("score").asInt();
            if (score > 0) video.metacriticRating = score;
        }

        // Plot & Runtime
        if (root.has("plot")) {
            String imdbPlot = root.get("plot").asText();
            if (video.overview == null || video.overview.length() < imdbPlot.length()) {
                video.overview = imdbPlot;
            }
        }
        if (video.runtimeMins == null) {
            int seconds = root.path("runtimeSeconds").asInt();
            if (seconds > 0) video.runtimeMins = seconds / 60;
        }

        // Identification
        if (video.mpaaRating == null || video.mpaaRating.isBlank()) {
            video.mpaaRating = root.path("contentRating").asText();
        }

        // Genres
        if (video.genres == null || video.genres.isEmpty()) {
            JsonNode genresNode = root.path("genres");
            if (genresNode.isArray()) {
                video.genres = new ArrayList<>();
                for (JsonNode g : genresNode) {
                    video.genres.add(g.asText());
                }
            }
        }

        // Directors, Writers, Stars
        populatePeople(video, root);
    }

    private void populatePeople(Video video, JsonNode root) {
        // Directors
        if (video.directors == null || video.directors.isEmpty()) {
            video.directors = new ArrayList<>();
            for (JsonNode person : root.path("directors")) {
                String name = person.path("displayName").asText();
                if (!name.isEmpty()) video.directors.add(name);
            }
        }
        // Writers
        if (video.writers == null || video.writers.isEmpty()) {
            video.writers = new ArrayList<>();
            for (JsonNode person : root.path("writers")) {
                String name = person.path("displayName").asText();
                if (!name.isEmpty()) video.writers.add(name);
            }
        }
        // Stars/Cast
        if (video.cast == null || video.cast.isEmpty()) {
            video.cast = new ArrayList<>();
            for (JsonNode person : root.path("stars")) {
                String name = person.path("displayName").asText();
                if (!name.isEmpty()) video.cast.add(name);
            }
        }
    }

    private void fetchParentsGuide(Video video, String imdbId) {
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/parentsGuide", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("parentsGuide").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : root.path("parentsGuide")) {
                    String category = item.path("category").asText();
                    JsonNode breakdowns = item.path("severityBreakdowns");
                    String severity = "Unknown";
                    
                    if (breakdowns.isArray() && breakdowns.size() > 0) {
                        // Pick the severity with the highest vote count or just the first one
                        severity = breakdowns.get(0).path("severityLevel").asText();
                    }
                    
                    if (!category.isEmpty()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(category.replace("_", " ")).append(": ").append(severity);
                    }
                }
                video.parentsGuide = sb.toString();
            }
        } catch (Exception e) { LOG.warn("IMDb Dev Parents Guide failed: {}", e.getMessage()); }
    }

    private void fetchReleaseDates(Video video, String imdbId) {
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/releaseDates", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("releaseDates").isArray()) {
                for (JsonNode rd : root.path("releaseDates")) {
                    String countryCode = rd.path("country").path("code").asText();
                    JsonNode dateObj = rd.path("releaseDate");
                    String dateStr = String.format("%04d-%02d-%02d", 
                        dateObj.path("year").asInt(), 
                        dateObj.path("month").asInt(), 
                        dateObj.path("day").asInt());
                    
                    if ("US".equalsIgnoreCase(countryCode)) {
                        video.releaseDate = dateStr;
                        break;
                    }
                    if (video.releaseDate == null) video.releaseDate = dateStr;
                }
            }
        } catch (Exception e) { LOG.warn("IMDb Dev Release Dates failed: {}", e.getMessage()); }
    }

    private void fetchAkas(Video video, String imdbId) {
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/akas", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("akas").isArray()) {
                if (video.akas == null) video.akas = new ArrayList<>();
                for (JsonNode aka : root.path("akas")) {
                    String text = aka.path("text").asText();
                    if (!text.isEmpty() && !video.akas.contains(text)) video.akas.add(text);
                }
            }
        } catch (Exception e) { LOG.warn("IMDb Dev AKAs failed: {}", e.getMessage()); }
    }

    private void fetchCredits(Video video, String imdbId) {
        // Credits are now largely handled by populatePeople from the main Title response.
        // This method can be used for supplemental cast discovery if needed.
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/credits", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("cast").isArray()) {
                if (video.cast == null) video.cast = new ArrayList<>();
                for (JsonNode person : root.path("cast")) {
                    String name = person.path("displayName").asText();
                    if (!name.isEmpty() && !video.cast.contains(name)) video.cast.add(name);
                }
            }
        } catch (Exception e) { LOG.warn("IMDb Dev Credits failed: {}", e.getMessage()); }
    }

    private void fetchTrailers(Video video, String imdbId) {
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/videos", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("videos").isArray()) {
                for (JsonNode v : root.path("videos")) {
                    if ("TRAILER".equalsIgnoreCase(v.path("type").asText())) {
                        video.trailerUrl = v.path("url").asText();
                        break;
                    }
                }
            }
        } catch (Exception e) { LOG.warn("IMDb Dev Trailers failed: {}", e.getMessage()); }
    }

    private void fetchCompanyCredits(Video video, String imdbId) {
        try {
            String url = String.format(IMDB_DEV_TITLE_URL + "/companyCredits", imdbId);
            JsonNode root = fetchJson(url);
            if (root != null && root.has("productionCompanies")) {
                if (video.productionCompanies == null) video.productionCompanies = new ArrayList<>();
                for (JsonNode comp : root.path("productionCompanies")) {
                    String name = comp.path("name").asText();
                    if (!name.isEmpty() && !video.productionCompanies.contains(name)) {
                        video.productionCompanies.add(name);
                    }
                }
            }
        } catch (Exception e) { LOG.warn("IMDb Dev Company Credits failed: {}", e.getMessage()); }
    }

    private void enrichWithOmdbMetadata(Models.Video video, String apiKey) {
        try {
            String url = (video.imdbId != null && !video.imdbId.isBlank()) ? 
                String.format(OMDB_URL, apiKey, video.imdbId) :
                String.format(OMDB_SEARCH_URL, apiKey, URLEncoder.encode(video.title, StandardCharsets.UTF_8), video.releaseYear != null ? video.releaseYear : "");

            JsonNode root = fetchJson(url);
            if (root == null || root.path("Response").asText().equalsIgnoreCase("False")) return;

            if (root.has("imdbRating")) {
                try { video.imdbRating = Double.parseDouble(root.get("imdbRating").asText()); } catch (Exception ignored) {}
            }
            if (video.mpaaRating == null || video.mpaaRating.isBlank()) {
                video.mpaaRating = root.path("Rated").asText();
            }
            if (video.awards == null || video.awards.isBlank()) {
                video.awards = root.path("Awards").asText();
            }
            if (root.has("Plot")) {
                String omdbPlot = root.get("Plot").asText();
                if (video.overview == null || video.overview.length() < omdbPlot.length()) video.overview = omdbPlot;
            }
        } catch (Exception e) { LOG.error("OMDb enrichment failed: {}", e.getMessage()); }
    }

    private void enrichMovieMetadata(Video video, String apiKey) throws IOException, InterruptedException {
        if (video.tmdbId == null) {
            String query = URLEncoder.encode(video.title, StandardCharsets.UTF_8);
            String url = String.format(TMDB_SEARCH_MOVIE, apiKey, query);
            JsonNode root = fetchJson(url);
            if (root != null && root.path("results").size() > 0) {
                video.tmdbId = root.path("results").get(0).path("id").asText();
            }
        }

        if (video.tmdbId != null) {
            String url = String.format(TMDB_MOVIE_DETAILS, video.tmdbId, apiKey);
            JsonNode root = fetchJson(url);
            if (root != null) {
                if (root.has("overview")) video.overview = root.get("overview").asText();
                if (root.has("vote_average")) video.tmdbRating = root.get("vote_average").asDouble();
                if (root.has("imdb_id")) video.imdbId = root.get("imdb_id").asText();
                if (video.releaseYear == null && root.has("release_date")) {
                    String date = root.get("release_date").asText();
                    if (date.length() >= 4) video.releaseYear = Integer.parseInt(date.substring(0, 4));
                }
                if (root.has("poster_path") && !root.get("poster_path").isNull()) {
                    video.posterPath = TMDB_IMAGE_BASE + root.get("poster_path").asText();
                }
                if (root.has("backdrop_path") && !root.get("backdrop_path").isNull()) {
                    video.backdropPath = TMDB_IMAGE_ORIGINAL + root.get("backdrop_path").asText();
                }
                if (root.has("budget")) video.budget = root.get("budget").asLong();
                if (root.has("revenue")) video.revenue = root.get("revenue").asLong();
                if (root.has("status")) video.status = root.get("status").asText();
            }
        }
    }

    private void enrichEpisodeMetadata(Video video, String apiKey) throws IOException, InterruptedException {
        String showTmdbId = null;
        if (video.seriesTitle != null) {
            String searchUrl = String.format(TMDB_SEARCH_TV, apiKey, URLEncoder.encode(video.seriesTitle, StandardCharsets.UTF_8));
            JsonNode searchRoot = fetchJson(searchUrl);
            if (searchRoot != null && searchRoot.path("results").size() > 0) {
                showTmdbId = searchRoot.path("results").get(0).path("id").asText();
            }
        }

        if (showTmdbId != null) {
            String url = String.format(TMDB_EPISODE_DETAILS, showTmdbId, video.seasonNumber, video.episodeNumber, apiKey);
            JsonNode root = fetchJson(url);
            if (root != null) {
                if (root.has("name")) video.episodeTitle = root.get("name").asText();
                if (root.has("overview")) video.overview = root.get("overview").asText();
                if (root.has("vote_average")) video.tmdbRating = root.get("vote_average").asDouble();
                if (root.has("still_path") && !root.get("still_path").isNull()) {
                    video.posterPath = TMDB_IMAGE_BASE + root.get("still_path").asText();
                }
            }
        }
    }

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) return objectMapper.readTree(response.body());
        LOG.warn("Request failed: {} - {}", response.statusCode(), url);
        return null;
    }

    public Optional<String> fetchPosterUrl(String type, String title, Integer year) {
        if (title == null || title.isBlank()) return Optional.empty();
        
        String tmdbKey = getApiKey();
        if (tmdbKey != null && !tmdbKey.isBlank()) {
            try {
                String searchUrl = "movie".equalsIgnoreCase(type) ? 
                    String.format(TMDB_SEARCH_MOVIE, tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8)) :
                    String.format(TMDB_SEARCH_TV, tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8));
                
                JsonNode root = fetchJson(searchUrl);
                if (root != null && root.path("results").isArray() && root.path("results").size() > 0) {
                    String path = root.path("results").get(0).path("poster_path").asText();
                    if (path != null && !path.isEmpty() && !path.equals("null")) {
                        return Optional.of(TMDB_IMAGE_BASE + path);
                    }
                }
            } catch (Exception e) {
                LOG.warn("TMDb artwork fetch failed for {}: {}", title, e.getMessage());
            }
        }

        // Fallback to TVMaze (No key required)
        try {
            String url = String.format(TVMAZE_SEARCH, URLEncoder.encode(title, StandardCharsets.UTF_8));
            JsonNode root = fetchJson(url);
            if (root != null && root.has("image") && root.get("image").has("medium")) {
                return Optional.of(root.get("image").get("medium").asText());
            }
        } catch (Exception e) {
            LOG.warn("TVMaze artwork fetch failed for {}: {}", title, e.getMessage());
        }

        return Optional.empty();
    }
    
    /**
     * Fetch episode-specific image URL from TMDB
     */
    public Optional<String> fetchEpisodeImageUrl(String seriesTitle, int seasonNumber, int episodeNumber) {
        if (seriesTitle == null || seriesTitle.isBlank()) return Optional.empty();
        
        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            return Optional.empty();
        }
        
        try {
            String searchUrl = String.format(TMDB_SEARCH_TV, tmdbKey, URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8));
            JsonNode searchResult = fetchJson(searchUrl);
            
            if (searchResult == null || !searchResult.has("results") || searchResult.get("results").isEmpty()) {
                return Optional.empty();
            }
            
            String tvShowId = searchResult.get("results").get(0).get("id").asText();
            
            String episodeUrl = String.format(TMDB_EPISODE_DETAILS, tvShowId, seasonNumber, episodeNumber, tmdbKey);
            JsonNode episodeResult = fetchJson(episodeUrl);
            
            if (episodeResult != null && episodeResult.has("still_path")) {
                String stillPath = episodeResult.get("still_path").asText();
                if (stillPath != null && !stillPath.isEmpty()) {
                    return Optional.of(TMDB_IMAGE_BASE + stillPath);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch episode image for {} S{}E{}: {}", seriesTitle, seasonNumber, episodeNumber, e.getMessage());
        }
        
        return Optional.empty();
    }
}
