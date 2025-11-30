package Controllers;

import Services.ImportService;
import Models.DTOs.ImportInstallationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ImportController {

    @Inject
    ImportService importService;

    /**
     * Starts the singleton background import process.
     */
    public void startDownload(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload, Long profileId) {
        importService.startDownload(url, format, downloadThreads, searchThreads, downloadPath, playlistName, queueAfterDownload, profileId);
    }

    /**
     * Checks if an import process is currently active.
     * @return true if an import is running, false otherwise.
     */
    public boolean isImporting() {
        return importService.isImporting();
    }

    /**
     * Gets the cached output of the current or last import process.
     * @return The entire output log as a single string.
     */
    public String getOutputCache() {
        return importService.getOutputCache();
    }

    /**
     * Gets the installation status of required external tools (Python, SpotDL, FFmpeg).
     * @return An object containing the installation status details.
     */
    public ImportInstallationStatus getInstallationStatus() {
        return importService.getInstallationStatus();
    }
}