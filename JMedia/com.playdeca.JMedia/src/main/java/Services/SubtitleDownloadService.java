package Services;

import Models.Video.Video;
import Models.Video.SubtitleTrack;
import Models.DTOs.SubtitleSearchResult;
import Models.Settings.Settings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SubtitleDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleDownloadService.class);
    private static final String OPENSUBTITLES_API_BASE = "https://api.opensubtitles.com/api/v1";
    private static final String USER_AGENT = "JMedia v1.0";

    @Inject
    SettingsService settingsService;
    
    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;
    
    @Inject
    VideoService videoService;

    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cached OpenSubtitles JWT (volatile for thread-safety); null when anonymous
    private volatile String cachedToken;

    public List<SubtitleSearchResult> searchSubtitles(Video video, String language, String userQuery) throws Exception {
        Settings settings = settingsService.getOrCreateSettings();
        if (!Boolean.TRUE.equals(settings.getOpenSubtitlesEnabled())) {
            LOG.info("OpenSubtitles is disabled in settings, skipping search");
            return new ArrayList<>();
        }

        String apiKey = settings.getOpenSubtitlesApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOG.error("OpenSubtitles API key not configured");
            throw new RuntimeException("OpenSubtitles API key not configured — add it in Settings");
        }

        // OpenSubtitles.com API accepts 2-letter language codes directly
        String osLanguage = (language != null && !language.isBlank()) ? language.toLowerCase() : "en";

        // Use user-provided query if available, otherwise fall back to video title
        String searchQuery;
        if (userQuery != null && !userQuery.isBlank()) {
            searchQuery = userQuery;
            LOG.info("Searching OpenSubtitles.com for user query: " + searchQuery + " in language: " + osLanguage);
        } else {
            searchQuery = video.type.equalsIgnoreCase("episode") ? video.seriesTitle : video.title;
            LOG.info("Searching OpenSubtitles.com for video: " + video.title + " in language: " + osLanguage);
        }

        // Build query parameters
        StringBuilder params = new StringBuilder("query=").append(URLEncoder.encode(searchQuery, StandardCharsets.UTF_8));
        params.append("&languages=").append(URLEncoder.encode(osLanguage, StandardCharsets.UTF_8));
        params.append("&type=all");

        // Add IMDb ID if available (strip 'tt' prefix) - only if not using custom query
        if ((userQuery == null || userQuery.isBlank()) && video.imdbId != null && !video.imdbId.isBlank()) {
            params.append("&imdb_id=").append(video.imdbId.replace("tt", ""));
        }

        String searchUrl = OPENSUBTITLES_API_BASE + "/subtitles?" + params;
        LOG.info("OpenSubtitles Search URL: " + searchUrl);

        HttpResponse<String> response = sendWithAuthRetry(() -> newApiRequestBuilder(settings, "/subtitles?" + params).GET(), settings);

        if (response.statusCode() != 200) {
            LOG.error("OpenSubtitles search failed with status " + response.statusCode() + ": " + response.body());
            return new ArrayList<>();
        }

        return parseApiSearchResults(response.body(), osLanguage);
    }

    public String mapToThreeLetterLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "all";
        
        // Handle explicit SPL request from user
        if (lang.equalsIgnoreCase("spl")) return "spl";
        
        if (lang.length() == 3) return lang.toLowerCase();
        
        // Common mappings for 2-letter to 3-letter codes
        return switch (lang.toLowerCase()) {
            case "en" -> "eng";
            case "es" -> "spa";
            case "fr" -> "fre";
            case "de" -> "deu";
            case "it" -> "ita";
            case "pt" -> "por";
            case "ru" -> "rus";
            case "ja" -> "jpn";
            case "ko" -> "kor";
            case "zh" -> "chi";
            default -> lang;
        };
    }

    private List<SubtitleSearchResult> parseApiSearchResults(String jsonBody, String searchLang) throws Exception {
        List<SubtitleSearchResult> results = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) {
                LOG.warn("OpenSubtitles search response contained no data array");
                return results;
            }

            for (JsonNode node : data) {
                JsonNode attributes = node.path("attributes");
                if (!attributes.isObject()) continue;

                SubtitleSearchResult result = new SubtitleSearchResult();
                result.filename = attributes.path("release_name").asText(null);
                if (result.filename == null || result.filename.isEmpty()) {
                    result.filename = attributes.path("title").asText(null);
                }

                result.language = attributes.path("language").asText(null);
                result.languageCode = searchLang; // Store the code used for the search

                JsonNode ratingNode = attributes.path("rating");
                result.rating = (ratingNode.isNumber()) ? ratingNode.asDouble() : 0.0;

                JsonNode downloadsNode = attributes.path("download_count");
                result.downloadCount = (downloadsNode.isNumber()) ? downloadsNode.asInt() : 0;

                // The download endpoint requires the file_id from the first file entry
                JsonNode files = attributes.path("files");
                if (files.isArray() && files.size() > 0) {
                    JsonNode fileIdNode = files.get(0).path("file_id");
                    result.id = (fileIdNode.isNumber() || fileIdNode.isTextual()) ? fileIdNode.asText() : null;
                } else {
                    result.id = node.path("id").asText(null);
                }

                result.format = attributes.path("subtitle_format").asText(null);
                if (result.format == null || result.format.isBlank()) {
                    result.format = "srt"; // OpenSubtitles standard
                }
                results.add(result);
            }
            LOG.info("Found " + results.size() + " subtitles in API response");
        } catch (Exception e) {
            LOG.error("Failed to parse OpenSubtitles API response: " + e.getMessage());
            throw new IOException("Failed to parse subtitle search results");
        }

        return results;
    }

    private void invalidateToken() {
        cachedToken = null;
    }

    private String getApiToken(Settings settings) {
        String currentToken = cachedToken;
        if (currentToken != null) {
            return currentToken;
        }

        // Synchronize so concurrent requests share a single login
        synchronized (this) {
            if (cachedToken != null) {
                return cachedToken;
            }

            String username = settings.getOpenSubtitlesUsername();
            String password = settings.getOpenSubtitlesPassword();
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                LOG.info("OpenSubtitles login credentials not configured, using anonymous API mode");
                return null;
            }

            LOG.info("Authenticating with OpenSubtitles.com API...");
            try {
                String loginBody = objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", password));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OPENSUBTITLES_API_BASE + "/login"))
                        .header("Api-Key", settings.getOpenSubtitlesApiKey())
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.error("OpenSubtitles login failed with status " + response.statusCode() + ": " + response.body());
                    throw new RuntimeException("OpenSubtitles login failed (HTTP " + response.statusCode() + "). Check the username/password in Settings.");
                }

                JsonNode json = objectMapper.readTree(response.body());
                String token = json.path("token").asText(null);
                if (token == null || token.isBlank()) {
                    throw new RuntimeException("OpenSubtitles login response did not contain a token");
                }

                cachedToken = token;
                return token;
            } catch (IOException e) {
                throw new RuntimeException("Failed to authenticate with OpenSubtitles: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to authenticate with OpenSubtitles: " + e.getMessage(), e);
            }
        }
    }

    private void addAuthHeader(Settings settings, HttpRequest.Builder builder) {
        String token = getApiToken(settings);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    private HttpRequest.Builder newApiRequestBuilder(Settings settings, String pathAndQuery) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(OPENSUBTITLES_API_BASE + pathAndQuery))
                .header("Api-Key", settings.getOpenSubtitlesApiKey())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");
        addAuthHeader(settings, builder);
        return builder;
    }

    private HttpResponse<String> sendWithAuthRetry(java.util.function.Supplier<HttpRequest.Builder> requestFactory, Settings settings) throws Exception {
        HttpResponse<String> response = httpClient.send(requestFactory.get().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            String expiredToken = cachedToken;
            invalidateToken();
            if (expiredToken != null) {
                LOG.info("OpenSubtitles returned 401, refreshing token and retrying once");
                response = httpClient.send(requestFactory.get().build(), HttpResponse.BodyHandlers.ofString());
            }
        }
        return response;
    }

    public String downloadSubtitleWithLang(Video video, String fileId, String lang) {
        try {
            Settings settings = settingsService.getOrCreateSettings();
            if (!Boolean.TRUE.equals(settings.getOpenSubtitlesEnabled())) {
                LOG.info("OpenSubtitles is disabled in settings, skipping download");
                return null;
            }

            String apiKey = settings.getOpenSubtitlesApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                LOG.error("OpenSubtitles API key not configured");
                throw new RuntimeException("OpenSubtitles API key not configured — add it in Settings");
            }

            LOG.info("Downloading subtitle from OpenSubtitles.com with ID: " + fileId + " (" + lang + ")");

            String videoPathStr = video.path;
            int lastSlash = Math.max(videoPathStr.lastIndexOf('/'), videoPathStr.lastIndexOf('\\'));
            int lastDot = videoPathStr.lastIndexOf('.');
            String videoBasename = videoPathStr.substring(lastSlash + 1, lastDot);
            
            // Normalize language code for filename (pass 2-letter codes through directly)
            String fileLang = (lang != null && !lang.isBlank()) ? lang.toLowerCase() : "und";
            
            // Format: videoBasename.lang.os-id.srt
            String filename = videoBasename + "." + fileLang + ".os-" + fileId + ".srt";
            Path targetPath = Paths.get(video.path).getParent().resolve(filename);
            
            LOG.info("Target download path: " + targetPath);
            
            // Check if file already exists to avoid redundant downloads
            if (Files.exists(targetPath)) {
                LOG.info("Subtitle file already exists, skipping download: " + filename);
                refreshSubtitleTracks(video);
                return filename;
            }

            // 1. Request a signed download link from the API
            String downloadBody = objectMapper.writeValueAsString(java.util.Map.of("file_id", fileId));
            HttpResponse<String> downloadResponse = sendWithAuthRetry(() -> newApiRequestBuilder(settings, "/download")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(downloadBody, StandardCharsets.UTF_8)), settings);

            if (downloadResponse.statusCode() != 200) {
                throw new IOException("OpenSubtitles download request failed with status " + downloadResponse.statusCode() + ": " + downloadResponse.body());
            }

            JsonNode downloadJson = objectMapper.readTree(downloadResponse.body());
            String downloadUrl = downloadJson.path("link").asText(null);
            if (downloadUrl == null || downloadUrl.isBlank()) {
                throw new IOException("OpenSubtitles download response did not contain a signed link");
            }
            LOG.info("Obtained signed download link");

            // 2. Fetch the actual subtitle file from the signed URL (follow redirects)
            HttpRequest fileRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Api-Key", apiKey)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            // Using sendAsync to avoid InterruptedException blocking issues and add a timeout
            HttpResponse<Path> fileResponse = httpClient.sendAsync(fileRequest, 
                    HttpResponse.BodyHandlers.ofFile(targetPath))
                    .get(45, java.util.concurrent.TimeUnit.SECONDS);

            // Some CDNs reject the signed link without the API key; retry once with the header
            if (fileResponse.statusCode() == 403) {
                LOG.warn("Signed link returned 403, retrying with Api-Key header");
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .header("User-Agent", USER_AGENT)
                        .header("Api-Key", apiKey)
                        .timeout(java.time.Duration.ofSeconds(30))
                        .GET()
                        .build();
                fileResponse = httpClient.sendAsync(retryRequest,
                        HttpResponse.BodyHandlers.ofFile(targetPath))
                        .get(45, java.util.concurrent.TimeUnit.SECONDS);
            }

            if (fileResponse.statusCode() != 200) {
                Files.deleteIfExists(targetPath);
                throw new IOException("Subtitle file download failed with status " + fileResponse.statusCode());
            }

            LOG.info("Downloaded subtitle to: " + fileResponse.body());
            
            refreshSubtitleTracks(video);
            return filename;
            
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.error("Subtitle download timed out");
            throw new RuntimeException("Download timed out");
        } catch (Exception e) {
            LOG.error("Error downloading subtitle", e);
            throw new RuntimeException("Download failed: " + e.getMessage());
        }
    }

    public String downloadSubtitleSync(Video video, String fileId) {
        return downloadSubtitleWithLang(video, fileId, null);
    }

    public CompletableFuture<String> downloadSubtitle(Video video, String fileId) {
        return CompletableFuture.supplyAsync(() -> downloadSubtitleSync(video, fileId));
    }

    public List<Models.DTOs.LocalSubtitleFile> scanAllSubtitleFiles(Video video) {
        return subtitleMatcher.scanAllSubtitleFiles(Paths.get(video.path), video);
    }
    @Transactional
    public void addLocalSubtitle(Video video, String filePath) {
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new RuntimeException("File does not exist: " + filePath);
        }

        // Check for duplicates
        if (managedVideo.subtitleTracks != null && managedVideo.subtitleTracks.stream().anyMatch(t -> t.fullPath.equals(filePath))) {
            return;
        }

        // Create manual track
        SubtitleTrack track = new SubtitleTrack();
        track.filename = path.getFileName().toString();
        track.fullPath = filePath;
        track.format = getFileExtension(track.filename);
        track.video = managedVideo;
        track.isManual = true;

        // Extract language and metadata using the matcher
        subtitleMatcher.extractLanguageAndTags(track.filename, track);

        // If still no display name, the matcher fix will fallback to filename
        if (track.displayName == null || track.displayName.equals("Unknown")) {
            track.displayName = track.filename + " (Manual)";
        }

        track.persist();
        
        if (managedVideo.subtitleTracks == null) {
            managedVideo.subtitleTracks = new ArrayList<>();
        }
        managedVideo.subtitleTracks.add(track);
        managedVideo.persist();
        
        LOG.info("Manually added subtitle track: " + filePath + " to video: " + managedVideo.title);
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    @Transactional
    public void refreshSubtitleTracks(Video video) {
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        List<SubtitleTrack> tracks = subtitleMatcher.discoverSubtitleTracks(Paths.get(managedVideo.path), managedVideo);
        videoService.updateSubtitleTracks(managedVideo.id, tracks);
        LOG.info("Refreshed subtitle tracks for video: " + managedVideo.title);
    }
}
