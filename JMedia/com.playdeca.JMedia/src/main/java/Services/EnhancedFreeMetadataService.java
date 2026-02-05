package Services;

import com.playdeca.jmedia.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.Data;

/**
 * Enhanced service for enriching metadata using free, no-authentication APIs.
 * Features retry mechanisms, circuit breakers, and proper error classification.
 */
@ApplicationScoped
public class EnhancedFreeMetadataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedFreeMetadataService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // API Endpoints
    private static final String MUSICBRAINZ_API = "https://musicbrainz.org/ws/2/recording/?query=%s&fmt=json";
    private static final String DEEZER_API = "https://api.deezer.com/search/track/?q=%s&limit=5";
    private static final String THEAUDIODB_API = "https://www.theaudiodb.com/api/v1/json/2/search.php?s=%s";

    // API availability tracking
    private final Map<String, Boolean> apiAvailability = new HashMap<>();
    private final Map<String, Long> lastFailureTime = new HashMap<>();

    /**
     * Enriches metadata for a given artist and title combination with enhanced
     * reliability.
     */
    @Fallback(fallbackMethod = "fallbackEnrichMetadata")
    @Timeout(value = 45, unit = ChronoUnit.SECONDS)
    public EnhancedMetadataResult enrichMetadata(String artist, String title) {
        long startTime = System.currentTimeMillis();

        try {
            // Parse artist from title if artist is unknown and title contains " - " separator
            String parsedArtist = artist;
            String parsedTitle = title;

            if (("Unknown Artist".equals(artist) || artist == null || artist.trim().isEmpty())
                    && title != null && title.contains(" - ")) {
                String[] parts = title.split(" - ", 2);
                if (parts.length == 2) {
                    parsedArtist = parts[0].trim();
                    parsedTitle = parts[1].trim();
                    LOGGER.info("Parsed artist from title: '{}' - '{}'", parsedArtist, parsedTitle);
                }
            }

            LOGGER.info("Starting enhanced metadata enrichment for: {} - {}", parsedArtist, parsedTitle);

            EnhancedMetadataResult result = new EnhancedMetadataResult();

            // Capture original values for comparison
            result.originalArtist = artist;
            result.originalTitle = title;

            // Check API availability first
            if (!isAnyApiAvailable()) {
                throw new RuntimeException(new ApiUnavailableException("All APIs", "All metadata APIs are currently unavailable", null));
            }

            // Primary: MusicBrainz search with retries
            try {
                Optional<MusicBrainzData> mbResult = searchMusicBrainz(parsedArtist, parsedTitle);
                if (mbResult.isPresent()) {
                    mergeMusicBrainzResult(result, mbResult.get());
                    result.addApiResult("MusicBrainz", ApiResult.SUCCESS, System.currentTimeMillis() - startTime);
                    LOGGER.info("Found MusicBrainz data: {} - {}", mbResult.get().artist, mbResult.get().title);
                } else {
                    result.addApiResult("MusicBrainz", ApiResult.NO_DATA, System.currentTimeMillis() - startTime);
                }
            } catch (ApiException e) {
                result.addApiResult("MusicBrainz", ApiResult.fromException(e), System.currentTimeMillis() - startTime);
                updateApiAvailability("MusicBrainz", false);
                LOGGER.warn("MusicBrainz API failed: {}", e.getMessage());
            }

            // Secondary: Deezer for album art and additional genres
            try {
                Optional<DeezerData> deezerResult = searchDeezer(parsedArtist, parsedTitle);
                if (deezerResult.isPresent()) {
                    mergeDeezerResult(result, deezerResult.get());
                    result.addApiResult("Deezer", ApiResult.SUCCESS, System.currentTimeMillis() - startTime);
                    LOGGER.info("Added data from Deezer");
                } else {
                    result.addApiResult("Deezer", ApiResult.NO_DATA, System.currentTimeMillis() - startTime);
                }
            } catch (ApiException e) {
                result.addApiResult("Deezer", ApiResult.fromException(e), System.currentTimeMillis() - startTime);
                updateApiAvailability("Deezer", false);
                LOGGER.warn("Deezer API failed: {}", e.getMessage());
            }

            // Tertiary: TheAudioDB for backup album art
            if (!result.hasAlbumArt()) {
                try {
                    Optional<TheAudioDbData> audioDbResult = searchTheAudioDb(parsedArtist, parsedTitle);
                    if (audioDbResult.isPresent()) {
                        mergeTheAudioDbResult(result, audioDbResult.get());
                        result.addApiResult("TheAudioDB", ApiResult.SUCCESS, System.currentTimeMillis() - startTime);
                        LOGGER.info("Added backup album art from TheAudioDB");
                    } else {
                        result.addApiResult("TheAudioDB", ApiResult.NO_DATA, System.currentTimeMillis() - startTime);
                    }
                } catch (ApiException e) {
                    result.addApiResult("TheAudioDB", ApiResult.fromException(e), System.currentTimeMillis() - startTime);
                    updateApiAvailability("TheAudioDB", false);
                    LOGGER.warn("TheAudioDB API failed: {}", e.getMessage());
                }
            }

            // Finalize result
            result.processingTimeMs = System.currentTimeMillis() - startTime;
            result.deduplicateGenres();

            if (result.isEnriched()) {
                if (result.isEnriched()) {
                    if (result.improved(result.originalArtist, result.artist)) {
                        LOGGER.info("Artist corrected: '{}' → '{}'",
                                result.originalArtist,
                                result.artist);
                    }

                    if (result.improved(result.originalTitle, result.title)) {
                        LOGGER.info("Title corrected: '{}' → '{}'",
                                result.originalTitle,
                                result.title);
                    }
                }
                LOGGER.info("Successfully enriched metadata for {} - {} in {}ms with sources: {}",
                        artist, title, result.processingTimeMs, result.getSuccessfulSources());
                return result;
            } else {
                LOGGER.warn("Could not enrich metadata for {} - {} after trying all APIs. Results: {}",
                        artist, title, result.apiResults);
                return result;
            }

        } catch (RuntimeException e) {
            LOGGER.error("Error enriching metadata for: {} - {}", artist, title, e);
            EnhancedMetadataResult errorResult = new EnhancedMetadataResult();
            errorResult.processingTimeMs = System.currentTimeMillis() - startTime;
            errorResult.lastError = e.getMessage();
            return errorResult;
        }
    }

    /**
     * Fallback method when enrichment fails.
     */
    public EnhancedMetadataResult fallbackEnrichMetadata(String artist, String title) {
        LOGGER.warn("Using fallback enrichment for: {} - {}", artist, title);
        EnhancedMetadataResult result = new EnhancedMetadataResult();
        result.fallbackUsed = true;
        result.lastError = "All APIs failed - fallback mode";
        return result;
    }

    /**
     * Searches MusicBrainz API with enhanced reliability.
     */
    @CircuitBreakerName("musicbrainz")
    @CircuitBreaker(
            successThreshold = 5,
            failureRatio = 0.4,
            delay = 30,
            delayUnit = ChronoUnit.SECONDS,
            failOn = {Exception.class}
    )
    @Retry(
            maxRetries = 3,
            delay = 1000,
            delayUnit = ChronoUnit.MILLIS,
            maxDuration = 15000,
            jitter = 200,
            retryOn = {Exception.class}
    )
    @Timeout(value = 25, unit = ChronoUnit.SECONDS)
    @RateLimit(value = 1, window = 1, windowUnit = ChronoUnit.SECONDS)
    public Optional<MusicBrainzData> searchMusicBrainz(String artist, String title) throws ApiException {
        long startTime = System.currentTimeMillis();

        try {
            // Build a sane query
            final String query;
            if (isUnknown(artist)) {
                // Title-only fallback (critical fix)
                query = String.format("recording:\"%s\"", title);
            } else {
                query = String.format(
                        "artist:\"%s\" AND recording:\"%s\"",
                        artist, title
                );
            }

            String url = String.format(
                    MUSICBRAINZ_API,
                    URLEncoder.encode(query, StandardCharsets.UTF_8)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (Enhanced)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            LOGGER.debug(
                    "MusicBrainz response time: {}ms, status: {}",
                    System.currentTimeMillis() - startTime,
                    response.statusCode()
            );

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode recordings = root.path("recordings");

                if (recordings.isArray()) {
                    // Find the first *useful* recording, not just the first one
                    for (JsonNode recording : recordings) {
                        MusicBrainzData parsed = parseMusicBrainzResult(recording);

                        if (isUseful(parsed)) {
                            return Optional.of(parsed);
                        }
                    }
                }

                return Optional.empty();
            }

            if (response.statusCode() == 429) {
                throw new ApiRateLimitException("MusicBrainz", 2000);
            }

            if (response.statusCode() >= 500) {
                throw new ApiUnavailableException("MusicBrainz", response.statusCode());
            }

            return Optional.empty();

        } catch (SocketTimeoutException | ConnectException e) {
            throw new ApiTimeoutException(
                    "MusicBrainz",
                    System.currentTimeMillis() - startTime,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiUnavailableException("MusicBrainz", "Request interrupted", e);
        } catch (IOException e) {
            throw new ApiParseException("MusicBrainz", "Failed to parse response", e);
        }
    }

    /**
     * Searches Deezer API with enhanced reliability.
     */
    @CircuitBreakerName("deezer")
    @CircuitBreaker(
            successThreshold = 5,
            failureRatio = 0.4,
            delay = 20,
            delayUnit = ChronoUnit.SECONDS
    )
    @Retry(
            maxRetries = 2,
            delay = 500,
            delayUnit = ChronoUnit.MILLIS,
            maxDuration = 10000,
            jitter = 100
    )
    @Timeout(value = 20, unit = ChronoUnit.SECONDS)
    public Optional<DeezerData> searchDeezer(String artist, String title) throws ApiException {
        long startTime = System.currentTimeMillis();

        try {
            // Enhanced query construction - try multiple formats
            String[] queryFormats = {
                artist + " " + title, // Standard format
                "\"" + artist + "\" \"" + title + "\"", // Exact match format
                title + " " + artist // Title-first format
            };

            for (String query : queryFormats) {
                String url = String.format(DEEZER_API, URLEncoder.encode(query, "UTF-8"));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "JMedia/1.0 (Enhanced)")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                long responseTime = System.currentTimeMillis() - startTime;
                LOGGER.debug("Deezer query '{}' response time: {}ms, status: {}", query, responseTime, response.statusCode());

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode tracks = root.path("data");

                    if (tracks.isArray() && tracks.size() > 0) {
                        JsonNode firstTrack = tracks.get(0);
                        LOGGER.info("Deezer found match using query: '{}'", query);
                        return Optional.of(parseDeezerResult(firstTrack));
                    }
                } else if (response.statusCode() == 429) {
                    throw new RuntimeException(new ApiRateLimitException("Deezer", 1000));
                } else if (response.statusCode() >= 500) {
                    throw new RuntimeException(new ApiUnavailableException("Deezer", response.statusCode()));
                }
            }

        } catch (SocketTimeoutException | ConnectException e) {
            throw new RuntimeException(new ApiTimeoutException("Deezer", System.currentTimeMillis() - startTime, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new ApiUnavailableException("Deezer", "Request interrupted", e));
        } catch (IOException e) {
            throw new RuntimeException(new ApiParseException("Deezer", "Failed to parse response", e));
        } catch (RuntimeException e) {
            throw new RuntimeException(new ApiUnavailableException("Deezer", "Unexpected error", e));
        }

        return Optional.empty();
    }

    /**
     * Searches TheAudioDB API with enhanced reliability.
     */
    @CircuitBreakerName("theaudiodb")
    @CircuitBreaker(
            successThreshold = 5,
            failureRatio = 0.4,
            delay = 25,
            delayUnit = ChronoUnit.SECONDS
    )
    @Retry(
            maxRetries = 2,
            delay = 500,
            delayUnit = ChronoUnit.MILLIS,
            maxDuration = 12000,
            jitter = 100
    )
    @Timeout(value = 20, unit = ChronoUnit.SECONDS)
    @RateLimit(value = 2, window = 1, windowUnit = ChronoUnit.SECONDS)
    public Optional<TheAudioDbData> searchTheAudioDb(String artist, String title) throws ApiException {
        long startTime = System.currentTimeMillis();

        try {
            String query = artist + " " + title;
            String url = String.format(THEAUDIODB_API,
                    URLEncoder.encode(query, "UTF-8"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (Enhanced)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            long responseTime = System.currentTimeMillis() - startTime;
            LOGGER.debug("TheAudioDB response time: {}ms, status: {}", responseTime, response.statusCode());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode tracks = root.path("track");

                if (tracks.isArray() && tracks.size() > 0) {
                    JsonNode firstTrack = tracks.get(0);
                    return Optional.of(parseTheAudioDbResult(firstTrack));
                }
            } else if (response.statusCode() == 429) {
                throw new RuntimeException(new ApiRateLimitException("TheAudioDB", 1500));
            } else if (response.statusCode() >= 500) {
                throw new RuntimeException(new ApiUnavailableException("TheAudioDB", response.statusCode()));
            }

        } catch (SocketTimeoutException | ConnectException e) {
            throw new RuntimeException(new ApiTimeoutException("TheAudioDB", System.currentTimeMillis() - startTime, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new ApiUnavailableException("TheAudioDB", "Request interrupted", e));
        } catch (IOException e) {
            throw new RuntimeException(new ApiParseException("TheAudioDB", "Failed to parse response", e));
        } catch (RuntimeException e) {
            throw new RuntimeException(new ApiUnavailableException("TheAudioDB", "Unexpected error", e));
        }

        return Optional.empty();
    }

    /**
     * Checks if any API is currently available.
     */
    private boolean isAnyApiAvailable() {
        long currentTime = System.currentTimeMillis();
        long recoveryTime = 60000; // 1 minute recovery period

        for (Map.Entry<String, Boolean> entry : apiAvailability.entrySet()) {
            String api = entry.getKey();
            Boolean available = entry.getValue();

            if (available == null || available) {
                return true;
            }

            // Check if recovery period has passed
            Long lastFailure = lastFailureTime.get(api);
            if (lastFailure != null && (currentTime - lastFailure) > recoveryTime) {
                updateApiAvailability(api, true);
                return true;
            }
        }

        return true; // Assume available if not tracked
    }

    /**
     * Updates API availability status.
     */
    private void updateApiAvailability(String apiName, boolean available) {
        apiAvailability.put(apiName, available);
        if (!available) {
            lastFailureTime.put(apiName, System.currentTimeMillis());
        } else {
            lastFailureTime.remove(apiName);
        }
        LOGGER.debug("Updated API availability for {}: {}", apiName, available);
    }

    /**
     * Merges MusicBrainz result into the main result with duration-based
     * confidence.
     */
    private void mergeMusicBrainzResult(EnhancedMetadataResult result, MusicBrainzData mb) {
        result.artist = mb.artist;
        result.title = mb.title;
        result.releaseDate = mb.releaseDate;
        result.trackNumber = mb.trackNumber;
        result.album = mb.album;
        result.addAllGenres(mb.genres);
        result.addSource("MusicBrainz");

        // Calculate confidence based on data completeness
        double confidence = 0.9; // Base confidence for MusicBrainz

        if (mb.getDuration() != null && mb.getDuration() > 0) {
            confidence += 0.05; // Boost confidence for duration data
            LOGGER.info("MusicBrainz provided duration: {}ms for {} - {}",
                    mb.getDuration(), mb.artist, mb.title);
        }

        if (mb.getReleaseDate() != null && !mb.getReleaseDate().isEmpty()) {
            confidence += 0.03; // Boost for release date
        }

        if (mb.getAlbum() != null && !mb.getAlbum().isEmpty()) {
            confidence += 0.02; // Boost for album info
        }

        result.setConfidence(Math.min(confidence, 1.0)); // Cap at 1.0
    }

    /**
     * Merges Deezer result into the main result.
     */
    private void mergeDeezerResult(EnhancedMetadataResult result, DeezerData dz) {
        // Update album art if higher quality
        if (dz.hasAlbumArt() && !result.hasAlbumArt()) {
            result.setAlbumArtUrl(dz.getAlbumArtUrl());
            result.setAlbumArtSize(dz.getAlbumArtSize());
            result.addSource("Deezer (Art)");
        }

        // Merge additional genres
        if (dz.hasGenres()) {
            result.addAllGenres(dz.getGenres());
            result.addSource("Deezer (Genres)");
        }
    }

    /**
     * Merges TheAudioDB result into the main result.
     */
    private void mergeTheAudioDbResult(EnhancedMetadataResult result, TheAudioDbData adb) {
        if (adb.hasAlbumArt()) {
            result.setAlbumArtUrl(adb.getAlbumArtUrl());
            result.setAlbumArtSize(adb.getAlbumArtSize());
            result.addSource("TheAudioDB (Art)");
        }
    }

    // Data parsing methods with enhanced duration extraction
    private MusicBrainzData parseMusicBrainzResult(JsonNode track) {
        MusicBrainzData result = new MusicBrainzData();
        result.setTitle(track.path("title").asText());
        result.setAlbum(track.path("release").path(0).path("title").asText());

        JsonNode artist = track.path("artist-credit");
        if (artist.isArray() && artist.size() > 0) {
            result.setArtist(artist.get(0).path("name").asText());
        }

        // Extract release date
        JsonNode release = track.path("release");
        if (!release.isMissingNode()) {
            JsonNode date = release.path(0).path("date");
            if (!date.isMissingNode()) {
                String dateStr = date.asText();
                if (dateStr.length() >= 4) {
                    result.setReleaseDate(dateStr.substring(0, 4));
                }
            }
        }

        // Extract duration in milliseconds
        JsonNode duration = track.path("length");
        if (!duration.isMissingNode() && duration.asLong() > 0) {
            result.setDuration(duration.asLong());
            LOGGER.debug("Extracted duration from MusicBrainz: {}ms for track: {}",
                    duration.asLong(), result.getTitle());
        }

        // Extract track number
        JsonNode trackNumber = track.path("number");
        if (!trackNumber.isMissingNode()) {
            try {
                result.setTrackNumber(Integer.parseInt(trackNumber.asText()));
            } catch (NumberFormatException e) {
                // Ignore if not a valid number
            }
        }

        return result;
    }

    private DeezerData parseDeezerResult(JsonNode track) {
        DeezerData result = new DeezerData();
        result.setTitle(track.path("title").asText());
        result.setAlbum(track.path("album").path("title").asText());

        JsonNode artist = track.path("artist");
        if (artist.isArray() && artist.size() > 0) {
            result.setArtist(artist.get(0).path("name").asText());
        }

        // Extract album art
        JsonNode album = track.path("album");
        if (!album.isMissingNode()) {
            JsonNode cover = album.path("cover");
            if (!cover.isMissingNode()) {
                result.setAlbumArtUrl(cover.asText());
                result.setAlbumArtSize("medium");
            }
        }

        return result;
    }

    private TheAudioDbData parseTheAudioDbResult(JsonNode track) {
        TheAudioDbData result = new TheAudioDbData();
        result.setTitle(track.path("strTrack").asText());
        result.setAlbum(track.path("strAlbum").asText());
        result.setArtist(track.path("strArtist").asText());

        JsonNode albumThumb = track.path("strAlbumThumb");
        if (!albumThumb.isMissingNode() && !albumThumb.asText().isEmpty()) {
            result.setAlbumArtUrl(albumThumb.asText());
            result.setAlbumArtSize("medium");
        }

        return result;
    }

    private boolean isUnknown(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim().toLowerCase();
        return v.isEmpty()
                || v.equals("unknown")
                || v.equals("unknown artist")
                || v.equals("unknown title")
                || v.equals("various artists");
    }

    private boolean isUseful(MusicBrainzData mb) {
        boolean hasBasicInfo = !isUnknown(mb.artist)
                || !isUnknown(mb.title)
                || (mb.releaseDate != null && !mb.releaseDate.isEmpty())
                || (mb.album != null && !mb.album.isEmpty());

        boolean hasDuration = mb.getDuration() != null && mb.getDuration() > 0;

        if (hasDuration) {
            LOGGER.debug("MusicBrainz result has duration: {}ms - increasing confidence", mb.getDuration());
        }

        return hasBasicInfo || hasDuration;
    }

    // Enhanced result class and enums
    public enum ApiResult {
        SUCCESS, TIMEOUT, UNAVAILABLE, PARSE_ERROR, RATE_LIMIT, NO_DATA, UNKNOWN_ERROR;

        public static ApiResult fromException(Exception e) {
            if (e.getCause() instanceof ApiTimeoutException) {
                return TIMEOUT;
            }
            if (e.getCause() instanceof ApiUnavailableException) {
                return UNAVAILABLE;
            }
            if (e.getCause() instanceof ApiParseException) {
                return PARSE_ERROR;
            }
            if (e.getCause() instanceof ApiRateLimitException) {
                return RATE_LIMIT;
            }

            // Direct exceptions
            if (e instanceof SocketTimeoutException) {
                return TIMEOUT;
            }
            if (e instanceof ConnectException) {
                return UNAVAILABLE;
            }
            if (e instanceof IOException) {
                return PARSE_ERROR;
            }

            return UNKNOWN_ERROR;
        }
    }

    // Enhanced result class with detailed tracking
    @Data
    public static class EnhancedMetadataResult {

        // Original values before enrichment
        private String originalArtist;
        private String originalTitle;

        private String artist;
        private String title;
        private String album;
        private String releaseDate;
        private Integer trackNumber;
        private String albumArtUrl;
        private String albumArtSize;
        private List<String> genres = new ArrayList<>();
        private Set<String> sources = new HashSet<>();
        private double confidence = 0.0;
        private long processingTimeMs;
        private boolean fallbackUsed = false;
        private String lastError;
        private Map<String, ApiResult> apiResults = new HashMap<>();
        private Map<String, Long> apiResponseTimes = new HashMap<>();

        // Getters and Setters for EnhancedMetadataResult
        public String getOriginalArtist() {
            return originalArtist;
        }

        public void setOriginalArtist(String originalArtist) {
            this.originalArtist = originalArtist;
        }

        public String getOriginalTitle() {
            return originalTitle;
        }

        public void setOriginalTitle(String originalTitle) {
            this.originalTitle = originalTitle;
        }

        public String getArtist() {
            return artist;
        }

        public void setArtist(String artist) {
            this.artist = artist;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getAlbum() {
            return album;
        }

        public void setAlbum(String album) {
            this.album = album;
        }

        public String getReleaseDate() {
            return releaseDate;
        }

        public void setReleaseDate(String releaseDate) {
            this.releaseDate = releaseDate;
        }

        public Integer getTrackNumber() {
            return trackNumber;
        }

        public void setTrackNumber(Integer trackNumber) {
            this.trackNumber = trackNumber;
        }

        public String getAlbumArtUrl() {
            return albumArtUrl;
        }

        public void setAlbumArtUrl(String albumArtUrl) {
            this.albumArtUrl = albumArtUrl;
        }

        public String getAlbumArtSize() {
            return albumArtSize;
        }

        public void setAlbumArtSize(String albumArtSize) {
            this.albumArtSize = albumArtSize;
        }

        public List<String> getGenres() {
            return genres;
        }

        public void setGenres(List<String> genres) {
            this.genres = genres;
        }

        public Set<String> getSources() {
            return sources;
        }

        public void setSources(Set<String> sources) {
            this.sources = sources;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public void setProcessingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
        }

        public boolean isFallbackUsed() {
            return fallbackUsed;
        }

        public void setFallbackUsed(boolean fallbackUsed) {
            this.fallbackUsed = fallbackUsed;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }

        public Map<String, ApiResult> getApiResults() {
            return apiResults;
        }

        public void setApiResults(Map<String, ApiResult> apiResults) {
            this.apiResults = apiResults;
        }

        public Map<String, Long> getApiResponseTimes() {
            return apiResponseTimes;
        }

        public void setApiResponseTimes(Map<String, Long> apiResponseTimes) {
            this.apiResponseTimes = apiResponseTimes;
        }

        public void addSource(String source) {
            this.sources.add(source);
        }

        public void addAllGenres(List<String> newGenres) {
            if (newGenres != null) {
                this.genres.addAll(newGenres);
            }
        }

        public void addApiResult(String api, ApiResult result, long responseTime) {
            this.apiResults.put(api, result);
            this.apiResponseTimes.put(api, responseTime);
        }

        public boolean hasAlbumArt() {
            return albumArtUrl != null && !albumArtUrl.isEmpty();
        }

        public boolean hasGenres() {
            return !genres.isEmpty();
        }

        public boolean isEnriched() {
            return hasAlbumArt()
                    || hasGenres()
                    || (releaseDate != null && !releaseDate.isEmpty())
                    || (album != null && !album.isEmpty())
                    || improved(originalArtist, artist)
                    || improved(originalTitle, title);
        }

        public void deduplicateGenres() {
            Set<String> uniqueGenres = new LinkedHashSet<>(genres);
            genres.clear();
            genres.addAll(uniqueGenres);
        }

        public List<String> getSuccessfulSources() {
            return apiResults.entrySet().stream()
                    .filter(entry -> entry.getValue() == ApiResult.SUCCESS)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        public List<String> getFailedSources() {
            return apiResults.entrySet().stream()
                    .filter(entry -> entry.getValue() != ApiResult.SUCCESS && entry.getValue() != ApiResult.NO_DATA)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private boolean improved(String original, String current) {
            return isUnknown(original)
                    && !isUnknown(current);
        }

        private boolean isUnknown(String value) {
            if (value == null) {
                return true;
            }
            String v = value.trim().toLowerCase();
            return v.isEmpty()
                    || v.equals("unknown")
                    || v.equals("unknown artist")
                    || v.equals("unknown title")
                    || v.equals("various artists");
        }

    }

    // Data classes
    @Data
    public static class MusicBrainzData {

        private String artist;
        private String title;
        private String album;
        private String releaseDate;
        private Integer trackNumber;
        private Long duration; // Add duration in milliseconds
        private List<String> genres = new ArrayList<>();
    }

    @Data
    public static class DeezerData {

        private String artist;
        private String title;
        private String album;
        private String albumArtUrl;
        private String albumArtSize;
        private List<String> genres = new ArrayList<>();

        public boolean hasAlbumArt() {
            return albumArtUrl != null && !albumArtUrl.isEmpty();
        }

        public boolean hasGenres() {
            return !genres.isEmpty();
        }
    }

    @Data
    public static class TheAudioDbData {

        private String artist;
        private String title;
        private String album;
        private String albumArtUrl;
        private String albumArtSize;

        public boolean hasAlbumArt() {
            return albumArtUrl != null && !albumArtUrl.isEmpty();
        }
    }

}
