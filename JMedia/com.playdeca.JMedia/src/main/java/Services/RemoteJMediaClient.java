package Services;

import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class RemoteJMediaClient {

    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int READ_TIMEOUT_SECONDS = 60;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RemoteJMediaClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public SyncExchangeResponse exchange(String baseUrl, String apiKey, SyncExchangeRequest request)
            throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/api/sync/exchange";
        String jsonRequest = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-JMedia-Sync-Key", apiKey)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            throw new SecurityException("Authentication failed — invalid API key for remote server");
        }
        if (response.statusCode() == 404) {
            throw new Exception("Sync endpoint not found on remote server (404) — check server version");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new Exception("Remote server returned HTTP " + response.statusCode()
                    + ": " + truncateBody(response.body()));
        }

        return objectMapper.readValue(response.body(), SyncExchangeResponse.class);
    }

    public boolean checkConnection(String baseUrl, String apiKey) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/api/sync/ping";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-JMedia-Sync-Key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncateBody(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

}
