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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RemoteJMediaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteJMediaClient.class);

    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int READ_TIMEOUT_SECONDS = 300;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RemoteJMediaClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
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
            throw new SecurityException("Authentication failed - invalid API key for remote server");
        }
        if (response.statusCode() == 404) {
            throw new Exception("Sync endpoint not found on remote server (404) - check server version");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            String detail = "";
            if (body != null && !body.isBlank()) {
                try {
                    var json = objectMapper.readTree(body);
                    if (json.has("details")) {
                        detail = " details=" + json.get("details").asText();
                    } else if (json.has("error")) {
                        detail = " error=" + json.get("error").asText();
                    } else if (json.has("message")) {
                        detail = " message=" + json.get("message").asText();
                    }
                } catch (Exception ignored) {
                    detail = " body=" + truncateBody(body);
                }
            }
            throw new Exception("Remote server returned HTTP " + response.statusCode()
                    + " for url=" + truncateUrl(url) + detail);
        }

        return objectMapper.readValue(response.body(), SyncExchangeResponse.class);
    }

    public boolean checkConnection(String baseUrl, String apiKey) {
        String pingUrl = baseUrl.replaceAll("/+$", "") + "/api/sync/ping";
        LOGGER.info("Testing connection to " + pingUrl);
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
                LOGGER.info("Connection test succeeded for " + pingUrl);
                return true;
            } else {
                LOGGER.info("Connection test to " + pingUrl + " returned HTTP " + response.statusCode());
                return false;
            }
        } catch (java.net.UnknownHostException e) {
            LOGGER.error("Connection test failed — unknown host: " + pingUrl, e);
            return false;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            LOGGER.error("Connection test failed — timed out after " + CONNECT_TIMEOUT_SECONDS + "s: " + pingUrl, e);
            return false;
        } catch (java.net.ConnectException e) {
            LOGGER.error("Connection test failed — connection refused: " + pingUrl, e);
            return false;
        } catch (javax.net.ssl.SSLException e) {
            LOGGER.error("Connection test failed — SSL/TLS error: " + pingUrl, e);
            return false;
        } catch (Exception e) {
            LOGGER.error("Connection test failed for " + pingUrl, e);
            return false;
        }
    }

    private String truncateBody(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private String truncateUrl(String url) {
        if (url == null) return "null";
        // Strip query params and truncate to keep the origin + path readable
        int queryIdx = url.indexOf('?');
        String clean = queryIdx > 0 ? url.substring(0, queryIdx) : url;
        return clean.length() > 100 ? clean.substring(0, 100) + "..." : clean;
    }

}
