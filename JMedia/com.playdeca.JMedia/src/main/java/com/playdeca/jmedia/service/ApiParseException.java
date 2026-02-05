package com.playdeca.jmedia.service;

/**
 * Exception thrown when API response parsing fails.
 */
public class ApiParseException extends ApiException {
    private final String responseData;
    
    public ApiParseException(String apiName, String message) {
        super(apiName, "PARSE_ERROR", message);
        this.responseData = null;
    }
    
    public ApiParseException(String apiName, String message, String responseData) {
        super(apiName, "PARSE_ERROR", message);
        this.responseData = responseData;
    }
    
    public ApiParseException(String apiName, String message, Throwable cause) {
        super(apiName, "PARSE_ERROR", message, cause);
        this.responseData = null;
    }
    
    public String getResponseData() {
        return responseData;
    }
}