package com.playdeca.jmedia.service;

import jakarta.enterprise.context.ApplicationScoped; 
import java.io.IOException;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Health check service for metadata APIs.
 */
@ApplicationScoped
@Liveness
@Readiness
public class MetadataApiHealthCheck implements HealthCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataApiHealthCheck.class);
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private final Executor executor = Executors.newCachedThreadPool();
    
    // API endpoints for health checks
    private static final String MUSICBRAINZ_HEALTH_URL = "https://musicbrainz.org/";
    private static final String DEEZER_HEALTH_URL = "https://api.deezer.com/";
    private static final String THEAUDIODB_HEALTH_URL = "https://www.theaudiodb.com/";
    
    @Override
    public HealthCheckResponse call() {
        LOGGER.debug("Performing metadata API health checks");
        
        try {
            CompletableFuture<Boolean> musicbrainzHealth = checkApiAsync("MusicBrainz", MUSICBRAINZ_HEALTH_URL);
            CompletableFuture<Boolean> deezerHealth = checkApiAsync("Deezer", DEEZER_HEALTH_URL);
            CompletableFuture<Boolean> theAudioDbHealth = checkApiAsync("TheAudioDB", THEAUDIODB_HEALTH_URL);
            
            // Wait for all checks to complete
            CompletableFuture.allOf(musicbrainzHealth, deezerHealth, theAudioDbHealth).join();
            
            boolean musicbrainzOk = musicbrainzHealth.join();
            boolean deezerOk = deezerHealth.join();
            boolean theAudioDbOk = theAudioDbHealth.join();
            
            if (musicbrainzOk && deezerOk && theAudioDbOk) {
                return HealthCheckResponse.named("Metadata APIs")
                        .up()
                        .withData("MusicBrainz", "UP")
                        .withData("Deezer", "UP")
                        .withData("TheAudioDB", "UP")
                        .withData("overall", "ALL_UP")
                        .build();
            } else {
                var response = HealthCheckResponse.named("Metadata APIs")
                        .down()
                        .withData("MusicBrainz", musicbrainzOk ? "UP" : "DOWN")
                        .withData("Deezer", deezerOk ? "UP" : "DOWN")
                        .withData("TheAudioDB", theAudioDbOk ? "UP" : "DOWN");
                
                int upCount = (musicbrainzOk ? 1 : 0) + (deezerOk ? 1 : 0) + (theAudioDbOk ? 1 : 0);
                String overall = switch (upCount) {
                    case 3 -> "ALL_UP";
                    case 2 -> "PARTIAL_DEGRADED";
                    case 1 -> "SEVERELY_DEGRADED";
                    default -> "ALL_DOWN";
                };
                response.withData("overall", overall);
                response.withData("available_apis", upCount);
                response.withData("total_apis", 3);
                
                return response.build();
            }
            
        } catch (Exception e) {
            LOGGER.error("Health check failed", e);
            return HealthCheckResponse.named("Metadata APIs")
                    .down()
                    .withData("error", e.getMessage())
                    .withData("overall", "HEALTH_CHECK_ERROR")
                    .build();
        }
    }
    
    /**
     * Checks an API endpoint asynchronously.
     */
    private CompletableFuture<Boolean> checkApiAsync(String apiName, String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.debug("Checking health of {} API", apiName);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("User-Agent", "JMedia/1.0 (Health-Check)")
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
                
                boolean isHealthy = response.statusCode() >= 200 && response.statusCode() < 400;
                LOGGER.debug("{} API health check: {} (status: {})", 
                    apiName, isHealthy ? "UP" : "DOWN", response.statusCode());
                
                return isHealthy;
                
            } catch (IOException | InterruptedException e) {
                LOGGER.debug("{} API health check failed: {}", apiName, e.getMessage());
                return false;
            }
        }, executor);
    }
}