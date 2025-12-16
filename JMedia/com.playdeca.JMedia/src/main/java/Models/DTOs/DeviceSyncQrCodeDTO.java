package Models.DTOs;

import lombok.Data;

@Data
public class DeviceSyncQrCodeDTO {
    
    private String serverAddress; // IP address or domain
    private int port; // Server port
    private String securityKey; // Unique security key for this session
    private String protocol; // "http" or "https"
    private String version; // API version
    private long timestamp; // QR code generation timestamp
    private String deviceName; // Optional device name suggestion
    
    // Constructor
    public DeviceSyncQrCodeDTO(String serverAddress, int port, String securityKey) {
        this.serverAddress = serverAddress;
        this.port = port;
        this.securityKey = securityKey;
        this.protocol = "http"; // Default to HTTP
        this.version = "1.0";
        this.timestamp = System.currentTimeMillis();
    }
    
    // Get full connection URL
    public String getFullConnectionUrl() {
        return protocol + "://" + serverAddress + ":" + port + "/api/device-sync/connect?key=" + securityKey;
    }
    
    // Get connection string for QR code (compact format)
    public String getQrCodeData() {
        return String.format("jmedia://sync?addr=%s&port=%d&key=%s&ts=%d", 
                           serverAddress, port, securityKey, timestamp);
    }
}