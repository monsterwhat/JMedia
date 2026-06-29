package Services;

import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class RemoteJMediaClient {

    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int READ_TIMEOUT_SECONDS = 60;

    @Inject
    LoggingService log;

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
        String pingUrl = baseUrl.replaceAll("/+$", "") + "/api/sync/ping";
        log.addLog("Testing connection to " + pingUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pingUrl))
                    .header("X-JMedia-Sync-Key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.addLog("Connection test succeeded for " + pingUrl);
                return true;
            } else {
                log.addLog("Connection test to " + pingUrl + " returned HTTP " + response.statusCode());
                return false;
            }
        } catch (java.net.UnknownHostException e) {
            log.addLog("Connection test failed — unknown host: " + pingUrl, e);
            return false;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.addLog("Connection test failed — timed out after " + CONNECT_TIMEOUT_SECONDS + "s: " + pingUrl, e);
            return false;
        } catch (java.net.ConnectException e) {
            log.addLog("Connection test failed — connection refused: " + pingUrl, e);
            return false;
        } catch (javax.net.ssl.SSLException e) {
            log.addLog("Connection test failed — SSL/TLS error: " + pingUrl, e);
            return false;
        } catch (Exception e) {
            log.addLog("Connection test failed for " + pingUrl + " — " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            return false;
        }
    }

    private String truncateBody(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

}
