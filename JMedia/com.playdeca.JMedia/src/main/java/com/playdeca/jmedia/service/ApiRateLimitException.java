package com.playdeca.jmedia.service;

/**
 * Exception thrown when API rate limits are exceeded.
 */
public class ApiRateLimitException extends ApiException {
    private final long retryAfterMs;
    
    public ApiRateLimitException(String apiName, long retryAfterMs) {
        super(apiName, "RATE_LIMIT", 
              String.format("Rate limit exceeded, retry after %dms", retryAfterMs));
        this.retryAfterMs = retryAfterMs;
    }
    
    public ApiRateLimitException(String apiName, String message) {
        super(apiName, "RATE_LIMIT", message);
        this.retryAfterMs = 60000; // Default 1 minute
    }
    
    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}