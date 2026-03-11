package Services;

import Models.Video;
import Models.SubtitleTrack;
import Models.DTOs.SubtitleSearchResult;
import Models.Settings;
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

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<SubtitleSearchResult> searchSubtitles(Video video, String language) throws Exception {
        Settings settings = settingsService.getOrCreateSettings();
        String apiKey = settings.getOpenSubtitlesApiKey();
        
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("OpenSubtitles API key is missing. Cannot search for subtitles.");
            return new ArrayList<>();
        }

        StringBuilder urlBuilder = new StringBuilder(OPENSUBTITLES_API_BASE + "/subtitles?");
        
        // Prefer IMDb ID for accuracy
        if (video.imdbId != null && !video.imdbId.isBlank()) {
            String cleanImdbId = video.imdbId.replace("tt", "");
            urlBuilder.append("imdb_id=").append(cleanImdbId);
        } else if ("episode".equalsIgnoreCase(video.type)) {
            urlBuilder.append("query=").append(URLEncoder.encode(video.seriesTitle, StandardCharsets.UTF_8));
            if (video.seasonNumber != null) urlBuilder.append("&season_number=").append(video.seasonNumber);
            if (video.episodeNumber != null) urlBuilder.append("&episode_number=").append(video.episodeNumber);
        } else {
            urlBuilder.append("query=").append(URLEncoder.encode(video.title, StandardCharsets.UTF_8));
            if (video.releaseYear != null) urlBuilder.append("&year=").append(video.releaseYear);
        }

        if (language != null && !language.isBlank()) {
            urlBuilder.append("&languages=").append(language);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            LOG.error("OpenSubtitles search failed: " + response.body());
            throw new IOException("Search failed with status " + response.statusCode());
        }

        return parseSearchResults(response.body());
    }

    private List<SubtitleSearchResult> parseSearchResults(String json) throws Exception {
        List<SubtitleSearchResult> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.get("data");

        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                JsonNode attributes = item.get("attributes");
                JsonNode file = attributes.get("files").get(0);
                
                SubtitleSearchResult result = new SubtitleSearchResult();
                result.id = file.get("file_id").asText();
                result.filename = file.get("file_name").asText();
                result.language = attributes.get("language").asText();
                result.rating = attributes.get("ratings").asDouble();
                result.downloadCount = attributes.get("download_count").asInt();
                result.isForced = attributes.get("forced").asBoolean();
                result.isSDH = attributes.get("sdh").asBoolean();
                result.format = attributes.get("format") != null ? attributes.get("format").asText() : "srt";
                
                results.add(result);
            }
        }
        return results;
    }

    public CompletableFuture<String> downloadSubtitle(Video video, String fileId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Settings settings = settingsService.getOrCreateSettings();
                String apiKey = settings.getOpenSubtitlesApiKey();
                
                if (apiKey == null || apiKey.isBlank()) {
                    throw new RuntimeException("OpenSubtitles API key is missing");
                }

                // 1. Request download link
                String downloadRequestJson = "{\"file_id\": " + fileId + "}";
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(OPENSUBTITLES_API_BASE + "/download"))
                        .header("Api-Key", apiKey)
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(downloadRequestJson))
                        .build();

                HttpResponse<String> response = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    throw new IOException("Download link request failed: " + response.body());
                }

                JsonNode downloadNode = objectMapper.readTree(response.body());
                String downloadUrl = downloadNode.get("link").asText();
                String filename = downloadNode.get("file_name").asText();

                // 2. Actually download the file
                HttpRequest fileRequest = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build();
                HttpResponse<Path> fileResponse = httpClient.send(fileRequest, 
                        HttpResponse.BodyHandlers.ofFile(Paths.get(video.path).getParent().resolve(filename)));

                LOG.info("Downloaded subtitle to: " + fileResponse.body());
                
                // 3. Refresh subtitle tracks for the video
                refreshSubtitleTracks(video);

                return "Subtitle downloaded successfully: " + filename;
                
            } catch (Exception e) {
                LOG.error("Error downloading subtitle", e);
                throw new RuntimeException("Download failed: " + e.getMessage());
            }
        });
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
