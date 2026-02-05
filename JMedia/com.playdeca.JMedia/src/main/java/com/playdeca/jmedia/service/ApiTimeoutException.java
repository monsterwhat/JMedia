package com.playdeca.jmedia.service;

/**
 * Exception thrown when API timeout occurs.
 */
public class ApiTimeoutException extends ApiException {
    
    public ApiTimeoutException(String apiName, long timeoutMs) {
        super(apiName, "TIMEOUT", 
              String.format("API timeout after %dms", timeoutMs));
    }
    
    public ApiTimeoutException(String apiName, long timeoutMs, Throwable cause) {
        super(apiName, "TIMEOUT", 
              String.format("API timeout after %dms", timeoutMs), cause);
    }
}