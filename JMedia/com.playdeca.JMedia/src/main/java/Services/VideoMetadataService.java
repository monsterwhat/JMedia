package Services;

import Models.Video.Series;
import Models.Settings.Settings;
import Models.Video.SubtitleTrack;
import Models.Video.Video;
import Utils.MediaPathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
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
import java.util.Set;
import Models.DTOs.VerificationField;
import Models.DTOs.VerificationPreview;
import jakarta.annotation.PreDestroy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@ApplicationScoped
public class VideoMetadataService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoMetadataService.class);

    /** Video IDs currently being text-enriched — dedups concurrent batched UI requests. */
    private static final Set<Long> ENRICHING_VIDEOS = ConcurrentHashMap.newKeySet();

    /** Cache of video IDs where IntroDB returned no data, to avoid repeated API calls. */
    private static final Map<Long, Long> INTRODB_NO_DATA_CACHE = new ConcurrentHashMap<>();
    private static final long INTRODB_NO_DATA_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours
    
    @Inject
    SettingsService settingsService;

    @Inject
    IMDbApiService imdbApiService;

    @Inject
    IntroDbService introDbService;

    @Inject
    VideoService videoService;

    @Inject
    FFprobeAudioService audioService;

    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;

    @Inject
    PgsOcrService pgsOcrService;

    @Inject
    ThumbnailService thumbnailService;

    @Inject
    VideoStoryboardService storyboardService;

    // TMDb
    private static final Pattern TMDB_V3_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final String TMDB_SEARCH_MOVIE = "https://api.themoviedb.org/3/search/movie?api_key=%s&query=%s";
    private static final String TMDB_SEARCH_TV = "https://api.themoviedb.org/3/search/tv?api_key=%s&query=%s";
    private static final String TMDB_MOVIE_DETAILS = "https://api.themoviedb.org/3/movie/%s?api_key=%s&append_to_response=credits,release_dates,images";
    private static final String TMDB_TV_DETAILS = "https://api.themoviedb.org/3/tv/%s?api_key=%s&append_to_response=credits,external_ids";
    private static final String TMDB_EPISODE_DETAILS = "https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?api_key=%s&append_to_response=credits,images";
    private static final String TMDB_IMAGE_W342 = "https://image.tmdb.org/t/p/w342";
    private static final String TMDB_IMAGE_W500 = "https://image.tmdb.org/t/p/w500";
    private static final String TMDB_IMAGE_W1280 = "https://image.tmdb.org/t/p/w1280";
    private static final String TMDB_IMAGE_ORIGINAL = "https://image.tmdb.org/t/p/original";
    private static final String TMDB_TV_IMAGES = "https://api.themoviedb.org/3/tv/%s/images?api_key=%s";
    private static final String TMDB_MOVIE_IMAGES = "https://api.themoviedb.org/3/movie/%s/images?api_key=%s";
    private static final String BUILT_IN_TMDB_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1NzlkZWYyZDY5ZWFlNDk4ZjJiOTI4MTgyNDdjM2ViMCIsInN1YiI6IjY2MjdmMGJlNjJmMzM1MDE0YmQ4NTFmMiIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.h3KpPvkiaz8uNz1bntAKqsPrxG_4UUWaY3kYME6N6m8";
    
    // OMDb
    private static final String OMDB_URL = "https://www.omdbapi.com/?apikey=%s&i=%s&plot=full";
    private static final String OMDB_SEARCH_URL = "https://www.omdbapi.com/?apikey=%s&t=%s&y=%s&plot=full";
    
    // Free IMDb Dev API
    private static final String IMDB_DEV_TITLE_URL = "https://api.imdbapi.dev/titles/%s";
    private static final String IMDB_DEV_SEARCH_URL = "https://api.imdbapi.dev/search/titles?query=%s";
    
    // TVMaze (Free, no key)
    private static final String TVMAZE_SEARCH = "https://api.tvmaze.com/search/shows?q=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache for show IDs to avoid rate limits during batch reloads
    private final Map<String, String> seriesImdbIdCache = new ConcurrentHashMap<>();
    


    // Dedicated 2-thread executor for background series text enrichment (NOT the shared managed pool — MAJOR-6)
    private final ExecutorService seriesEnrichmentExecutor = Executors.newFixedThreadPool(2);

    // In-memory cooldown map: seriesId → last enrichment attempt millis (retries after cooldown;
    // a never-evicting keyed set would permanently skip failures and deadlock the TMDB-disabled case — MAJOR-4)
    private final Map<Long, Long> seriesEnrichmentAttempts = new ConcurrentHashMap<>();

    @ConfigProperty(name = "metadata.enrichment.retry.cooldown.minutes", defaultValue = "30")
    int retryCooldownMinutes;

    // CDI self-injection: @ApplicationScoped client proxy so the @Transactional interceptor
    // applies on the worker thread (direct this-call would bypass it)
    @Inject
    VideoMetadataService self;

    @Inject
    @io.quarkus.cache.CacheName("cinema-home-cache")
    io.quarkus.cache.Cache cinemaHomeCache;
    
    /**
     * Multi-size media image URLs for a given title, fetched from TMDB.
     */
    public record MediaImages(
        Optional<String> posterPath,   // w342 poster for cards
        Optional<String> logoPath,     // w500 title/logo
        Optional<String> backdropPath, // w1280 backdrop
        Optional<String> heroPath,     // original resolution hero
        Optional<String> stillPath     // w500 episode still (episodes only)
    ) {}

    public static boolean isVideoEnriched(Video video) {
        if (video.tmdbId == null) return false;
        if (video.posterPath == null || video.backdropPath == null || video.logoPath == null || video.heroPath == null) {
            return false;
        }
        if ("episode".equalsIgnoreCase(video.type) && video.stillPath == null) {
            return false;
        }
        return true;
    }

    public static boolean isFullyEnriched(Video video) {
        if (video.tmdbId == null || video.tmdbId.isBlank()) return false;
        if (video.posterPath == null || video.backdropPath == null || video.logoPath == null || video.heroPath == null) return false;
        if ("episode".equalsIgnoreCase(video.type) && video.stillPath == null) return false;
        if (video.genres == null || video.genres.isEmpty()) return false;
        if (video.overview == null || video.overview.isBlank()) return false;
        if (video.releaseYear == null) return false;
        if ("movie".equalsIgnoreCase(video.type)) {
            if (video.directors == null || video.directors.isEmpty()) return false;
            if (video.cast == null || video.cast.isEmpty()) return false;
            if (video.productionCompanies == null || video.productionCompanies.isEmpty()) return false;
            if (video.productionCountries == null || video.productionCountries.isBlank()) return false;
        }
        if ("episode".equalsIgnoreCase(video.type)) {
            if (video.originalLanguage == null || video.originalLanguage.isBlank()) return false;
        }
        return true;
    }

    public boolean needsEnrichment(Video video) {
        if (video == null || !video.isActive) return false;
        if (video.enrichmentStatus == Video.EnrichmentStatus.ENRICHED) return false;
        if (video.enrichmentStatus == Video.EnrichmentStatus.NOT_FOUND) return false;
        if (video.titleManuallyEdited) return false;
        return true;
    }

    public void enrichVideoWithIntroData(Models.Video.Video video) {
        if (video == null || !"episode".equalsIgnoreCase(video.type)) return;

        // Fresh snapshot by id; only scalar fields are touched here. Deliberately NOT
        // transactional: the IntroDB HTTP lookup below must not hold database locks.
        final Models.Video.Video loadedVideo = Models.Video.Video.findById(video.id);
        if (loadedVideo == null) return;

        Long noDataExpiry = INTRODB_NO_DATA_CACHE.get(loadedVideo.id);
        if (noDataExpiry != null && System.currentTimeMillis() < noDataExpiry) {
            return;
        }

        // 1. Ensure we have Show IMDb ID (Required for IntroDB TV lookups)
        if (loadedVideo.showImdbId == null || loadedVideo.showImdbId.isBlank()) {
            loadedVideo.showImdbId = findSeriesImdbId(loadedVideo);
        }
        
        // 2. Fetch Intro/Outro/Recap data if we have the show ID
        if (loadedVideo.showImdbId != null && !loadedVideo.showImdbId.isBlank() && 
            loadedVideo.seasonNumber != null && loadedVideo.episodeNumber != null) {
            
            LOG.info("Refreshing IntroDB data for video {} (S{}E{}) using series ID: {}", loadedVideo.id, loadedVideo.seasonNumber, loadedVideo.episodeNumber, loadedVideo.showImdbId);
            
            introDbService.fetchAllMetadata(loadedVideo.showImdbId, loadedVideo.seasonNumber, loadedVideo.episodeNumber)
                .ifPresentOrElse(m -> {
                    INTRODB_NO_DATA_CACHE.remove(loadedVideo.id);
                    m.intro.ifPresent(ts -> {
                        loadedVideo.introStart = ts.start;
                        loadedVideo.introEnd = ts.end;
                        LOG.info("Updated intro for video {}: {}-{}", loadedVideo.id, ts.start, ts.end);
                    });
                    m.outro.ifPresent(ts -> {
                        loadedVideo.outroStart = ts.start;
                        loadedVideo.outroEnd = ts.end;
                        LOG.info("Updated outro for video {}: {}-{}", loadedVideo.id, ts.start, ts.end);
                    });
                    m.recap.ifPresent(ts -> {
                        loadedVideo.recapStart = ts.start;
                        loadedVideo.recapEnd = ts.end;
                        LOG.info("Updated recap for video {}: {}-{}", loadedVideo.id, ts.start, ts.end);
                    });
                }, () -> {
                    LOG.warn("IntroDB returned no data for series {} S{}E{}", loadedVideo.showImdbId, loadedVideo.seasonNumber, loadedVideo.episodeNumber);
                    INTRODB_NO_DATA_CACHE.put(loadedVideo.id, System.currentTimeMillis() + INTRODB_NO_DATA_TTL_MS);
                });
            
            self.persistEnrichedVideo(loadedVideo);
        } else {
            LOG.debug("Cannot fetch IntroDB data: Missing ShowImdbId ({}), Season ({}), or Episode ({})", 
                loadedVideo.showImdbId, loadedVideo.seasonNumber, loadedVideo.episodeNumber);
        }
    }

    /**
     * Helper to find a Series IMDb ID using the free IMDb Dev API.
     */
    private String findSeriesImdbId(Models.Video.Video video) {
        String searchTitle = "episode".equalsIgnoreCase(video.type) ? video.seriesTitle : video.title;
        if (searchTitle == null || searchTitle.isBlank()) return null;
        
        if (seriesImdbIdCache.containsKey(searchTitle)) {
            return seriesImdbIdCache.get(searchTitle);
        }
        
        Settings settings = settingsService.getOrCreateSettings();
        if (Boolean.TRUE.equals(settings.getImdbDevEnabled())) {
            try {
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
        if (key != null && !key.isBlank() && !isValidTmdbKey(key)) {
            LOG.warn("Invalid TMDB API key in settings ({}), falling through to env/built-in", key.substring(0, Math.min(key.length(), 10)));
            key = null;
        }
        if (key == null || key.isBlank()) {
            key = System.getenv("TMDB_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = BUILT_IN_TMDB_ACCESS_TOKEN;
        }
        return key;
    }

    private static boolean isValidTmdbKey(String key) {
        return key.startsWith("eyJ") || (key.length() >= 20 && key.length() <= 40 && TMDB_V3_KEY_PATTERN.matcher(key).matches());
    }

    private static boolean isBearerToken(String key) { return key != null && key.startsWith("eyJ"); }

    /**
     * Whether TMDB enrichment is available: enabled in settings AND an API key is resolvable.
     * MINOR-12: getApiKey() always returns a key (settings → env → built-in token), so in practice
     * this collapses to the settings check; the per-call settings check inside ensureSeriesTextMetadata stays in sync.
     */
    public boolean isTmdbConfigured() {
        if (!Boolean.TRUE.equals(settingsService.getOrCreateSettings().getTmdbEnabled())) {
            return false;
        }
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    private String getOmdbApiKey() {
        String key = settingsService.getOrCreateSettings().getOmdbApiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("OMDB_API_KEY");
        }
        return key;
    }

    public void fetchAndEnrichMetadata(Models.Video.Video video) {
        if (video == null) return;

        // Load a fully-initialized detached snapshot. Deliberately NOT transactional:
        // the TMDB/OMDb/IMDb HTTP calls, ffprobe runs and image downloads below used to
        // hold H2 row locks until commit and deadlocked concurrent workers.
        video = self.loadVideoForEnrichment(video.id);
        if (video == null) return;

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

            // 5. Audio Track Extraction (Always attempt if path is available)
            if (video.path != null && !video.path.isBlank()) {
                try {
                    // Build absolute path for ffprobe (video.path is stored relative to library)
                    String fullPath;
                    java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                    if (vPath.isAbsolute()) {
                        fullPath = video.path;
                    } else {
                        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                        fullPath = java.nio.file.Paths.get(videoLibraryPath, video.path).toString();
                    }

                    List<Models.Video.AudioTrack> audioTracks = audioService.extractAudioTracks(video, fullPath);
                    if (audioTracks != null && !audioTracks.isEmpty()) {
                        videoService.updateAudioTracks(video.id, audioTracks);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to extract audio tracks for {}: {}", video.title, e.getMessage());
                }
            }

            // 5b. Subtitle discovery + PGS OCR pre-warm (re-enrichment path)
            if (video.path != null && !video.path.isBlank()) {
                try {
                    java.nio.file.Path vPath = java.nio.file.Paths.get(video.path);
                    if (!vPath.isAbsolute()) {
                        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                        vPath = java.nio.file.Paths.get(videoLibraryPath, video.path);
                    }
                    if (java.nio.file.Files.exists(vPath)) {
                        List<SubtitleTrack> existing = SubtitleTrack.list("video.id", video.id);
                        if (existing == null || existing.isEmpty()) {
                            List<SubtitleTrack> discovered = subtitleMatcher.discoverSubtitleTracks(vPath, video);
                            if (discovered != null && !discovered.isEmpty()) {
                                videoService.updateSubtitleTracks(video.id, discovered);
                            }
                        }
                        pgsOcrService.preloadForVideo(video.id);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to discover/pre-warm subtitles for {}: {}", video.title, e.getMessage());
                }
            }

            // Final safety check for title - aggressively fix technical names and prioritize official episode titles
            if ("episode".equalsIgnoreCase(video.type)) {
                // If we found an official episode title, use it as the primary title
                if (video.episodeTitle != null && !video.episodeTitle.isBlank()) {
                    video.title = video.episodeTitle;
                } 
                // Otherwise, if the title is still technical noise, fall back to a clean S#E# format
                else if (video.title == null || video.title.isBlank() || video.title.startsWith(".") || 
                    video.title.toLowerCase().contains("720p") || video.title.toLowerCase().contains("1080p")) {
                    
                    video.title = video.seriesTitle + " - S" + video.seasonNumber + "E" + video.episodeNumber;
                }
            }

            // Flush enriched fields in a short transaction
            self.persistEnrichedVideo(video);

            // 7. Download TMDB image URLs to local files (poster, logo, backdrop, hero)
            try {
                String imgType = video.type != null ? video.type : "movie";
                String imgTitle;
                if ("episode".equalsIgnoreCase(imgType) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                    imgTitle = video.seriesTitle;
                } else {
                    imgTitle = video.title != null ? video.title : video.seriesTitle;
                }
                if (imgTitle != null && (video.posterPath != null || video.logoPath != null || video.backdropPath != null || video.heroPath != null)) {
                    VideoMetadataService.MediaImages tmdbImages = fetchMediaImages(imgType, imgTitle, video.releaseYear,
                        video.seriesTitle, video.seasonNumber, video.episodeNumber, video.tmdbId);
                    ThumbnailService.MediaImages localImages = new ThumbnailService.MediaImages(
                        tmdbImages.posterPath(), tmdbImages.logoPath(),
                        tmdbImages.backdropPath(), tmdbImages.heroPath(),
                        tmdbImages.stillPath());
                    ThumbnailService.MediaImagePaths paths = thumbnailService.downloadMediaImages(video.id, localImages);
                    if (paths.posterPath() != null) video.posterPath = paths.posterPath();
                    if (paths.logoPath() != null) video.logoPath = paths.logoPath();
                    if (paths.backdropPath() != null) video.backdropPath = paths.backdropPath();
                    if (paths.heroPath() != null) video.heroPath = paths.heroPath();
                    if (paths.stillPath() != null) video.stillPath = paths.stillPath();
                    self.persistEnrichedVideo(video);
                }
            } catch (Exception imgEx) {
                LOG.warn("[Enrich] Failed to download local image files for {}: {}", video.id, imgEx.getMessage());
            }

            // 6. If we now have external IDs, rename thumbnail/storyboard assets to canonical naming
            if (MediaPathResolver.hasExternalId(video)) {
                try {
                    thumbnailService.renameForExternalIds(video.id);
                    storyboardService.renameForExternalIds(video.id);
                } catch (Exception renameEx) {
                    LOG.warn("Failed to rename assets after enrichment for {}: {}", video.id, renameEx.getMessage());
                }
            }

            if (video.tmdbId != null && !video.tmdbId.isBlank()) {
                video.enrichmentStatus = Video.EnrichmentStatus.ENRICHED;
            } else {
                video.enrichmentStatus = Video.EnrichmentStatus.NOT_FOUND;
            }
            self.persistEnrichedVideo(video);

        } catch (Exception e) {
            if (e instanceof jakarta.persistence.OptimisticLockException
                    || (e.getCause() != null && e.getCause() instanceof jakarta.persistence.OptimisticLockException)
                    || (e.getCause() != null && e.getCause().getCause() != null
                        && e.getCause().getCause() instanceof jakarta.persistence.OptimisticLockException)) {
                LOG.warn("Metadata enrichment skipped for '{}' - entity was likely deleted during database reset", video.title);
            } else {
                LOG.error("DEBUG: Metadata enrichment FAILED for {}: {}", video.title, e.getMessage(), e);
            }
            video.enrichmentStatus = Video.EnrichmentStatus.FAILED;
            try {
                self.persistEnrichmentStatus(video.id, Video.EnrichmentStatus.FAILED);
            } catch (Exception statusEx) {
                LOG.warn("Failed to persist FAILED enrichment status for video {}: {}", video.id, statusEx.getMessage());
            }
        }
    }

    /**
     * Preview what metadata enrichment would change WITHOUT saving anything.
     * Runs the same TMDB + IMDb lookups as fetchAndEnrichMetadata but returns
     * a diff of current vs. fetched values. The original Video entity is NOT modified.
     *
     * @param video     the video to preview enrichment for
     * @param titleBlind if true, skip using the extracted title for search; use only showName+S/E
     * @return VerificationPreview with current vs fetched field pairs
     */
    public VerificationPreview previewEnrichment(Video video, boolean titleBlind) {
        VerificationPreview preview = new VerificationPreview();
        preview.videoId = video.id;
        preview.filename = video.filename;
        preview.type = video.type;

        preview.title = new VerificationField<>(video.title, null);
        preview.seriesTitle = new VerificationField<>(video.seriesTitle, null);
        preview.episodeTitle = new VerificationField<>(video.episodeTitle, null);
        preview.seasonNumber = new VerificationField<>(video.seasonNumber, null);
        preview.episodeNumber = new VerificationField<>(video.episodeNumber, null);
        preview.imdbId = new VerificationField<>(video.imdbId, null);
        preview.showImdbId = new VerificationField<>(video.showImdbId, null);
        preview.tmdbId = new VerificationField<>(video.tmdbId, null);

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            LOG.warn("No TMDB API key available, preview will be limited");
        }

        try {
            if ("episode".equalsIgnoreCase(video.type)) {
                previewEpisode(video, preview, tmdbKey);
            } else if ("movie".equalsIgnoreCase(video.type)) {
                previewMovie(video, preview, tmdbKey);
            }
        } catch (Exception e) {
            LOG.warn("Preview enrichment failed for video {}: {}", video.id, e.getMessage());
        }

        return preview;
    }

    private void previewEpisode(Video video, VerificationPreview preview, String tmdbKey) {
        String seriesName = video.seriesTitle;
        Integer season = video.seasonNumber;
        Integer ep = video.episodeNumber;
        if (seriesName == null || seriesName.isBlank() || season == null || ep == null) return;

        // 1. TMDB: search show → episode details
        Settings settings = settingsService.getOrCreateSettings();
        if (Boolean.TRUE.equals(settings.getTmdbEnabled()) && tmdbKey != null && !tmdbKey.isBlank()) {
            try {
                Map<String, String> authHeaders = isBearerToken(tmdbKey) ? Map.of("Authorization", "Bearer " + tmdbKey) : null;
                String searchUrl;
                String yearSuffix = video.releaseYear != null ? "&first_air_date_year=" + video.releaseYear : "";
                if (isBearerToken(tmdbKey)) {
                    searchUrl = String.format("https://api.themoviedb.org/3/search/tv?query=%s%s",
                            URLEncoder.encode(seriesName, StandardCharsets.UTF_8), yearSuffix);
                } else {
                    searchUrl = String.format(TMDB_SEARCH_TV + "%s", tmdbKey,
                            URLEncoder.encode(seriesName, StandardCharsets.UTF_8), yearSuffix);
                }
                JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
                if (searchRoot != null && searchRoot.path("results").isArray() && searchRoot.path("results").size() > 0) {
                    String showTmdbId = searchRoot.path("results").get(0).path("id").asText();
                    if (showTmdbId != null && !showTmdbId.isBlank()) {
                        preview.tmdbId.fetched = showTmdbId;

                        String epUrl;
                        if (isBearerToken(tmdbKey)) {
                            epUrl = String.format("https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?append_to_response=credits,images", showTmdbId, season, ep);
                        } else {
                            epUrl = String.format(TMDB_EPISODE_DETAILS, showTmdbId, season, ep, tmdbKey);
                        }
                        JsonNode epRoot = fetchJson(epUrl, authHeaders);
                        if (epRoot != null) {
                            if (epRoot.has("name")) {
                                preview.episodeTitle.fetched = epRoot.get("name").asText();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("TMDB preview failed for {} S{}E{}: {}", seriesName, season, ep, e.getMessage());
            }
        }

        // 2. IMDb Dev: find show IMDb ID → fetch episode list
        if (Boolean.TRUE.equals(settings.getImdbDevEnabled())) {
        try {
            String showImdbId = findSeriesImdbId(video);
            if (showImdbId != null && !showImdbId.isBlank()) {
                preview.showImdbId.fetched = showImdbId;

                String epListUrl = String.format(IMDB_DEV_TITLE_URL + "/episodes", showImdbId);
                JsonNode epRoot = fetchJson(epListUrl);
                if (epRoot != null && epRoot.path("episodes").isArray()) {
                    for (JsonNode epNode : epRoot.path("episodes")) {
                        String epSeason = epNode.path("season").asText();
                        int epNum = epNode.path("episodeNumber").asInt();
                        if (String.valueOf(season).equals(epSeason) && epNum == ep) {
                            String epTitle = epNode.path("title").asText();
                            if (epTitle != null && !epTitle.isBlank()) {
                                preview.episodeTitle.fetched = epTitle;
                            }
                            String epId = epNode.path("id").asText();
                            if (epId != null && !epId.isBlank()) {
                                preview.imdbId.fetched = epId;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("IMDb Dev preview failed for {}: {}", video.id, e.getMessage());
        }
        }

    }

    private void previewMovie(Video video, VerificationPreview preview, String tmdbKey) {
        Settings settings = settingsService.getOrCreateSettings();
        if (Boolean.TRUE.equals(settings.getTmdbEnabled()) && tmdbKey != null && !tmdbKey.isBlank()) {
            try {
                String query = URLEncoder.encode(video.title != null ? video.title : video.filename, StandardCharsets.UTF_8);
                Map<String, String> authHeaders = isBearerToken(tmdbKey) ? Map.of("Authorization", "Bearer " + tmdbKey) : null;
                String searchUrl;
                String yearSuffix = video.releaseYear != null ? "&year=" + video.releaseYear : "";
                if (isBearerToken(tmdbKey)) {
                    searchUrl = String.format("https://api.themoviedb.org/3/search/movie?query=%s%s", query, yearSuffix);
                } else {
                    searchUrl = String.format(TMDB_SEARCH_MOVIE + "%s", tmdbKey, query, yearSuffix);
                }
                JsonNode root = fetchJson(searchUrl, authHeaders);
                if (root != null && root.path("results").isArray() && root.path("results").size() > 0) {
                    JsonNode first = root.path("results").get(0);
                    String foundTmdbId = first.path("id").asText();
                    if (foundTmdbId != null && !foundTmdbId.isBlank()) {
                        preview.tmdbId.fetched = foundTmdbId;

                        String detailUrl;
                        if (isBearerToken(tmdbKey)) {
                            detailUrl = String.format("https://api.themoviedb.org/3/movie/%s?append_to_response=credits,release_dates,images", foundTmdbId);
                        } else {
                            detailUrl = String.format(TMDB_MOVIE_DETAILS, foundTmdbId, tmdbKey);
                        }
                        JsonNode detailRoot = fetchJson(detailUrl, authHeaders);
                        if (detailRoot != null) {
                            if (detailRoot.has("imdb_id")) {
                                preview.imdbId.fetched = detailRoot.get("imdb_id").asText();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("TMDB movie preview failed for {}: {}", video.id, e.getMessage());
            }
        }

    }

    private void enrichWithImdbDevMetadata(Models.Video.Video video) {
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getImdbDevEnabled())) {
            LOG.info("IMDb Dev disabled in settings, skipping IMDb Dev enrichment");
            return;
        }
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
            if (score > 0) video.metacriticRating = (double) score;
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

    private void enrichWithOmdbMetadata(Models.Video.Video video, String apiKey) {
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getOmdbEnabled())) {
            LOG.info("OMDb disabled in settings, skipping OMDb enrichment");
            return;
        }
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
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            LOG.info("TMDB disabled in settings, skipping movie metadata enrichment");
            return;
        }
        if (video.tmdbId == null) {
            String query = URLEncoder.encode(video.title, StandardCharsets.UTF_8);
            String yearSuffix = video.releaseYear != null ? "&year=" + video.releaseYear : "";
            Map<String, String> authHeaders = null;
            String url;
            if (isBearerToken(apiKey)) {
                url = String.format("https://api.themoviedb.org/3/search/movie?query=%s%s", query, yearSuffix);
                authHeaders = Map.of("Authorization", "Bearer " + apiKey);
            } else {
                url = String.format(TMDB_SEARCH_MOVIE + "%s", apiKey, query, yearSuffix);
            }
            JsonNode root = fetchJson(url, authHeaders);
            if (root != null && root.path("results").size() > 0) {
                video.tmdbId = root.path("results").get(0).path("id").asText();
                LOG.info("[EnrichMovie] TMDb search found match for '{}': tmdbId={}", video.title, video.tmdbId);
            } else if (root == null) {
                LOG.warn("[EnrichMovie] TMDb search request failed for: {}", video.title);
            }
        }

        if (video.tmdbId != null) {
            Map<String, String> authHeaders = isBearerToken(apiKey) ? Map.of("Authorization", "Bearer " + apiKey) : null;
            String url;
            if (isBearerToken(apiKey)) {
                url = String.format("https://api.themoviedb.org/3/movie/%s?append_to_response=credits,release_dates,images", video.tmdbId);
            } else {
                url = String.format(TMDB_MOVIE_DETAILS, video.tmdbId, apiKey);
            }
            JsonNode root = fetchJson(url, authHeaders);
            if (root == null) {
                LOG.warn("[EnrichMovie] Failed to fetch movie details for tmdbId={}", video.tmdbId);
            }
            if (root != null) {
                if (root.has("overview")) video.overview = root.get("overview").asText();
                if (root.has("vote_average")) video.tmdbRating = root.get("vote_average").asDouble();
                if (root.has("imdb_id")) {
                    video.imdbId = root.get("imdb_id").asText();
                }
                if (video.releaseYear == null && root.has("release_date")) {
                    String date = root.get("release_date").asText();
                    if (date.length() >= 4) video.releaseYear = Integer.parseInt(date.substring(0, 4));
                }
                if (root.has("poster_path") && !root.get("poster_path").isNull()) {
                    video.posterPath = TMDB_IMAGE_W500 + root.get("poster_path").asText();
                }
                if (root.has("backdrop_path") && !root.get("backdrop_path").isNull()) {
                    video.backdropPath = TMDB_IMAGE_W1280 + root.get("backdrop_path").asText();
                    video.fanartPath = video.backdropPath;
                }
                // Extract logo from images (already fetched via append_to_response=images)
                if (root.has("images") && root.get("images").has("logos")) {
                    JsonNode logos = root.get("images").get("logos");
                    if (logos.isArray() && logos.size() > 0) {
                        String logoFilePath = null;
                        for (JsonNode logo : logos) {
                            if ("en".equals(logo.path("iso_639_1").asText())) {
                                logoFilePath = logo.path("file_path").asText();
                                break;
                            }
                        }
                        if (logoFilePath == null) logoFilePath = logos.get(0).path("file_path").asText();
                        if (logoFilePath != null && video.logoPath == null) {
                            video.logoPath = TMDB_IMAGE_W500 + logoFilePath;
                            LOG.info("[EnrichMovie] Set logo path: {}", video.logoPath);
                        } else {
                            LOG.info("[EnrichMovie] No usable logo found in images");
                        }
                    }
                }
                if (root.has("budget")) video.budget = root.get("budget").asLong();
                if (root.has("revenue")) video.revenue = root.get("revenue").asLong();
                if (root.has("status")) video.status = root.get("status").asText();
                if (root.has("tagline")) video.tagline = root.get("tagline").asText();
                if (root.has("vote_count")) video.voteCount = root.get("vote_count").asInt();
                if (root.has("popularity")) video.popularityScore = root.get("popularity").asDouble();
                if (root.has("runtime")) video.runtimeMins = root.get("runtime").asInt();
                if (root.has("original_language")) video.originalLanguage = root.get("original_language").asText();
                // Additional metadata: genres, credits, production info
                if (video.genres == null || video.genres.isEmpty()) {
                    if (root.has("genres") && root.get("genres").isArray()) {
                        video.genres = new ArrayList<>();
                        for (JsonNode g : root.path("genres")) {
                            if (g.has("name")) video.genres.add(g.get("name").asText());
                        }
                    }
                }
                if (video.productionCompanies == null || video.productionCompanies.isEmpty()) {
                    if (root.has("production_companies") && root.get("production_companies").isArray()) {
                        video.productionCompanies = new ArrayList<>();
                        for (JsonNode c : root.path("production_companies")) {
                            if (c.has("name")) video.productionCompanies.add(c.get("name").asText());
                        }
                    }
                }
                if (video.productionCountries == null) {
                    if (root.has("production_countries") && root.get("production_countries").isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode pc : root.path("production_countries")) {
                            if (pc.has("iso_3166_1")) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(pc.get("iso_3166_1").asText());
                            }
                        }
                        if (sb.length() > 0) video.productionCountries = sb.toString();
                    }
                }
                if (video.directors == null || video.directors.isEmpty()) {
                    if (root.has("credits") && root.get("credits").has("crew") && root.get("credits").get("crew").isArray()) {
                        video.directors = new ArrayList<>();
                        for (JsonNode crew : root.get("credits").get("crew")) {
                            if ("Director".equals(crew.path("job").asText())) {
                                video.directors.add(crew.path("name").asText());
                            }
                        }
                    }
                }
                if (video.cast == null || video.cast.isEmpty()) {
                    if (root.has("credits") && root.get("credits").has("cast") && root.get("credits").get("cast").isArray()) {
                        video.cast = new ArrayList<>();
                        for (JsonNode actor : root.get("credits").get("cast")) {
                            String name = actor.path("name").asText();
                            if (name != null && !name.isBlank()) {
                                video.cast.add(name);
                                if (video.cast.size() >= 15) break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void enrichEpisodeMetadata(Video video, String apiKey) throws IOException, InterruptedException {
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            LOG.info("TMDB disabled in settings, skipping episode metadata enrichment");
            return;
        }
        String showTmdbId = null;
        if (video.seriesTitle != null) {
            Map<String, String> authHeaders = null;
            String searchUrl;
            String yearSuffix = video.releaseYear != null ? "&first_air_date_year=" + video.releaseYear : "";
            if (isBearerToken(apiKey)) {
                searchUrl = String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", URLEncoder.encode(video.seriesTitle, StandardCharsets.UTF_8), yearSuffix);
                authHeaders = Map.of("Authorization", "Bearer " + apiKey);
            } else {
                searchUrl = String.format(TMDB_SEARCH_TV + "%s", apiKey, URLEncoder.encode(video.seriesTitle, StandardCharsets.UTF_8), yearSuffix);
            }
            JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
            if (searchRoot != null && searchRoot.path("results").size() > 0) {
                showTmdbId = searchRoot.path("results").get(0).path("id").asText();
            } else if (searchRoot == null) {
                LOG.warn("[EnrichEpisode] TMDb search request failed for: {}", video.seriesTitle);
            }
        }

        if (showTmdbId != null) {
            video.tmdbId = showTmdbId;
            Map<String, String> authHeaders = isBearerToken(apiKey) ? Map.of("Authorization", "Bearer " + apiKey) : null;
            String url;
            if (isBearerToken(apiKey)) {
                url = String.format("https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?append_to_response=credits,images", showTmdbId, video.seasonNumber, video.episodeNumber);
            } else {
                url = String.format(TMDB_EPISODE_DETAILS, showTmdbId, video.seasonNumber, video.episodeNumber, apiKey);
            }
            JsonNode root = fetchJson(url, authHeaders);
            if (root == null) {
                LOG.debug("[EnrichEpisode] Failed to fetch episode details for showTmdbId={}, S{}E{}", showTmdbId, video.seasonNumber, video.episodeNumber);
            }
            if (root != null) {
                if (root.has("name")) video.episodeTitle = root.get("name").asText();
                if (root.has("overview")) video.overview = root.get("overview").asText();
                if (root.has("vote_average")) video.tmdbRating = root.get("vote_average").asDouble();
                if (root.has("vote_count")) video.voteCount = root.get("vote_count").asInt();
                if (root.has("still_path") && !root.get("still_path").isNull()) {
                    video.posterPath = TMDB_IMAGE_W500 + root.get("still_path").asText();
                    video.stillPath = TMDB_IMAGE_W500 + root.get("still_path").asText();
                }
            }
            // Fetch show-level backdrop and logo
            String showUrl;
            if (isBearerToken(apiKey)) {
                showUrl = String.format("https://api.themoviedb.org/3/tv/%s?append_to_response=credits,external_ids", showTmdbId);
            } else {
                showUrl = String.format(TMDB_TV_DETAILS, showTmdbId, apiKey);
            }
            JsonNode showRoot = fetchJson(showUrl, authHeaders);
            if (showRoot == null) {
                LOG.warn("[EnrichEpisode] Failed to fetch show details for showTmdbId={}", showTmdbId);
            }
            if (showRoot != null) {
                if (showRoot.has("backdrop_path") && !showRoot.get("backdrop_path").isNull()) {
                    video.backdropPath = TMDB_IMAGE_W1280 + showRoot.get("backdrop_path").asText();
                    video.heroPath = TMDB_IMAGE_ORIGINAL + showRoot.get("backdrop_path").asText();
                    video.fanartPath = video.backdropPath;
                }
                if (showRoot.has("networks") && showRoot.get("networks").isArray()) {
                    List<String> networkNames = new ArrayList<>();
                    for (JsonNode net : showRoot.get("networks")) {
                        if (net.has("name")) {
                            networkNames.add(net.get("name").asText());
                        }
                    }
                    if (!networkNames.isEmpty()) video.networks = networkNames;
                }
                if (showRoot.has("vote_count")) video.voteCount = showRoot.get("vote_count").asInt();
                if (showRoot.has("popularity")) video.popularityScore = showRoot.get("popularity").asDouble();
                if (showRoot.has("original_language")) video.originalLanguage = showRoot.get("original_language").asText();

                if (video.series != null) {
                    enrichSeriesFromShowRoot(video.series, showRoot, showTmdbId);
                }
            }
            // Get show logo
            {
                String imagesUrl;
                if (isBearerToken(apiKey)) {
                    imagesUrl = String.format("https://api.themoviedb.org/3/tv/%s/images", showTmdbId);
                } else {
                    imagesUrl = String.format(TMDB_TV_IMAGES, showTmdbId, apiKey);
                }
                JsonNode imagesRoot = fetchJson(imagesUrl, authHeaders);
                if (imagesRoot == null) {
                    LOG.warn("[EnrichEpisode] Failed to fetch show images for showTmdbId={}", showTmdbId);
                }
                if (imagesRoot != null && imagesRoot.has("logos")) {
                    JsonNode logos = imagesRoot.get("logos");
                    if (logos.isArray() && logos.size() > 0) {
                        String logoFilePath = null;
                        for (JsonNode logo : logos) {
                            if ("en".equals(logo.path("iso_639_1").asText())) {
                                logoFilePath = logo.path("file_path").asText();
                                break;
                            }
                        }
                        if (logoFilePath == null) logoFilePath = logos.get(0).path("file_path").asText();
                        if (logoFilePath != null) {
                            video.logoPath = TMDB_IMAGE_W500 + logoFilePath;
                        } else {
                            LOG.info("[EnrichEpisode] No usable logo found in show images");
                        }
                    } else {
                        LOG.info("[EnrichEpisode] No logos available in show images response");
                    }
                }
            }
            // Additional show/episode metadata
            if (root != null) {
                if (video.releaseDate == null && root.has("air_date") && !root.get("air_date").isNull()) {
                    video.releaseDate = root.get("air_date").asText();
                }
                if (video.runtimeMins == null && root.has("runtime") && !root.get("runtime").isNull()) {
                    video.runtimeMins = root.get("runtime").asInt();
                }
            }
            if (video.releaseYear == null && showRoot.has("first_air_date") && !showRoot.get("first_air_date").isNull()) {
                String d = showRoot.get("first_air_date").asText();
                if (d != null && d.length() >= 4) {
                    try { video.releaseYear = Integer.parseInt(d.substring(0, 4)); } catch (NumberFormatException ignored) {}
                }
            }
            if (video.genres == null || video.genres.isEmpty()) {
                if (showRoot.has("genres") && showRoot.get("genres").isArray()) {
                    List<String> genres = new ArrayList<>();
                    for (JsonNode g : showRoot.path("genres")) {
                        if (g.has("name")) genres.add(g.get("name").asText());
                    }
                    if (!genres.isEmpty()) video.genres = genres;
                }
            }
            if (video.originalLanguage == null && showRoot.has("original_language") && !showRoot.get("original_language").isNull()) {
                video.originalLanguage = showRoot.get("original_language").asText();
            }
            if (video.productionCountries == null) {
                if (showRoot.has("production_countries") && showRoot.get("production_countries").isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode pc : showRoot.path("production_countries")) {
                        if (pc.has("iso_3166_1")) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(pc.get("iso_3166_1").asText());
                        }
                    }
                    if (sb.length() > 0) video.productionCountries = sb.toString();
                }
            }
        }
    }

    private void enrichSeriesFromShowRoot(Series series, JsonNode showRoot, String showTmdbId) {
        if (series.tmdbId == null) {
            try { series.tmdbId = Integer.parseInt(showTmdbId); } catch (NumberFormatException ignored) {}
        }
        if (series.overview == null || series.overview.isBlank()) {
            if (showRoot.has("overview") && !showRoot.get("overview").isNull()) {
                series.overview = showRoot.get("overview").asText();
            }
        }
        if (series.genres == null || series.genres.isEmpty()) {
            if (showRoot.has("genres") && showRoot.get("genres").isArray()) {
                List<String> genres = new ArrayList<>();
                for (JsonNode g : showRoot.path("genres")) {
                    if (g.has("name")) genres.add(g.get("name").asText());
                }
                if (!genres.isEmpty()) series.genres = genres;
            }
        }
        if (series.networks == null || series.networks.isEmpty()) {
            if (showRoot.has("networks") && showRoot.get("networks").isArray()) {
                List<String> networks = new ArrayList<>();
                for (JsonNode n : showRoot.path("networks")) {
                    if (n.has("name")) networks.add(n.get("name").asText());
                }
                if (!networks.isEmpty()) series.networks = networks;
            }
        }
        if (series.tmdbRating == null && showRoot.has("vote_average") && !showRoot.get("vote_average").isNull()) {
            series.tmdbRating = showRoot.get("vote_average").asDouble();
        }
        if (series.voteCount == null && showRoot.has("vote_count")) {
            series.voteCount = showRoot.get("vote_count").asInt();
        }
        if (series.popularityScore == null && showRoot.has("popularity") && !showRoot.get("popularity").isNull()) {
            series.popularityScore = showRoot.get("popularity").asDouble();
        }
        if (series.status == null || series.status.isBlank()) {
            if (showRoot.has("status") && !showRoot.get("status").isNull()) {
                series.status = showRoot.get("status").asText();
            }
        }
        if (series.tagline == null || series.tagline.isBlank()) {
            if (showRoot.has("tagline") && !showRoot.get("tagline").isNull()) {
                series.tagline = showRoot.get("tagline").asText();
            }
        }
        if (series.originalLanguage == null || series.originalLanguage.isBlank()) {
            if (showRoot.has("original_language") && !showRoot.get("original_language").isNull()) {
                series.originalLanguage = showRoot.get("original_language").asText();
            }
        }
        if (series.productionCountries == null) {
            if (showRoot.has("production_countries") && showRoot.get("production_countries").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode pc : showRoot.path("production_countries")) {
                    if (pc.has("iso_3166_1")) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(pc.get("iso_3166_1").asText());
                    }
                }
                if (sb.length() > 0) series.productionCountries = sb.toString();
            }
        }
        if (series.releaseYear == null && showRoot.has("first_air_date") && !showRoot.get("first_air_date").isNull()) {
            String d = showRoot.get("first_air_date").asText();
            if (d != null && d.length() >= 4) {
                try { series.releaseYear = Integer.parseInt(d.substring(0, 4)); } catch (NumberFormatException ignored) {}
            }
        }
        // Series arrives as an initialized detached snapshot (see loadVideoForEnrichment).
        // Do NOT persist/merge here — the caller flushes it via persistEnrichedVideo(), which
        // merges the Series separately in its own short transaction.
    }

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
        return fetchJson(url, null);
    }

    private JsonNode fetchJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(30));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Transient provider outage (DNS/connect/reset/timeout) is expected for
            // best-effort enrichment providers — return null like a non-200 response.
            LOG.debug("Network error fetching {} ({})", url, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (response.statusCode() == 200) return objectMapper.readTree(response.body());
        if (response.statusCode() == 404) {
            LOG.debug("Resource not found (404): {}", url);
        } else {
            LOG.warn("Request failed: {} - {}", response.statusCode(), url);
        }
        return null;
    }

    public Optional<String> fetchPosterUrl(String type, String title, Integer year) {
        if (title == null || title.isBlank()) return Optional.empty();
        
        Settings settings = settingsService.getOrCreateSettings();
        if (Boolean.TRUE.equals(settings.getTmdbEnabled())) {
        String tmdbKey = getApiKey();
        if (tmdbKey != null && !tmdbKey.isBlank()) {
            try {
                Map<String, String> authHeaders = null;
                String searchUrl;
                String yearSuffix = "";
                if (year != null) {
                    yearSuffix = "movie".equalsIgnoreCase(type) ? "&year=" + year : "&first_air_date_year=" + year;
                }
                if (isBearerToken(tmdbKey)) {
                    searchUrl = "movie".equalsIgnoreCase(type) ?
                        String.format("https://api.themoviedb.org/3/search/movie?query=%s%s", URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix) :
                        String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix);
                    authHeaders = Map.of("Authorization", "Bearer " + tmdbKey);
                } else {
                    searchUrl = "movie".equalsIgnoreCase(type) ?
                        String.format(TMDB_SEARCH_MOVIE + "%s", tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix) :
                        String.format(TMDB_SEARCH_TV + "%s", tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix);
                }
                
                JsonNode root = fetchJson(searchUrl, authHeaders);
                if (root != null && root.path("results").isArray() && root.path("results").size() > 0) {
                    String path = root.path("results").get(0).path("poster_path").asText();
                    if (path != null && !path.isEmpty() && !path.equals("null")) {
                        return Optional.of(TMDB_IMAGE_W500 + path);
                    }
                }
            } catch (Exception e) {
                LOG.warn("TMDb artwork fetch failed for {}: {}", title, e.getMessage());
            }
        }
        }

        // Fallback to TVMaze (No key required)
        if (Boolean.TRUE.equals(settings.getTvmazeEnabled())) {
        try {
            String url = String.format(TVMAZE_SEARCH, URLEncoder.encode(title, StandardCharsets.UTF_8));
            JsonNode root = fetchJson(url);
            if (root != null && root.isArray()) {
                for (JsonNode result : root) {
                    JsonNode show = result.path("show");
                    if (show.has("image") && show.get("image").has("medium")) {
                        return Optional.of(show.get("image").get("medium").asText());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("TVMaze artwork fetch failed for {}: {}", title, e.getMessage());
        }
        }

        return Optional.empty();
    }

    /**
     * Fetch all TMDB image sizes (poster, logo, backdrop, hero) for a given title.
     * Reuses the TMDB search endpoint to find the first matching result, then
     * builds URLs at w342 (poster cards), w500 (logo/title), w1280 (backdrop),
     * and original (hero) resolutions. For episodes, also fetches the still image.
     *
     * @param type         "movie" or "tv"
     * @param title        search title
     * @param year         optional release year for disambiguation
     * @param seriesTitle  series title for episode still lookup (null for movies)
     * @param seasonNumber season number for episode still lookup (null for movies)
     * @param episodeNumber episode number for episode still lookup (null for movies)
     * @return MediaImages with Optional-wrapped URLs (empty if not found or TMDB disabled)
     */
    public MediaImages fetchMediaImages(String type, String title, Integer year,
                                         String seriesTitle, Integer seasonNumber, Integer episodeNumber) {
        if (title == null || title.isBlank()) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        try {
            String searchUrl;
            Map<String, String> authHeaders = null;
            String yearSuffix = "";
            if (year != null) {
                yearSuffix = "movie".equalsIgnoreCase(type) ? "&year=" + year : "&first_air_date_year=" + year;
            }
            if (isBearerToken(tmdbKey)) {
                searchUrl = "movie".equalsIgnoreCase(type) ?
                    String.format("https://api.themoviedb.org/3/search/movie?query=%s%s", URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix) :
                    String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix);
                authHeaders = Map.of("Authorization", "Bearer " + tmdbKey);
            } else {
                searchUrl = "movie".equalsIgnoreCase(type) ?
                    String.format(TMDB_SEARCH_MOVIE + "%s", tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix) :
                    String.format(TMDB_SEARCH_TV + "%s", tmdbKey, URLEncoder.encode(title, StandardCharsets.UTF_8), yearSuffix);
            }

            JsonNode root = fetchJson(searchUrl, authHeaders);
            if (root == null || !root.path("results").isArray() || root.path("results").isEmpty()) {
                return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            }

            JsonNode first = root.path("results").get(0);

            Optional<String> poster = Optional.empty();
            Optional<String> logo = Optional.empty();
            Optional<String> backdrop = Optional.empty();
            Optional<String> hero = Optional.empty();

            String posterPath = first.path("poster_path").asText(null);
            if (posterPath != null && !posterPath.isEmpty() && !posterPath.equals("null")) {
                poster = Optional.of(TMDB_IMAGE_W342 + posterPath);
            }

            String backdropPath = first.path("backdrop_path").asText(null);
            if (backdropPath != null && !backdropPath.isEmpty() && !backdropPath.equals("null")) {
                backdrop = Optional.of(TMDB_IMAGE_W1280 + backdropPath);
                hero = Optional.of(TMDB_IMAGE_ORIGINAL + backdropPath);
            }

            // TVMaze poster fallback when TMDB poster is empty
            if (poster.isEmpty() && Boolean.TRUE.equals(settings.getTvmazeEnabled())) {
                try {
                    String tvmazeUrl = String.format(TVMAZE_SEARCH, URLEncoder.encode(title, StandardCharsets.UTF_8));
                    JsonNode tvmazeRoot = fetchJson(tvmazeUrl, null);
                    if (tvmazeRoot != null && tvmazeRoot.isArray()) {
                        for (JsonNode result : tvmazeRoot) {
                            JsonNode show = result.path("show");
                            if (show.has("image") && show.get("image").has("medium")) {
                                poster = Optional.of(show.get("image").get("medium").asText());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("TVMaze poster fallback failed for {}: {}", title, e.getMessage());
                }
            }

            try {
                String tmdbId = first.path("id").asText(null);
                if (tmdbId != null && !tmdbId.isEmpty() && !tmdbId.equals("null")) {
                    String imagesUrl;
                    if (isBearerToken(tmdbKey)) {
                        imagesUrl = "movie".equalsIgnoreCase(type) ?
                            String.format("https://api.themoviedb.org/3/movie/%s/images", tmdbId) :
                            String.format("https://api.themoviedb.org/3/tv/%s/images", tmdbId);
                    } else {
                        imagesUrl = "movie".equalsIgnoreCase(type) ?
                            String.format(TMDB_MOVIE_IMAGES, tmdbId, tmdbKey) :
                            String.format(TMDB_TV_IMAGES, tmdbId, tmdbKey);
                    }
                    JsonNode imagesRoot = fetchJson(imagesUrl, authHeaders);
                    if (imagesRoot != null && imagesRoot.has("logos")) {
                        JsonNode logos = imagesRoot.get("logos");
                        if (logos.isArray() && logos.size() > 0) {
                            String logoFilePath = null;
                            for (JsonNode logoNode : logos) {
                                if ("en".equals(logoNode.path("iso_639_1").asText())) {
                                    logoFilePath = logoNode.path("file_path").asText(null);
                                    break;
                                }
                            }
                            if (logoFilePath == null) logoFilePath = logos.get(0).path("file_path").asText(null);
                            if (logoFilePath != null) {
                                logo = Optional.of(TMDB_IMAGE_W500 + logoFilePath);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warn("[FetchMediaImages] Logo fetch failed for {}: {}", title, ex.getMessage());
            }

            Optional<String> still = Optional.empty();
            if ("episode".equalsIgnoreCase(type) && seriesTitle != null && seasonNumber != null && episodeNumber != null) {
                try {
                    still = fetchEpisodeImageUrl(seriesTitle, seasonNumber, episodeNumber, year);
                } catch (Exception ex) {
                    LOG.warn("[FetchMediaImages] Episode still fetch failed for {} S{}E{}: {}", title, seasonNumber, episodeNumber, ex.getMessage());
                }
            }

            return new MediaImages(poster, logo, backdrop, hero, still);
        } catch (Exception e) {
            LOG.warn("[FetchMediaImages] TMDB image fetch failed for {}: {}", title, e.getMessage());
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * Fetch media images using a pre-resolved TMDB ID, bypassing the TMDB search step.
     * When tmdbId is null/blank, delegates to the original method for full backward compatibility.
     *
     * @param tmdbId pre-resolved TMDB ID (show/movie) — when non-blank, skips TMDB search and uses it directly
     */
    public MediaImages fetchMediaImages(String type, String title, Integer year,
                                         String seriesTitle, Integer seasonNumber, Integer episodeNumber,
                                         String tmdbId) {
        if (tmdbId == null || tmdbId.isBlank() || tmdbId.equals("null")) {
            return fetchMediaImages(type, title, year, seriesTitle, seasonNumber, episodeNumber);
        }

        if (title == null || title.isBlank()) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        try {
            Map<String, String> authHeaders = isBearerToken(tmdbKey) ? Map.of("Authorization", "Bearer " + tmdbKey) : null;

            Optional<String> poster = Optional.empty();
            Optional<String> logo = Optional.empty();
            Optional<String> backdrop = Optional.empty();
            Optional<String> hero = Optional.empty();

            // Fetch show/movie details to get poster and backdrop
            String detailUrl;
            if (isBearerToken(tmdbKey)) {
                detailUrl = "movie".equalsIgnoreCase(type)
                    ? String.format("https://api.themoviedb.org/3/movie/%s", tmdbId)
                    : String.format("https://api.themoviedb.org/3/tv/%s", tmdbId);
            } else {
                detailUrl = "movie".equalsIgnoreCase(type)
                    ? String.format(TMDB_MOVIE_DETAILS, tmdbId, tmdbKey)
                    : String.format(TMDB_TV_DETAILS, tmdbId, tmdbKey);
            }
            JsonNode detailRoot = fetchJson(detailUrl, authHeaders);
            if (detailRoot != null) {
                String posterPath = detailRoot.path("poster_path").asText(null);
                if (posterPath != null && !posterPath.isEmpty() && !posterPath.equals("null")) {
                    poster = Optional.of(TMDB_IMAGE_W342 + posterPath);
                }

                String backdropPath = detailRoot.path("backdrop_path").asText(null);
                if (backdropPath != null && !backdropPath.isEmpty() && !backdropPath.equals("null")) {
                    backdrop = Optional.of(TMDB_IMAGE_W1280 + backdropPath);
                    hero = Optional.of(TMDB_IMAGE_ORIGINAL + backdropPath);
                }
            }

            // TVMaze poster fallback when TMDB poster is empty
            if (poster.isEmpty() && Boolean.TRUE.equals(settings.getTvmazeEnabled())) {
                try {
                    String tvmazeUrl = String.format(TVMAZE_SEARCH, URLEncoder.encode(title, StandardCharsets.UTF_8));
                    JsonNode tvmazeRoot = fetchJson(tvmazeUrl, null);
                    if (tvmazeRoot != null && tvmazeRoot.isArray()) {
                        for (JsonNode result : tvmazeRoot) {
                            JsonNode show = result.path("show");
                            if (show.has("image") && show.get("image").has("medium")) {
                                poster = Optional.of(show.get("image").get("medium").asText());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("TVMaze poster fallback failed for {}: {}", title, e.getMessage());
                }
            }

            // Fetch logos
            try {
                String imagesUrl;
                if (isBearerToken(tmdbKey)) {
                    imagesUrl = "movie".equalsIgnoreCase(type)
                        ? String.format("https://api.themoviedb.org/3/movie/%s/images", tmdbId)
                        : String.format("https://api.themoviedb.org/3/tv/%s/images", tmdbId);
                } else {
                    imagesUrl = "movie".equalsIgnoreCase(type)
                        ? String.format(TMDB_MOVIE_IMAGES, tmdbId, tmdbKey)
                        : String.format(TMDB_TV_IMAGES, tmdbId, tmdbKey);
                }
                JsonNode imagesRoot = fetchJson(imagesUrl, authHeaders);
                if (imagesRoot != null && imagesRoot.has("logos")) {
                    JsonNode logos = imagesRoot.get("logos");
                    if (logos.isArray() && logos.size() > 0) {
                        String logoFilePath = null;
                        for (JsonNode logoNode : logos) {
                            if ("en".equals(logoNode.path("iso_639_1").asText())) {
                                logoFilePath = logoNode.path("file_path").asText(null);
                                break;
                            }
                        }
                        if (logoFilePath == null) logoFilePath = logos.get(0).path("file_path").asText(null);
                        if (logoFilePath != null) {
                            logo = Optional.of(TMDB_IMAGE_W500 + logoFilePath);
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warn("[FetchMediaImages] Logo fetch failed for {}: {}", title, ex.getMessage());
            }

            // Episode still
            Optional<String> still = Optional.empty();
            if ("episode".equalsIgnoreCase(type) && seriesTitle != null && seasonNumber != null && episodeNumber != null) {
                try {
                    still = fetchEpisodeImageUrl(seriesTitle, seasonNumber, episodeNumber, tmdbId, year);
                } catch (Exception ex) {
                    LOG.warn("[FetchMediaImages] Episode still fetch failed for {} S{}E{}: {}", title, seasonNumber, episodeNumber, ex.getMessage());
                }
            }

            return new MediaImages(poster, logo, backdrop, hero, still);
        } catch (Exception e) {
            LOG.warn("[FetchMediaImages] TMDB image fetch failed for {}: {}", title, e.getMessage());
            return new MediaImages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * Fetch episode-specific image URL from TMDB
     */
    public Optional<String> fetchEpisodeImageUrl(String seriesTitle, int seasonNumber, int episodeNumber, Integer year) {
        if (seriesTitle == null || seriesTitle.isBlank()) return Optional.empty();
        
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            return Optional.empty();
        }
        
        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            return Optional.empty();
        }
        
        try {
            Map<String, String> authHeaders = isBearerToken(tmdbKey) ? Map.of("Authorization", "Bearer " + tmdbKey) : null;
            String yearSuffix = year != null ? "&first_air_date_year=" + year : "";
            String searchUrl;
            if (isBearerToken(tmdbKey)) {
                searchUrl = String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8), yearSuffix);
            } else {
                searchUrl = String.format(TMDB_SEARCH_TV + "%s", tmdbKey, URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8), yearSuffix);
            }
            JsonNode searchResult = fetchJson(searchUrl, authHeaders);
            
            if (searchResult == null || !searchResult.has("results") || searchResult.get("results").isEmpty()) {
                return Optional.empty();
            }
            
            String tvShowId = searchResult.get("results").get(0).get("id").asText();
            
            String episodeUrl;
            if (isBearerToken(tmdbKey)) {
                episodeUrl = String.format("https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?append_to_response=credits,images", tvShowId, seasonNumber, episodeNumber);
            } else {
                episodeUrl = String.format(TMDB_EPISODE_DETAILS, tvShowId, seasonNumber, episodeNumber, tmdbKey);
            }
            JsonNode episodeResult = fetchJson(episodeUrl, authHeaders);
            
            if (episodeResult != null && episodeResult.has("still_path")) {
                String stillPath = episodeResult.get("still_path").asText();
                if (stillPath != null && !stillPath.isEmpty()) {
                    return Optional.of(TMDB_IMAGE_W500 + stillPath);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch episode image for {} S{}E{}: {}", seriesTitle, seasonNumber, episodeNumber, e.getMessage());
        }
        
        return Optional.empty();
    }

    /**
     * Fetch episode-specific image URL using a pre-resolved TMDB ID, bypassing the TMDB search step.
     * When tmdbId is null/blank, delegates to the original method for full backward compatibility.
     *
     * @param tmdbId pre-resolved TMDB TV show ID — when non-blank, skips search and uses it directly
     */
    public Optional<String> fetchEpisodeImageUrl(String seriesTitle, int seasonNumber, int episodeNumber, String tmdbId, Integer year) {
        if (tmdbId == null || tmdbId.isBlank() || tmdbId.equals("null")) {
            return fetchEpisodeImageUrl(seriesTitle, seasonNumber, episodeNumber, year);
        }

        if (seriesTitle == null || seriesTitle.isBlank()) return Optional.empty();

        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            return Optional.empty();
        }

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, String> authHeaders = isBearerToken(tmdbKey) ? Map.of("Authorization", "Bearer " + tmdbKey) : null;

            String episodeUrl;
            if (isBearerToken(tmdbKey)) {
                episodeUrl = String.format("https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?append_to_response=credits,images", tmdbId, seasonNumber, episodeNumber);
            } else {
                episodeUrl = String.format(TMDB_EPISODE_DETAILS, tmdbId, seasonNumber, episodeNumber, tmdbKey);
            }
            JsonNode episodeResult = fetchJson(episodeUrl, authHeaders);

            if (episodeResult != null && episodeResult.has("still_path")) {
                String stillPath = episodeResult.get("still_path").asText();
                if (stillPath != null && !stillPath.isEmpty()) {
                    return Optional.of(TMDB_IMAGE_W500 + stillPath);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch episode image for {} S{}E{}: {}", seriesTitle, seasonNumber, episodeNumber, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Ensure text metadata (overview, genres, directors/networks) are populated
     * for a single video by fetching from TMDB on demand. Only fields that are
     * currently null or empty are filled — existing data is never overwritten.
     *
     * @param videoId the Video entity ID
     */
    public void ensureMediaTextMetadata(Long videoId) {
        if (videoId == null) {
            LOG.info("[EnsureTextMetadata] videoId is null, skipping");
            return;
        }

        // Detached snapshot with lazy collections initialized; runs without an open
        // transaction so TMDB lookups below never hold database locks.
        Video video = self.loadVideoForEnrichment(videoId);
        if (video == null) {
            LOG.warn("[EnsureTextMetadata] Video {} not found", videoId);
            return;
        }

        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            LOG.info("[EnsureTextMetadata] TMDB disabled in settings, skipping video {}", videoId);
            return;
        }

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            LOG.info("[EnsureTextMetadata] No TMDB API key available, skipping video {}", videoId);
            return;
        }

        String type = video.type != null ? video.type : "movie";

        boolean needsOverview  = video.overview == null || video.overview.isBlank();
        boolean needsGenres    = video.genres == null || video.genres.isEmpty();
        boolean needsDirectors = !"episode".equalsIgnoreCase(type) && (video.directors == null || video.directors.isEmpty());
        boolean needsNetworks  = "episode".equalsIgnoreCase(type) && (video.networks == null || video.networks.isEmpty());
        boolean needsEpisodeTitle = "episode".equalsIgnoreCase(type)
                && (video.episodeTitle == null || video.episodeTitle.isBlank());

        if (!needsOverview && !needsGenres && !needsDirectors && !needsNetworks && !needsEpisodeTitle) {
            return;
        }

        if (!ENRICHING_VIDEOS.add(videoId)) {
            LOG.debug("[EnsureTextMetadata] Video {} already being enriched by another request, skipping", videoId);
            return;
        }

        try {
            Map<String, String> authHeaders = isBearerToken(tmdbKey)
                    ? Map.of("Authorization", "Bearer " + tmdbKey) : null;

            if ("movie".equalsIgnoreCase(type)) {
                String searchQuery = video.title != null
                        ? URLEncoder.encode(video.title, StandardCharsets.UTF_8) : null;
                if (searchQuery == null) {
                    LOG.warn("[EnsureTextMetadata] Movie video {} has no title, skipping", videoId);
                    return;
                }

                String yearSuffix = video.releaseYear != null ? "&year=" + video.releaseYear : "";
                String searchUrl = isBearerToken(tmdbKey)
                        ? String.format("https://api.themoviedb.org/3/search/movie?query=%s%s", searchQuery, yearSuffix)
                        : String.format(TMDB_SEARCH_MOVIE + "%s", tmdbKey, searchQuery, yearSuffix);
                JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
                if (searchRoot == null || searchRoot.path("results").isEmpty()) {
                    LOG.info("[EnsureTextMetadata] No TMDB movie results for '{}'", video.title);
                    return;
                }

                String tmdbId = searchRoot.path("results").get(0).path("id").asText(null);
                if (tmdbId == null) {
                    LOG.info("[EnsureTextMetadata] TMDB returned null id for video {}", videoId);
                    return;
                }

                String detailUrl = isBearerToken(tmdbKey)
                        ? String.format("https://api.themoviedb.org/3/movie/%s?append_to_response=credits", tmdbId)
                        : String.format(TMDB_MOVIE_DETAILS, tmdbId, tmdbKey);
                JsonNode root = fetchJson(detailUrl, authHeaders);
                if (root == null) {
                    LOG.warn("[EnsureTextMetadata] Failed to fetch movie details for tmdbId={}", tmdbId);
                    return;
                }

                if (needsOverview && root.has("overview") && !root.get("overview").isNull()) {
                    video.overview = root.get("overview").asText();
                }
                if (needsGenres && root.has("genres") && root.get("genres").isArray()) {
                    List<String> genres = new ArrayList<>();
                    for (JsonNode g : root.get("genres")) {
                        if (g.has("name")) genres.add(g.get("name").asText());
                    }
                    if (!genres.isEmpty()) video.genres = genres;
                }
                if (needsDirectors && root.has("credits") && root.get("credits").has("crew")
                        && root.get("credits").get("crew").isArray()) {
                    List<String> directors = new ArrayList<>();
                    for (JsonNode crew : root.get("credits").get("crew")) {
                        if ("Director".equals(crew.path("job").asText())) {
                            directors.add(crew.path("name").asText());
                        }
                    }
                    if (!directors.isEmpty()) video.directors = directors;
                }

                LOG.info("[EnsureTextMetadata] Movie '{}': overview={}, genres={}, directors={}",
                        video.title,
                        video.overview != null ? "set" : "none",
                        video.genres != null ? video.genres.size() + " genres" : "none",
                        video.directors != null ? video.directors.size() + " directors" : "none");

            } else if ("episode".equalsIgnoreCase(type)) {
                String showId = (video.tmdbId != null && !video.tmdbId.isBlank() && !video.tmdbId.equals("null"))
                        ? video.tmdbId : null;

                if (showId == null) {
                    String seriesSearchQuery = video.seriesTitle != null
                            ? URLEncoder.encode(video.seriesTitle, StandardCharsets.UTF_8) : null;
                    if (seriesSearchQuery == null) {
                        LOG.warn("[EnsureTextMetadata] Episode video {} has no seriesTitle, skipping", videoId);
                        return;
                    }

                    String yearSuffix = video.releaseYear != null ? "&first_air_date_year=" + video.releaseYear : "";
                    String searchUrl = isBearerToken(tmdbKey)
                            ? String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", seriesSearchQuery, yearSuffix)
                            : String.format(TMDB_SEARCH_TV + "%s", tmdbKey, seriesSearchQuery, yearSuffix);
                    JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
                    if (searchRoot == null || searchRoot.path("results").isEmpty()) {
                        LOG.info("[EnsureTextMetadata] No TMDB TV results for '{}'", video.seriesTitle);
                        return;
                    }

                    showId = searchRoot.path("results").get(0).path("id").asText(null);
                    if (showId == null) {
                        LOG.info("[EnsureTextMetadata] TMDB returned null showId for video {}", videoId);
                        return;
                    }
                    video.tmdbId = showId;
                } else {
                    LOG.debug("[EnsureTextMetadata] Episode '{}' using stored tmdbId={}", video.seriesTitle, showId);
                }

                if ((needsOverview || needsEpisodeTitle) && video.seasonNumber != null && video.episodeNumber != null) {
                    String episodeUrl = isBearerToken(tmdbKey)
                            ? String.format("https://api.themoviedb.org/3/tv/%s/season/%s/episode/%s?append_to_response=credits",
                                    showId, video.seasonNumber, video.episodeNumber)
                            : String.format(TMDB_EPISODE_DETAILS, showId, video.seasonNumber, video.episodeNumber, tmdbKey);
                    JsonNode episodeRoot = fetchJson(episodeUrl, authHeaders);
                    if (episodeRoot != null && episodeRoot.has("overview") && !episodeRoot.get("overview").isNull()) {
                        video.overview = episodeRoot.get("overview").asText();
                    }
                    if (needsEpisodeTitle && episodeRoot != null && episodeRoot.has("name")
                            && !episodeRoot.get("name").isNull()) {
                        String epName = episodeRoot.get("name").asText();
                        if (epName != null && !epName.isBlank()) {
                            video.episodeTitle = epName;
                        }
                    }
                }

                if (needsNetworks || needsGenres) {
                    String showUrl = isBearerToken(tmdbKey)
                            ? String.format("https://api.themoviedb.org/3/tv/%s?append_to_response=credits", showId)
                            : String.format(TMDB_TV_DETAILS, showId, tmdbKey);
                    JsonNode showRoot = fetchJson(showUrl, authHeaders);
                    if (showRoot != null) {
                        if (needsNetworks && showRoot.has("networks") && showRoot.get("networks").isArray()) {
                            List<String> networkNames = new ArrayList<>();
                            for (JsonNode net : showRoot.get("networks")) {
                                if (net.has("name")) networkNames.add(net.get("name").asText());
                            }
                            if (!networkNames.isEmpty()) video.networks = networkNames;
                        }
                        if (needsGenres && showRoot.has("genres") && showRoot.get("genres").isArray()) {
                            List<String> genres = new ArrayList<>();
                            for (JsonNode g : showRoot.get("genres")) {
                                if (g.has("name")) genres.add(g.get("name").asText());
                            }
                            if (!genres.isEmpty()) video.genres = genres;
                        }
                    }
                }

            } else {
                LOG.debug("[EnsureTextMetadata] Unsupported type '{}' for video {}, skipping", type, videoId);
                return;
            }

            self.persistEnrichedVideo(video);
        } catch (Exception e) {
            LOG.warn("[EnsureTextMetadata] Failed for video {}: {}", videoId, e.getMessage());
        } finally {
            ENRICHING_VIDEOS.remove(videoId);
        }
    }

    /**
     * Ensure text metadata (overview, genres, networks, directors, writers, cast) are populated
     * for a Series entity by fetching from TMDB on demand. Only fields that are currently
     * null or empty are filled — existing data is never overwritten.
     *
     * @param seriesId the Series entity ID
     */
    public SeriesEnrichmentResult ensureSeriesTextMetadata(Long seriesId) {
        if (seriesId == null) {
            LOG.info("[EnsureSeriesTextMetadata] seriesId is null, skipping");
            return SeriesEnrichmentResult.SKIPPED;
        }

        // Detached snapshot with lazy collections initialized; runs without an open
        // transaction so TMDB lookups below never hold database locks.
        Series series = self.loadSeriesForEnrichment(seriesId);
        if (series == null) {
            LOG.warn("[EnsureSeriesTextMetadata] Series {} not found", seriesId);
            return SeriesEnrichmentResult.SKIPPED;
        }

        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getTmdbEnabled())) {
            LOG.info("[EnsureSeriesTextMetadata] TMDB disabled in settings, skipping series {}", seriesId);
            return SeriesEnrichmentResult.SKIPPED;
        }

        String tmdbKey = getApiKey();
        if (tmdbKey == null || tmdbKey.isBlank()) {
            LOG.info("[EnsureSeriesTextMetadata] No TMDB API key available, skipping series {}", seriesId);
            return SeriesEnrichmentResult.SKIPPED;
        }

        // Check what needs populating
        boolean needsOverview  = series.overview == null || series.overview.isBlank();
        boolean needsGenres    = series.genres == null || series.genres.isEmpty();
        boolean needsNetworks  = series.networks == null || series.networks.isEmpty();
        boolean needsDirectors = series.directors == null || series.directors.isEmpty();
        boolean needsWriters   = series.writers == null || series.writers.isEmpty();
        boolean needsCast      = series.cast == null || series.cast.isEmpty();
        boolean needsRating    = series.tmdbRating == null;
        boolean needsVoteCount = series.voteCount == null;
        boolean needsStatus    = series.status == null || series.status.isBlank();
        boolean needsTagline   = series.tagline == null || series.tagline.isBlank();

        if (!needsOverview && !needsGenres && !needsNetworks && !needsDirectors
                && !needsWriters && !needsCast && !needsRating && !needsVoteCount
                && !needsStatus && !needsTagline) {
            LOG.info("[EnsureSeriesTextMetadata] Series {} already has all text metadata, skipping", seriesId);
            return SeriesEnrichmentResult.ALREADY_COMPLETE; // Everything already populated
        }

        boolean updated = false;
        try {
            Map<String, String> authHeaders = isBearerToken(tmdbKey)
                    ? Map.of("Authorization", "Bearer " + tmdbKey) : null;

            // Determine the TMDB show ID — use stored tmdbId or search by title
            String showId;
            if (series.tmdbId != null) {
                showId = String.valueOf(series.tmdbId);
            } else {
                String searchQuery = series.title != null
                        ? URLEncoder.encode(series.title, StandardCharsets.UTF_8) : null;
                if (searchQuery == null) {
                    LOG.info("[EnsureSeriesTextMetadata] Series {} has no title, skipping", seriesId);
                    return SeriesEnrichmentResult.SKIPPED;
                }
                String yearSuffix = series.releaseYear != null ? "&first_air_date_year=" + series.releaseYear : "";
                String searchUrl = isBearerToken(tmdbKey)
                        ? String.format("https://api.themoviedb.org/3/search/tv?query=%s%s", searchQuery, yearSuffix)
                        : String.format(TMDB_SEARCH_TV + "%s", tmdbKey, searchQuery, yearSuffix);
                JsonNode searchRoot = fetchJson(searchUrl, authHeaders);
                if (searchRoot == null) {
                    LOG.info("[EnsureSeriesTextMetadata] TMDB search failed (HTTP error) for '{}'", series.title);
                    return SeriesEnrichmentResult.FAILED;
                }
                if (searchRoot.path("results").isEmpty()) {
                    return SeriesEnrichmentResult.NO_MATCH;
                }
                showId = searchRoot.path("results").get(0).path("id").asText();
                // Save the tmdbId for future use
                try {
                    series.tmdbId = Integer.parseInt(showId);
                    updated = true;
                } catch (NumberFormatException ignored) {}
            }

            // Fetch full show details with credits
            String detailUrl = isBearerToken(tmdbKey)
                    ? String.format("https://api.themoviedb.org/3/tv/%s?append_to_response=credits", showId)
                    : String.format(TMDB_TV_DETAILS, showId, tmdbKey);
            JsonNode root = fetchJson(detailUrl, authHeaders);
            if (root == null) {
                LOG.info("[EnsureSeriesTextMetadata] Failed to fetch TMDB details for series {}", seriesId);
                return SeriesEnrichmentResult.FAILED;
            }

            if (needsOverview && root.has("overview") && !root.get("overview").isNull()) {
                series.overview = root.get("overview").asText();
                updated = true;
            }
            if (needsTagline && root.has("tagline") && !root.get("tagline").isNull()) {
                String tagline = root.get("tagline").asText();
                if (tagline != null && !tagline.isBlank()) {
                    series.tagline = tagline;
                    updated = true;
                }
            }
            if (needsStatus && root.has("status") && !root.get("status").isNull()) {
                series.status = root.get("status").asText();
                updated = true;
            }
            if (needsRating && root.has("vote_average")) {
                double rating = root.get("vote_average").asDouble(0);
                if (rating > 0) { series.tmdbRating = rating; updated = true; }
            }
            if (needsVoteCount && root.has("vote_count")) {
                int vc = root.get("vote_count").asInt(0);
                if (vc > 0) { series.voteCount = vc; updated = true; }
            }
            if (needsGenres && root.has("genres") && root.get("genres").isArray()) {
                List<String> genres = new ArrayList<>();
                for (JsonNode g : root.get("genres")) {
                    if (g.has("name")) genres.add(g.get("name").asText());
                }
                if (!genres.isEmpty()) { series.genres = genres; updated = true; }
            }
            if (needsNetworks && root.has("networks") && root.get("networks").isArray()) {
                List<String> networks = new ArrayList<>();
                for (JsonNode n : root.get("networks")) {
                    if (n.has("name")) networks.add(n.get("name").asText());
                }
                if (!networks.isEmpty()) { series.networks = networks; updated = true; }
            }

            // Credits — directors (showrunners), writers, cast
            if (root.has("credits")) {
                JsonNode credits = root.get("credits");

                if (needsDirectors && credits.has("crew") && credits.get("crew").isArray()) {
                    List<String> showrunners = new ArrayList<>();
                    for (JsonNode crew : credits.get("crew")) {
                        String job = crew.path("job").asText("");
                        if ("Executive Producer".equals(job) || "Showrunner".equals(job) || "Director".equals(job)) {
                            String name = crew.path("name").asText();
                            if (name != null && !name.isBlank() && !showrunners.contains(name)) {
                                showrunners.add(name);
                            }
                        }
                    }
                    if (!showrunners.isEmpty()) { series.directors = showrunners; updated = true; }
                }

                if (needsWriters && credits.has("crew") && credits.get("crew").isArray()) {
                    List<String> writers = new ArrayList<>();
                    for (JsonNode crew : credits.get("crew")) {
                        String job = crew.path("job").asText("");
                        if ("Writer".equals(job) || "Screenplay".equals(job) || "Story".equals(job)) {
                            String name = crew.path("name").asText();
                            if (name != null && !name.isBlank() && !writers.contains(name)) {
                                writers.add(name);
                            }
                        }
                    }
                    if (!writers.isEmpty()) { series.writers = writers; updated = true; }
                }

                if (needsCast && credits.has("cast") && credits.get("cast").isArray()) {
                    List<String> castList = new ArrayList<>();
                    for (JsonNode actor : credits.get("cast")) {
                        String name = actor.path("name").asText();
                        if (name != null && !name.isBlank()) {
                            castList.add(name);
                            if (castList.size() >= 15) break; // Top 15 cast members
                        }
                    }
                    if (!castList.isEmpty()) { series.cast = castList; updated = true; }
                }
            }

            // Also populate releaseDate if available
            if (series.releaseDate == null && root.has("first_air_date") && !root.get("first_air_date").isNull()) {
                series.releaseDate = root.get("first_air_date").asText();
                updated = true;
            }
            if (series.releaseYear == null && root.has("first_air_date") && !root.get("first_air_date").isNull()) {
                String dateStr = root.get("first_air_date").asText();
                if (dateStr != null && dateStr.length() >= 4) {
                    try {
                        series.releaseYear = Integer.parseInt(dateStr.substring(0, 4));
                        updated = true;
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (series.originalLanguage == null && root.has("original_language") && !root.get("original_language").isNull()) {
                series.originalLanguage = root.get("original_language").asText();
                updated = true;
            }

            if (updated) {
                LOG.info("[EnsureSeriesTextMetadata] Updated metadata for '{}': overview={}, genres={}, networks={}, directors={}, writers={}, cast={}",
                        series.title,
                        series.overview != null ? "set" : "none",
                        series.genres != null ? series.genres.size() : 0,
                        series.networks != null ? series.networks.size() : 0,
                        series.directors != null ? series.directors.size() : 0,
                        series.writers != null ? series.writers.size() : 0,
                        series.cast != null ? series.cast.size() : 0);
                self.persistEnrichedSeries(series);
            }
        } catch (Exception e) {
            LOG.warn("[EnsureSeriesTextMetadata] Failed for series '{}': {}", series.title, e.getMessage());
            return SeriesEnrichmentResult.FAILED;
        }
        return SeriesEnrichmentResult.SUCCESS;
    }

    public enum SeriesEnrichmentResult {
        SUCCESS, NO_MATCH, ALREADY_COMPLETE, SKIPPED, FAILED
    }

    /**
     * Fire-and-forget async series text enrichment guarded by a per-series cooldown map.
     * TMDB-not-configured returns WITHOUT recording (MAJOR-4: enabling TMDB later must resume without restart);
     * an attempt within retryCooldownMs returns WITHOUT re-recording.
     */
    public void enrichSeriesTextMetadataAsync(Long seriesId) {
        if (seriesId == null) return; // ConcurrentHashMap.getOrDefault(null, ...) would NPE
        if (!isTmdbConfigured()) return; // MAJOR-4: never record when TMDB disabled
        long retryCooldownMs = retryCooldownMinutes * 60 * 1000L;
        if (System.currentTimeMillis() - seriesEnrichmentAttempts.getOrDefault(seriesId, 0L) < retryCooldownMs) return;
        seriesEnrichmentAttempts.put(seriesId, System.currentTimeMillis());
        seriesEnrichmentExecutor.submit(() -> {
            io.quarkus.arc.ManagedContext requestContext = io.quarkus.arc.Arc.container().requestContext();
            boolean weActivated = false;
            if (!requestContext.isActive()) {
                requestContext.activate();
                weActivated = true;
            }
            try {
                SeriesEnrichmentResult result = self.ensureSeriesTextMetadata(seriesId);
                if (result == SeriesEnrichmentResult.SUCCESS) {
                    cinemaHomeCache.invalidateAll().await().indefinitely(); // MAJOR-7: re-render with fresh ratings/genres
                }
            } catch (Exception e) {
                LOG.warn("[EnrichSeriesTextMetadataAsync] Failed for series {}: {}", seriesId, e.getMessage());
            } finally {
                if (weActivated) requestContext.deactivate();
            }
        });
    }

    // =====================================================================================
    // Short-transaction persistence helpers.
    //
    // Enrichment used to run inside ONE @Transactional method held open across TMDB/OMDb/
    // IMDb HTTP calls, ffprobe subprocesses and image downloads. With H2 file storage every
    // write in that window kept its row lock until COMMIT, so the background workers
    // deadlocked each other on VIDEO/SERIES rows ("Timeout trying to lock table").
    //
    // The public entry points are therefore no longer transactional: they mutate detached
    // snapshots from the load*ForEnrichment methods and flush through the tiny persist*
    // transactions below. All external I/O now happens with NO open transaction.
    // Call these through the CDI proxy (self.) so the interceptors actually apply.
    // =====================================================================================

    /**
     * Loads a Video with every lazy {@code @ElementCollection} initialized so the detached
     * instance can be read and mutated freely outside a session (in-place adds in
     * fetchAkas/fetchCredits/fetchCompanyCredits require real collections).
     *
     * subtitleTracks/audioTracks are deliberately left UNINITIALIZED: they map to
     * cascade=ALL + orphanRemoval=true, and merge() ignores uninitialized collections —
     * so tracks written concurrently by updateAudioTracks/updateSubtitleTracks survive
     * persistEnrichedVideo instead of being deleted as stale orphans.
     */
    @jakarta.transaction.Transactional
    public Models.Video.Video loadVideoForEnrichment(Long videoId) {
        if (videoId == null) return null;
        Models.Video.Video v = Models.Video.Video.findById(videoId);
        if (v == null) return null;
        if (v.genres != null) v.genres.size();
        if (v.directors != null) v.directors.size();
        if (v.writers != null) v.writers.size();
        if (v.cast != null) v.cast.size();
        if (v.productionCompanies != null) v.productionCompanies.size();
        if (v.networks != null) v.networks.size();
        if (v.akas != null) v.akas.size();
        if (v.keywords != null) v.keywords.size();
        if (v.series != null) {
            Hibernate.initialize(v.series);
            if (v.series.genres != null) v.series.genres.size();
            if (v.series.networks != null) v.series.networks.size();
            if (v.series.directors != null) v.series.directors.size();
            if (v.series.writers != null) v.series.writers.size();
            if (v.series.cast != null) v.series.cast.size();
            if (v.series.productionCompanies != null) v.series.productionCompanies.size();
            if (v.series.akas != null) v.series.akas.size();
            if (v.series.keywords != null) v.series.keywords.size();
        }
        return v;
    }

    /**
     * Same contract as {@link #loadVideoForEnrichment(Long)} for a Series entity.
     * The videos list stays uninitialized on purpose (never touched during enrichment).
     */
    @jakarta.transaction.Transactional
    public Series loadSeriesForEnrichment(Long seriesId) {
        if (seriesId == null) return null;
        Series s = Series.findById(seriesId);
        if (s == null) return null;
        if (s.genres != null) s.genres.size();
        if (s.directors != null) s.directors.size();
        if (s.writers != null) s.writers.size();
        if (s.cast != null) s.cast.size();
        if (s.productionCompanies != null) s.productionCompanies.size();
        if (s.networks != null) s.networks.size();
        if (s.akas != null) s.akas.size();
        if (s.keywords != null) s.keywords.size();
        return s;
    }

    /**
     * Persists an enriched detached Video in its own short transaction. A linked Series is
     * merged separately (Video.series has no cascade — the merge resolves the FK by id),
     * but only when it was initialized, i.e. actually loaded/mutated by episode enrichment.
     */
    @jakarta.transaction.Transactional
    public void persistEnrichedVideo(Models.Video.Video enriched) {
        if (enriched == null || enriched.id == null) return;
        enriched.getEntityManager().merge(enriched);
        if (enriched.series != null && Hibernate.isInitialized(enriched.series) && enriched.series.id != null) {
            enriched.series.getEntityManager().merge(enriched.series);
        }
    }

    /** Persists an enriched detached Series in its own short transaction. */
    @jakarta.transaction.Transactional
    public void persistEnrichedSeries(Series enriched) {
        if (enriched == null || enriched.id == null) return;
        enriched.getEntityManager().merge(enriched);
    }

    /**
     * Best-effort failure-path write: marks the video FAILED in its own short transaction
     * so an aborted enrichment never leaves a stale NOT_ATTEMPTED/ENRICHED state behind.
     */
    @jakarta.transaction.Transactional
    public void persistEnrichmentStatus(Long videoId, Video.EnrichmentStatus status) {
        if (videoId == null || status == null) return;
        Models.Video.Video v = Models.Video.Video.findById(videoId);
        if (v != null) {
            v.enrichmentStatus = status; // managed inside this transaction — flushed at commit
        }
    }

    @PreDestroy
    void shutdown() {
        seriesEnrichmentExecutor.shutdown();
    }
}
