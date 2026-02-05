package com.playdeca.jmedia.service;

/**
 * Base class for all API-related exceptions in metadata enrichment.
 */
public abstract class ApiException extends Exception {
    private final String apiName;
    private final String errorCode;
    
    public ApiException(String apiName, String message) {
        super(message);
        this.apiName = apiName;
        this.errorCode = "UNKNOWN";
    }
    
    public ApiException(String apiName, String errorCode, String message) {
        super(message);
        this.apiName = apiName;
        this.errorCode = errorCode;
    }
    
    public ApiException(String apiName, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.apiName = apiName;
        this.errorCode = errorCode;
    }
    
    public String getApiName() {
        return apiName;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}