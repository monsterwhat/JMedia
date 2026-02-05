package com.playdeca.jmedia.service;

/**
 * Exception thrown when API is unavailable or unreachable.
 */
public class ApiUnavailableException extends ApiException {
    private final int httpStatusCode;
    private final boolean isTemporary;
    
    public ApiUnavailableException(String apiName, int httpStatusCode) {
        super(apiName, "UNAVAILABLE", 
              String.format("API unavailable with HTTP status %d", httpStatusCode));
        this.httpStatusCode = httpStatusCode;
        this.isTemporary = isTemporaryError(httpStatusCode);
    }
    
    public ApiUnavailableException(String apiName, String message, Throwable cause) {
        super(apiName, "UNAVAILABLE", message, cause);
        this.httpStatusCode = 0;
        this.isTemporary = true;
    }
    
    public int getHttpStatusCode() {
        return httpStatusCode;
    }
    
    public boolean isTemporary() {
        return isTemporary;
    }
    
    private boolean isTemporaryError(int statusCode) {
        return statusCode == 429 || // Rate limit
               statusCode == 502 || // Bad gateway
               statusCode == 503 || // Service unavailable
               statusCode == 504;   // Gateway timeout
    }
}