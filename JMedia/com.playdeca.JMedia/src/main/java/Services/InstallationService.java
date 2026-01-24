package Services;

import API.WS.ImportStatusSocket;
import Models.DTOs.ImportInstallationStatus;
import Services.Platform.PlatformOperations;
import Services.Platform.PlatformOperationsFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class InstallationService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(InstallationService.class);
    
    @Inject
    PlatformOperationsFactory platformOperationsFactory;
    
    @Inject
    ImportStatusSocket importStatusSocket;
    
    /**
     * Gets the current installation status of all required tools.
     *
     * @return ImportInstallationStatus with current status of all tools
     */
    public ImportInstallationStatus getInstallationStatus() {
        LOGGER.info("Starting library installation status detection...");
        
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        
        try {
            boolean packageManagerInstalled = platformOps.isPackageMangerInstalled();
            boolean pythonInstalled = platformOps.isPythonInstalled();
            boolean spotdlInstalled = platformOps.isSpotdlInstalled();
            boolean ytdlpInstalled = platformOps.isYtdlpInstalled();
            boolean ffmpegInstalled = platformOps.isFFmpegInstalled();
            boolean whisperInstalled = platformOps.isWhisperInstalled();
            
            String packageManagerMessage = packageManagerInstalled ? 
                platformOps.getPackageManagerName() + " found" : 
                platformOps.getPackageManagerInstallMessage();
            String pythonMessage = pythonInstalled ? 
                "Python found" : 
                platformOps.getPythonInstallMessage();
            String spotdlMessage = spotdlInstalled ? 
                "SpotDL found" : 
                platformOps.getSpotdlInstallMessage();
            String ytdlpMessage = ytdlpInstalled ? 
                "yt-dlp found" : 
                platformOps.getYtdlpInstallMessage();
            String ffmpegMessage = ffmpegInstalled ? 
                "FFmpeg found" : 
                platformOps.getFFmpegInstallMessage();
            String whisperMessage = whisperInstalled ? 
                "Whisper found" : 
                platformOps.getWhisperInstallMessage();
            
            // Log final detection results
            LOGGER.info("Library installation status detection completed:");
            LOGGER.info("  Package Manager: {} - {}", packageManagerInstalled ? "INSTALLED" : "NOT INSTALLED", packageManagerMessage);
            LOGGER.info("  Python: {} - {}", pythonInstalled ? "INSTALLED" : "NOT INSTALLED", pythonMessage);
            LOGGER.info("  SpotDL: {} - {}", spotdlInstalled ? "INSTALLED" : "NOT INSTALLED", spotdlMessage);
            LOGGER.info("  yt-dlp: {} - {}", ytdlpInstalled ? "INSTALLED" : "NOT INSTALLED", ytdlpMessage);
            LOGGER.info("  FFmpeg: {} - {}", ffmpegInstalled ? "INSTALLED" : "NOT INSTALLED", ffmpegMessage);
            LOGGER.info("  Whisper: {} - {}", whisperInstalled ? "INSTALLED" : "NOT INSTALLED", whisperMessage);
            
            return new ImportInstallationStatus(packageManagerInstalled, pythonInstalled, spotdlInstalled, ytdlpInstalled, ffmpegInstalled, whisperInstalled, 
                    packageManagerMessage, pythonMessage, spotdlMessage, ytdlpMessage, ffmpegMessage, whisperMessage);
            
        } catch (Exception e) {
            LOGGER.error("Critical error during installation status detection", e);
            return new ImportInstallationStatus(
                    false, false, false, false, false, false,
                    "Error checking package manager: " + e.getMessage(),
                    "Error checking Python: " + e.getMessage(),
                    "Error checking SpotDL: " + e.getMessage(),
                    "Error checking yt-dlp: " + e.getMessage(),
                    "Error checking FFmpeg: " + e.getMessage(),
                    "Error checking Whisper: " + e.getMessage()
            );
        }
    }
    
    /**
     * Installs all required tools in the correct order.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If any installation fails
     */
    public void installAllRequirements(Long profileId) throws Exception {
        ImportInstallationStatus status = getInstallationStatus();
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();

        if (!status.chocoInstalled) {
            broadcast("Installing " + platformOps.getPackageManagerName() + "...\n", profileId);
            platformOps.installPackageManger(profileId);
        }

        // Refresh status after package manager installation
        status = getInstallationStatus();
        if (status.chocoInstalled) {
            if (!status.pythonInstalled) {
                broadcast("Installing Python...\n", profileId);
                platformOps.installPython(profileId);
            }

            // Refresh status after Python installation
            status = getInstallationStatus();
            if (status.pythonInstalled) {
                if (!status.ffmpegInstalled) {
                    broadcast("Installing FFmpeg...\n", profileId);
                    platformOps.installFFmpeg(profileId);
                }

                if (!status.spotdlInstalled) {
                    broadcast("Installing SpotDL...\n", profileId);
                    platformOps.installSpotdl(profileId);
                }

                if (!status.ytdlpInstalled) {
                    broadcast("Installing yt-dlp...\n", profileId);
                    platformOps.installYtdlp(profileId);
                }
            }
        }

        broadcast("Installation process completed.\n", profileId);
        broadcast("[INSTALLATION_FINISHED]", profileId);
    }
    
    /**
     * Installs the platform-specific package manager.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installPackageManger(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installPackageManger(profileId);
    }

    /**
     * Installs Python.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installPython(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installPython(profileId);
    }

    /**
     * Installs FFmpeg.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installFFmpeg(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installFFmpeg(profileId);
    }

    /**
     * Installs SpotDL.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     * @return The detected Python executable after installation
     */
    public String installSpotdl(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installSpotdl(profileId);
        
        // After installation, return the detected Python executable
        try {
            return platformOps.findPythonExecutable();
        } catch (Exception e) {
            LOGGER.warn("Could not determine Python executable after SpotDL installation", e);
            return null;
        }
    }

    /**
     * Installs yt-dlp.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installYtdlp(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installYtdlp(profileId);
    }

    /**
     * Installs Whisper.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installWhisper(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installWhisper(profileId);
    }
    
    /**
     * Uninstalls Python.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallPython(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallPython(profileId);
    }

    /**
     * Uninstalls FFmpeg.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallFFmpeg(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallFFmpeg(profileId);
    }

    /**
     * Uninstalls SpotDL.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallSpotdl(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallSpotdl(profileId);
    }

    /**
     * Uninstalls yt-dlp.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallYtdlp(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallYtdlp(profileId);
    }

    /**
     * Uninstalls Whisper.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallWhisper(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallWhisper(profileId);
    }
    
    /**
     * Validates that all required tools are installed.
     *
     * @throws Exception If any required tool is missing
     */
    public void validateInstallation() throws Exception {
        ImportInstallationStatus status = getInstallationStatus();
        if (!status.isAllInstalled()) {
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            StringBuilder errorMessage = new StringBuilder("SpotDL functionality requires the following external tools:\n");
            if (!status.chocoInstalled) {
                errorMessage.append("- Package Manager (").append(platformOps.getPackageManagerName()).append("): ").append(status.chocoMessage).append("\n");
            }
            if (!status.pythonInstalled) {
                errorMessage.append("- Python: ").append(status.pythonMessage).append("\n");
            }
            if (!status.spotdlInstalled) {
                errorMessage.append("- SpotDL: ").append(status.spotdlMessage).append("\n");
            }
            if (!status.ytdlpInstalled) {
                errorMessage.append("- yt-dlp: ").append(status.ytdlpMessage).append("\n");
            }
            if (!status.ffmpegInstalled) {
                errorMessage.append("- FFmpeg: ").append(status.ffmpegMessage).append("\n");
            }
            if (!status.whisperInstalled) {
                errorMessage.append("- Whisper: ").append(status.whisperMessage).append("\n");
            }
            throw new Exception(errorMessage.toString());
        }
    }
    
    /**
     * Finds the Python executable on the current platform.
     *
     * @return The Python executable command
     * @throws Exception If Python is not found
     */
    public String findPythonExecutable() throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        return platformOps.findPythonExecutable();
    }
    
    private void broadcast(String message, Long profileId) {
        importStatusSocket.broadcast(message, profileId);
    }
}