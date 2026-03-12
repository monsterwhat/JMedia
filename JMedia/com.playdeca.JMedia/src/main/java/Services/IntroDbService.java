package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class IntroDbService {

    private static final Logger LOG = LoggerFactory.getLogger(IntroDbService.class);
    
    private static final String INTRODB_API_BASE = "https://api.introdb.app";
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class Timestamps {
        public Double start;
        public Double end;
    }

    public Optional<Timestamps> fetchIntro(String showImdbId, int season, int episode) {
        return fetchTimestamps("intro", showImdbId, season, episode);
    }

    public Optional<Timestamps> fetchOutro(String showImdbId, int season, int episode) {
        return fetchTimestamps("outro", showImdbId, season, episode);
    }

    public Optional<Timestamps> fetchRecap(String showImdbId, int season, int episode) {
        return fetchTimestamps("recap", showImdbId, season, episode);
    }

    private Optional<Timestamps> fetchTimestamps(String type, String showImdbId, int season, int episode) {
        if (showImdbId == null || showImdbId.isBlank()) return Optional.empty();
        
        try {
            String url = String.format("%s/%s?imdb=%s&season=%d&episode=%d", 
                    INTRODB_API_BASE, type, showImdbId, season, episode);
            
            LOG.info("Fetching {} from IntroDB for: {} S{:02d}E{:02d}", type, showImdbId, season, episode);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.has("start") && root.has("end")) {
                    Timestamps ts = new Timestamps();
                    ts.start = root.get("start").asDouble();
                    ts.end = root.get("end").asDouble();
                    return Optional.of(ts);
                }
            } else if (response.statusCode() == 404) {
                LOG.info("No {} data found on IntroDB for: {} S{:02d}E{:02d}", type, showImdbId, season, episode);
            } else {
                LOG.warn("IntroDB API returned status {} for {}: {}", response.statusCode(), type, response.body());
            }
        } catch (Exception e) {
            LOG.error("Error fetching {} from IntroDB: {}", type, e.getMessage());
        }
        return Optional.empty();
    }
}
