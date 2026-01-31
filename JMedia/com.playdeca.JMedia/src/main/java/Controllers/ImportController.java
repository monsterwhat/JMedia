package Controllers;

import Models.DTOs.ImportInstallationStatus;
import Services.ImportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ImportController {

    @Inject
    ImportService importService;

    /**
     * Starts singleton background import process.
     */
    public void startDownload(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload, Long profileId) {
        importService.startDownload(url, format, downloadThreads, searchThreads, downloadPath, playlistName, queueAfterDownload, profileId);
    }

    /**
     * Starts background import process for multiple songs/URLs.
     */
    public void startDownloads(List<String> urls, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload, Long profileId) {
        importService.startDownloads(urls, format, downloadThreads, searchThreads, downloadPath, playlistName, queueAfterDownload, profileId);
    }

    /**
     * Checks if an import process is currently active.
     *
     * @return true if an import is running, false otherwise.
     */
    public boolean isImporting() {
        return importService.isImporting();
    }

    /**
     * Gets cached output of current or last import process.
     *
     * @return The entire output log as a single string.
     */
    public String getOutputCache() {
        return importService.getOutputCache();
    }
  
    /**
     * Gets the installation status of required external tools (Python, SpotDL,
     * FFmpeg).
     *
     * @return An object containing the installation status details.
     */
    public ImportInstallationStatus getInstallationStatus() {
        return importService.getInstallationStatus();
    }

    /**
     * Installs the platform-specific package manager (Chocolatey on Windows,
     * apt/yum/dnf/pacman/zypper on Linux, Homebrew/MacPorts on macOS).
     */
    public void installPackageManger(Long profileId) throws Exception {
        importService.installPackageManger(profileId);
    }

    /**
     * Installs Python.
     */
    public void installPython(Long profileId) throws Exception {
        importService.installPython(profileId);
    }

    /**
     * Installs FFmpeg.
     */
    public void installFFmpeg(Long profileId) throws Exception {
        importService.installFFmpeg(profileId);
    }

    /**
     * Installs SpotDL.
     */
    public void installSpotdl(Long profileId) throws Exception {
        importService.installSpotdl(profileId);
    }

    /**
     * Installs Whisper.
     */
    public void installWhisper(Long profileId) throws Exception {
        importService.installWhisper(profileId);
    }

    /**
     * Uninstalls Python.
     */
    public void uninstallPython(Long profileId) throws Exception {
        importService.uninstallPython(profileId);
    }

    /**
     * Uninstalls FFmpeg.
     */
    public void uninstallFFmpeg(Long profileId) throws Exception {
        importService.uninstallFFmpeg(profileId);
    }

    /**
     * Uninstalls SpotDL.
     */
    public void uninstallSpotdl(Long profileId) throws Exception {
        importService.uninstallSpotdl(profileId);
    }

    /**
     * Uninstalls Whisper.
     */
    public void uninstallWhisper(Long profileId) throws Exception {
        importService.uninstallWhisper(profileId);
    }
}
