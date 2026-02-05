package Services;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import org.eclipse.microprofile.faulttolerance.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for downloading album art from URLs and converting to base64.
 * Features caching, timeout handling, and async processing.
 */
@ApplicationScoped
public class AlbumArtService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlbumArtService.class);

    // Cache for downloaded album art to avoid re-downloading same URLs
    private final ConcurrentHashMap<String, String> artworkCache = new ConcurrentHashMap<>();
    
    // HTTP client for downloading images
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Inject
    Executor executor;

    /**
     * Downloads album art from URL and converts to base64 asynchronously.
     * 
     * @param imageUrl URL of the album art image
     * @return CompletableFuture with base64 encoded image data
     */
    @CircuitBreakerName("albumart-download")
    @Timeout(value = 30, unit = java.time.temporal.ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "fallbackConvertUrlToBase64")
    public CompletableFuture<String> convertUrlToBase64(String imageUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (imageUrl == null || imageUrl.trim().isEmpty()) {
                    LOGGER.warn("Empty image URL provided");
                    return null;
                }

                // Check cache first
                String cached = artworkCache.get(imageUrl);
                if (cached != null) {
                    LOGGER.debug("Using cached album art for: {}", imageUrl);
                    return cached;
                }

                LOGGER.info("Downloading album art from: {}", imageUrl);
                
                // Download image
                byte[] imageData = downloadImage(imageUrl);
                if (imageData == null || imageData.length == 0) {
                    LOGGER.warn("Failed to download image from: {}", imageUrl);
                    return null;
                }

                // Convert to base64
                String base64 = Base64.getEncoder().encodeToString(imageData);
                
                // Cache the result
                artworkCache.put(imageUrl, base64);
                
                LOGGER.info("Successfully downloaded and converted album art ({} bytes)", imageData.length);
                return base64;

            } catch (Exception e) {
                LOGGER.error("Failed to convert album art URL to base64: {}", imageUrl, e);
                return null;
            }
        }, executor);
    }

    /**
     * Fallback method when album art download fails.
     */
    public CompletableFuture<String> fallbackConvertUrlToBase64(String imageUrl) {
        LOGGER.warn("Album art download failed, using fallback for: {}", imageUrl);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Downloads image data from URL with proper headers and timeout.
     */
    private byte[] downloadImage(String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "JMedia/1.0 (Album Art Downloader)")
                    .header("Accept", "image/*")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                byte[] imageData = response.body();
                
                // Validate that it's actually an image
                if (isValidImageData(imageData)) {
                    LOGGER.info("Successfully downloaded album art ({} bytes) from: {}", imageData.length, imageUrl);
                    return imageData;
                } else {
                    LOGGER.warn("Downloaded data is not a valid image: {}", imageUrl);
                    return null;
                }
            } else if (response.statusCode() == 302 || response.statusCode() == 301) {
                // Handle redirects manually if needed
                String location = response.headers().firstValue("Location").orElse(null);
                LOGGER.warn("Redirect {} to: {} for album art: {}", response.statusCode(), location, imageUrl);
                // Try the redirect location if available
                if (location != null && !location.equals(imageUrl)) {
                    return downloadImage(location);
                }
                return null;
            } else {
                LOGGER.warn("HTTP error {} downloading album art: {}", response.statusCode(), imageUrl);
                return null;
            }

        } catch (SocketTimeoutException e) {
            LOGGER.warn("Timeout downloading album art: {}", imageUrl);
            return null;
        } catch (ConnectException e) {
            LOGGER.warn("Connection failed downloading album art: {}", imageUrl);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted downloading album art: {}", imageUrl);
            return null;
        } catch (Exception e) {
            LOGGER.error("Error downloading album art: {}", imageUrl, e);
            return null;
        }
    }

    /**
     * Validates that downloaded data is a valid image.
     */
    private boolean isValidImageData(byte[] imageData) {
        if (imageData == null || imageData.length < 8) {
            return false;
        }

        // Check common image file signatures
        if (imageData.length >= 4) {
            // JPEG
            if (imageData[0] == (byte) 0xFF && imageData[1] == (byte) 0xD8 && 
                imageData[2] == (byte) 0xFF) {
                return true;
            }
            // PNG
            if (imageData[0] == (byte) 0x89 && imageData[1] == (byte) 0x50 && 
                imageData[2] == (byte) 0x4E && imageData[3] == (byte) 0x47) {
                return true;
            }
        }

        if (imageData.length >= 8) {
            // GIF
            if (imageData[0] == (byte) 0x47 && imageData[1] == (byte) 0x49 && 
                imageData[2] == (byte) 0x46 && imageData[3] == (byte) 0x38) {
                return true;
            }
            // BMP
            if (imageData[0] == (byte) 0x42 && imageData[1] == (byte) 0x4D) {
                return true;
            }
            // WebP
            if (imageData[0] == (byte) 0x52 && imageData[1] == (byte) 0x49 && 
                imageData[2] == (byte) 0x46 && imageData[3] == (byte) 0x46 &&
                imageData[8] == (byte) 0x57 && imageData[9] == (byte) 0x45 && 
                imageData[10] == (byte) 0x42 && imageData[11] == (byte) 0x50) {
                return true;
            }
        }

        return false;
    }

    /**
     * Clears the album art cache.
     */
    public void clearCache() {
        artworkCache.clear();
        LOGGER.info("Album art cache cleared");
    }

    /**
     * Gets cache statistics.
     */
    public String getCacheStats() {
        return String.format("Album art cache contains %d entries", artworkCache.size());
    }

    /**
     * Checks if a URL is cached.
     */
    public boolean isCached(String imageUrl) {
        return artworkCache.containsKey(imageUrl);
    }
}