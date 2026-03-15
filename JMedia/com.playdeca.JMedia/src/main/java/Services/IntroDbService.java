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
    
    // Source 1: Newest segments endpoint
    private static final String API_URL_SEGMENTS = "https://api.introdb.app/segments";
    
    // Source 2: OpenAPI v2 media endpoint
    private static final String API_URL_MEDIA = "https://api.theintrodb.org/v2/media";
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class Timestamps {
        public Double start;
        public Double end;
    }

    public static class MediaMetadata {
        public Optional<Timestamps> intro = Optional.empty();
        public Optional<Timestamps> recap = Optional.empty();
        public Optional<Timestamps> outro = Optional.empty(); 
    }

    /**
     * Attempts to fetch metadata from all available IntroDB sources.
     * Tries the /segments endpoint first, then falls back to /media.
     */
    public Optional<MediaMetadata> fetchAllMetadata(String imdbId, int season, int episode) {
        if (imdbId == null || imdbId.isBlank()) return Optional.empty();

        // 1. Try Source 1 (/segments)
        LOG.info("IntroDB: Attempting fetch from /segments for {} S{}E{}", imdbId, season, episode);
        Optional<MediaMetadata> result = executeFetch(API_URL_SEGMENTS, imdbId, season, episode);
        
        // 2. Fallback to Source 2 (/media) if Source 1 found nothing
        if (result.isEmpty()) {
            LOG.info("IntroDB: No data in /segments, falling back to /media for {} S{}E{}", imdbId, season, episode);
            result = executeFetch(API_URL_MEDIA, imdbId, season, episode);
        }

        return result;
    }

    private Optional<MediaMetadata> executeFetch(String baseUrl, String imdbId, int season, int episode) {
        try {
            String url = String.format("%s?imdb_id=%s&season=%d&episode=%d", 
                    baseUrl, imdbId, season, episode);
            
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                MediaMetadata metadata = new MediaMetadata();
                
                metadata.intro = parseSegment(root.path("intro"));
                metadata.recap = parseSegment(root.path("recap"));
                metadata.outro = parseSegment(root.path("credits"));
                
                if (metadata.intro.isPresent() || metadata.recap.isPresent() || metadata.outro.isPresent()) {
                    LOG.info("IntroDB: Successfully retrieved data from {}", baseUrl);
                    return Optional.of(metadata);
                }
            }
        } catch (Exception e) {
            LOG.warn("IntroDB: Request to {} failed: {}", baseUrl, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<Timestamps> parseSegment(JsonNode segmentArray) {
        if (segmentArray != null && segmentArray.isArray() && segmentArray.size() > 0) {
            JsonNode first = segmentArray.get(0);
            Timestamps ts = new Timestamps();
            
            // Check for milliseconds first
            if (first.has("start_ms") && !first.get("start_ms").isNull()) {
                ts.start = first.get("start_ms").asDouble() / 1000.0;
            } else if (first.has("start_sec") && !first.get("start_sec").isNull()) {
                ts.start = first.get("start_sec").asDouble();
            }
            
            if (first.has("end_ms") && !first.get("end_ms").isNull()) {
                ts.end = first.get("end_ms").asDouble() / 1000.0;
            } else if (first.has("end_sec") && !first.get("end_sec").isNull()) {
                ts.end = first.get("end_sec").asDouble();
            }
            
            // Only return if we actually got values
            if (ts.start != null || ts.end != null) {
                return Optional.of(ts);
            }
        }
        return Optional.empty();
    }

    public Optional<Timestamps> fetchIntro(String showImdbId, int season, int episode) {
        return fetchAllMetadata(showImdbId, season, episode).flatMap(m -> m.intro);
    }

    public Optional<Timestamps> fetchOutro(String showImdbId, int season, int episode) {
        return fetchAllMetadata(showImdbId, season, episode).flatMap(m -> m.outro);
    }

    public Optional<Timestamps> fetchRecap(String showImdbId, int season, int episode) {
        return fetchAllMetadata(showImdbId, season, episode).flatMap(m -> m.recap);
    }
}
