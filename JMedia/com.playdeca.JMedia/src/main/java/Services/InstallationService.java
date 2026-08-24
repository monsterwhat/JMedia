package Services;

import API.WS.ImportStatusSocket;
import Models.DTOs.ImportInstallationStatus;
import Services.Platform.PlatformOperations;
import Services.Platform.PlatformOperationsFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class InstallationService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(InstallationService.class);
    
    // Cache installation status for 24 hours
    private static final long CACHE_DURATION_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private final AtomicReference<CachedStatus> statusCache = new AtomicReference<>();
    
    // Guards against concurrent component updates (HTTP 409 when one is already running)
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);
    
    @Inject
    PlatformOperationsFactory platformOperationsFactory;
    
    @Inject
    ImportStatusSocket importStatusSocket;

    @Inject
    JsRuntimeResolver jsRuntimeResolver;
    
    /**
     * Gets the current installation status of all required tools.
     * Results are cached for 24 hours to avoid repeated expensive process checks.
     *
     * @return ImportInstallationStatus with current status of all tools
     */
    public ImportInstallationStatus getInstallationStatus() {
        // Check cache first
        CachedStatus cached = statusCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp) < CACHE_DURATION_MS) {
            LOGGER.debug("Returning cached installation status (age: {}ms)", now - cached.timestamp);
            return cached.status;
        }
        
        LOGGER.info("Starting library installation status detection...");
        
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        
        try {
            boolean packageManagerInstalled = platformOps.isPackageMangerInstalled();
            boolean pythonInstalled = platformOps.isPythonInstalled();
            boolean nodeInstalled = platformOps.isNodeInstalled();
            boolean spotdlInstalled = platformOps.isSpotdlInstalled();
            boolean ytdlpInstalled = platformOps.isYtdlpInstalled();
            boolean ffmpegInstalled = platformOps.isFFmpegInstalled();
            boolean parakeetInstalled = platformOps.isParakeetInstalled();
            boolean tesseractInstalled = platformOps.isTesseractInstalled();
            boolean denoInstalled = platformOps.isDenoInstalled();
            
            String packageManagerMessage = packageManagerInstalled ? 
                platformOps.getPackageManagerName() + " found" : 
                platformOps.getPackageManagerInstallMessage();
            String pythonMessage = pythonInstalled ? 
                "Python found" : 
                platformOps.getPythonInstallMessage();
            String nodeMessage = nodeInstalled ? 
                "Node.js found" : 
                platformOps.getNodeInstallMessage();
            String spotdlMessage = spotdlInstalled ? 
                "SpotDL found" : 
                platformOps.getSpotdlInstallMessage();
            String ytdlpMessage = ytdlpInstalled ? 
                "yt-dlp found" : 
                platformOps.getYtdlpInstallMessage();
            String ffmpegMessage = ffmpegInstalled ? 
                "FFmpeg found" : 
                platformOps.getFFmpegInstallMessage();
            String parakeetMessage = parakeetInstalled ? 
                "Parakeet found" : 
                platformOps.getParakeetInstallMessage();
            String tesseractMessage = tesseractInstalled ? 
                "Tesseract found" : 
                platformOps.getTesseractInstallMessage();
            String denoMessage = denoInstalled ? 
                platformOps.getDenoVersion() != null ? platformOps.getDenoVersion() : "Deno found" : 
                "Deno not found. Install via the import tools panel or https://deno.com";
            
            // Log final detection results
            LOGGER.info("Library installation status detection completed:");
            LOGGER.info("  Package Manager: {} - {}", packageManagerInstalled ? "INSTALLED" : "NOT INSTALLED", packageManagerMessage);
            LOGGER.info("  Python: {} - {}", pythonInstalled ? "INSTALLED" : "NOT INSTALLED", pythonMessage);
            LOGGER.info("  Node.js: {} - {}", nodeInstalled ? "INSTALLED" : "NOT INSTALLED", nodeMessage);
            LOGGER.info("  SpotDL: {} - {}", spotdlInstalled ? "INSTALLED" : "NOT INSTALLED", spotdlMessage);
            LOGGER.info("  yt-dlp: {} - {}", ytdlpInstalled ? "INSTALLED" : "NOT INSTALLED", ytdlpMessage);
            LOGGER.info("  FFmpeg: {} - {}", ffmpegInstalled ? "INSTALLED" : "NOT INSTALLED", ffmpegMessage);
            LOGGER.info("  Parakeet: {} - {}", parakeetInstalled ? "INSTALLED" : "NOT INSTALLED", parakeetMessage);
            LOGGER.info("  Tesseract: {} - {}", tesseractInstalled ? "INSTALLED" : "NOT INSTALLED", tesseractMessage);
            LOGGER.info("  Deno: {} - {}", denoInstalled ? "INSTALLED" : "NOT INSTALLED", denoMessage);
            
            ImportInstallationStatus status = new ImportInstallationStatus(packageManagerInstalled, pythonInstalled, nodeInstalled, spotdlInstalled, ytdlpInstalled, ffmpegInstalled, parakeetInstalled, tesseractInstalled, denoInstalled, 
                    packageManagerMessage, pythonMessage, nodeMessage, spotdlMessage, ytdlpMessage, ffmpegMessage, parakeetMessage, tesseractMessage, denoMessage);
            
            // Update cache
            statusCache.set(new CachedStatus(now, status));
            return status;
            
        } catch (Exception e) {
            LOGGER.error("Critical error during installation status detection", e);
            return new ImportInstallationStatus(
                    false, false, false, false, false, false, false, false, false,
                    "Error checking package manager: " + e.getMessage(),
                    "Error checking Python: " + e.getMessage(),
                    "Error checking Node.js: " + e.getMessage(),
                    "Error checking SpotDL: " + e.getMessage(),
                    "Error checking yt-dlp: " + e.getMessage(),
                    "Error checking FFmpeg: " + e.getMessage(),
                    "Error checking Parakeet: " + e.getMessage(),
                    "Error checking Tesseract: " + e.getMessage(),
                    "Error checking Deno: " + e.getMessage()
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
        clearCache(); // Force fresh status check after installation
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

                // yt-dlp's pip build requires an external JS runtime for YouTube extraction
                jsRuntimeResolver.reset();
                if (!jsRuntimeResolver.hasUsableRuntime()) {
                    broadcast("No JavaScript runtime found — installing Deno (required for YouTube)\n", profileId);
                    platformOps.installDeno(profileId);
                    jsRuntimeResolver.reset();
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
        clearCache();
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
        clearCache();
    }

    /**
     * Installs Node.js.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installNode(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installNode(profileId);
        clearCache();
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
        clearCache();
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
        clearCache();
        
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
        clearCache();
    }
    
    /**
     * Updates a single component by delegating to its platform-specific update
     * method. Only one update may run at a time across all components.
     *
     * @param component One of: choco, python, node, ffmpeg, spotdl, ytdlp, parakeet, tesseract, deno
     * @param profileId The profile ID for broadcasting status updates
     * @return true if the update ran, false if another update is already in progress
     * @throws Exception If the component is unknown or the update fails
     */
    public boolean updateComponent(String component, Long profileId) throws Exception {
        if (!updateInProgress.compareAndSet(false, true)) return false;
        try {
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            switch (component) {
                case "choco": platformOps.updatePackageManger(profileId); break;
                case "python": platformOps.updatePython(profileId); break;
                case "node": platformOps.updateNode(profileId); break;
                case "ffmpeg": platformOps.updateFFmpeg(profileId); break;
                case "spotdl": platformOps.updateSpotdl(profileId); break;
                case "ytdlp": platformOps.updateYtdlp(profileId); break;
                case "parakeet": platformOps.updateParakeet(profileId); break;
                case "tesseract": platformOps.updateTesseract(profileId); break;
                case "deno": platformOps.updateDeno(profileId); break;
                default: throw new IllegalArgumentException("Unknown component: " + component);
            }
            clearCache();
            broadcast("[" + component.toUpperCase() + "_UPDATE_FINISHED]\n", profileId);
            LOGGER.info("Update of {} completed", component);
            return true;
        } finally { updateInProgress.set(false); }
    }
    
    /**
     * Gets the current yt-dlp version.
     *
     * @return The current version string, or null if not installed
     */
    public String getYtDlpVersion() {
        try {
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            String pythonExecutable = platformOps.findPythonExecutable();
            
            if (pythonExecutable == null) {
                return null;
            }
            
            ProcessBuilder processBuilder = new ProcessBuilder(pythonExecutable, "-m", "yt_dlp", "--version");
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            
            process.waitFor(30, TimeUnit.SECONDS);
            
            if (process.exitValue() == 0) {
                return output.toString().trim();
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to get yt-dlp version", e);
        }
        return null;
    }

    /**
     * Installs Parakeet.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installParakeet(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installParakeet(profileId);
        clearCache();
    }

    /**
     * Installs Tesseract.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installTesseract(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installTesseract(profileId);
        clearCache();
    }

    /**
     * Installs Deno.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If installation fails
     */
    public void installDeno(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.installDeno(profileId);
        clearCache();
        jsRuntimeResolver.reset();
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
        clearCache();
    }

    /**
     * Uninstalls Node.js.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallNode(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallNode(profileId);
        clearCache();
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
        clearCache();
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
        clearCache();
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
        clearCache();
    }

    /**
     * Uninstalls Parakeet.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallParakeet(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallParakeet(profileId);
        clearCache();
    }

    /**
     * Uninstalls Tesseract.
     *
     * @param profileId The profile ID for broadcasting status updates
     * @throws Exception If uninstallation fails
     */
    public void uninstallTesseract(Long profileId) throws Exception {
        PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
        platformOps.uninstallTesseract(profileId);
        clearCache();
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
            if (!status.parakeetInstalled) {
                errorMessage.append("- Parakeet: ").append(status.parakeetMessage).append("\n");
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
    
    /**
     * Clears the cached installation status to force a fresh check on next request.
     * Called after installation operations to ensure accurate status.
     */
    public void clearCache() {
        statusCache.set(null);
        LOGGER.info("Installation status cache cleared");
    }
    
    /**
     * Thread-safe cache wrapper for installation status.
     */
    private static class CachedStatus {
        final long timestamp;
        final ImportInstallationStatus status;
        
        CachedStatus(long timestamp, ImportInstallationStatus status) {
            this.timestamp = timestamp;
            this.status = status;
        }
    }
}
