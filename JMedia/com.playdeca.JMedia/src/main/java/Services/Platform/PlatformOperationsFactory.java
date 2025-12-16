package Services.Platform;

import Utils.OSDetector;
import Utils.OSType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class PlatformOperationsFactory {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformOperationsFactory.class);
    
    @Inject
    WindowsPlatformOperations windowsOperations;
    
    @Inject
    LinuxPlatformOperations linuxOperations;
    
    @Inject
    MacOSPlatformOperations macOSOperations;
    
    private PlatformOperations currentPlatform;
    
    public PlatformOperations getPlatformOperations() {
        if (currentPlatform == null) {
            currentPlatform = createPlatformOperations();
        }
        return currentPlatform;
    }
    
    private PlatformOperations createPlatformOperations() {
        OSType osType = OSDetector.detectOS();
        
        switch (osType) {
            case WINDOWS:
                LOGGER.info("Using Windows platform operations");
                return windowsOperations;
            case LINUX:
                LOGGER.info("Using Linux platform operations");
                return linuxOperations;
            case MACOS:
                LOGGER.info("Using macOS platform operations");
                return macOSOperations;
            case UNKNOWN:
            default:
                LOGGER.warn("Unknown OS detected, falling back to Linux operations");
                return linuxOperations;
        }
    }
    
    public OSType getCurrentOSType() {
        return OSDetector.detectOS();
    }
    
    public String getCurrentOSName() {
        return OSDetector.getOSName();
    }
}