package Services;

import Models.Settings;
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
import java.util.Optional;

@ApplicationScoped
public class VideoMetadataService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoMetadataService.class);
    
    @Inject
    SettingsService settingsService;

    // TMDb
    private static final String TMDB_SEARCH_MOVIE = "https://api.themoviedb.org/3/search/movie?api_key=%s&query=%s";
    private static final String TMDB_SEARCH_TV = "https://api.themoviedb.org/3/search/tv?api_key=%s&query=%s";
    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    
    // TVMaze (Free, no key)
    private static final String TVMAZE_SEARCH = "https://api.tvmaze.com/singlesearch/shows?q=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String getApiKey() {
        String key = settingsService.getOrCreateSettings().getTmdbApiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("TMDB_API_KEY");
        }
        return key;
    }

    public Optional<String> fetchPosterUrl(String type, String title, Integer year) {
        if (title == null || title.isBlank()) return Optional.empty();
        
        // Deep clean for search: ensure all common tags are stripped
        String cleanSearchTitle = title.replaceAll("(?i)\\b(yts\\.?mx|yts\\.?am|yify|imax|hybrid|1080p|720p|2160p|4k|bluray|brrip|dvdrip|web-dl|webrip|hdtv|x264|x265|hevc|aac|ac3|dts|5\\s*1|7\\s*1|galaxyrg|neonoir|mzabi)\\b", "")
                                      .replaceAll("[._\\-\\[\\]\\(\\)]+", " ")
                                      .replaceAll("\\s+", " ")
                                      .trim();

        if ("episode".equalsIgnoreCase(type)) {
            Optional<String> tvmazePoster = fetchTVMazePoster(cleanSearchTitle);
            if (tvmazePoster.isPresent()) return tvmazePoster;
        }

        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("No TMDb API key provided. Skipping TMDb search for: {}", cleanSearchTitle);
            return Optional.empty();
        }

        // Try TMDb with year first
        Optional<String> result = fetchTmdbPoster(type, cleanSearchTitle, year);
        
        // If no result and we had a year, try again without the year (broader search)
        if (result.isEmpty() && year != null) {
            LOG.info("No match with year {}, retrying broader search for: {}", year, cleanSearchTitle);
            result = fetchTmdbPoster(type, cleanSearchTitle, null);
        }

        return result;
    }

    private Optional<String> fetchTVMazePoster(String title) {
        try {
            String query = URLEncoder.encode(title, StandardCharsets.UTF_8);
            String url = String.format(TVMAZE_SEARCH, query);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String poster = root.path("image").path("original").asText();
                if (poster != null && !poster.isEmpty()) {
                    LOG.info("Found poster on TVMaze for: {}", title);
                    return Optional.of(poster);
                }
            }
        } catch (Exception e) {
            LOG.warn("TVMaze fetch failed for {}: {}", title, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> fetchTmdbPoster(String type, String title, Integer year) {
        String apiKey = getApiKey();
        try {
            String query = URLEncoder.encode(title, StandardCharsets.UTF_8);
            String url = "episode".equalsIgnoreCase(type) ? 
                String.format(TMDB_SEARCH_TV, apiKey, query) :
                String.format(TMDB_SEARCH_MOVIE, apiKey, query);
            
            if (year != null) {
                url += "episode".equalsIgnoreCase(type) ? "&first_air_date_year=" + year : "&year=" + year;
            }

            LOG.info("TMDb Request: {}", url.replace(apiKey, "HIDDEN"));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    String posterPath = results.get(0).path("poster_path").asText();
                    if (posterPath != null && !posterPath.equals("null") && !posterPath.isEmpty()) {
                        LOG.info("Found poster on TMDb for: {}", title);
                        return Optional.of(TMDB_IMAGE_BASE + posterPath);
                    }
                }
            } else {
                LOG.warn("TMDb API returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.error("Error fetching poster from TMDb: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
