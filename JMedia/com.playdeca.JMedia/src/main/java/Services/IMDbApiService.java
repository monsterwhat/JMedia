package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class IMDbApiService {

    private static final Logger LOG = LoggerFactory.getLogger(IMDbApiService.class);
    
    private static final String IMDB_API_BASE = "https://api.imdbapi.dev";
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<String> findShowImdbId(String seriesTitle) {
        if (seriesTitle == null || seriesTitle.isBlank()) return Optional.empty();
        
        try {
            String query = URLEncoder.encode(seriesTitle, StandardCharsets.UTF_8);
            String url = IMDB_API_BASE + "/search/titles?q=" + query;
            
            LOG.info("Searching IMDb for series: {}", seriesTitle);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    // Find first result that is a TV series or has similar title
                    for (JsonNode result : results) {
                        String type = result.path("type").asText();
                        if ("tvSeries".equalsIgnoreCase(type) || "tvMiniSeries".equalsIgnoreCase(type)) {
                            String id = result.path("id").asText();
                            LOG.info("Found IMDb ID {} for series: {}", id, seriesTitle);
                            return Optional.of(id);
                        }
                    }
                    // Fallback to first result if no TV series found specifically
                    String id = results.get(0).path("id").asText();
                    return Optional.of(id);
                }
            } else {
                LOG.warn("IMDb API search returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.error("Error searching IMDb for {}: {}", seriesTitle, e.getMessage());
        }
        return Optional.empty();
    }
}
