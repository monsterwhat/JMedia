package Controllers;

import Services.InstallationService;
import Models.DTOs.ImportInstallationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InstallationController {

    @Inject
    InstallationService installationService;

    /**
     * Gets installation status of required external tools.
     * @return An object containing installation status details.
     */
    public ImportInstallationStatus getInstallationStatus() {
        return installationService.getInstallationStatus();
    }

    /**
     * Installs platform-specific package manager.
     */
    public void installPackageManger(Long profileId) throws Exception {
        installationService.installPackageManger(profileId);
    }

    /**
     * Installs Python.
     */
    public void installPython(Long profileId) throws Exception {
        installationService.installPython(profileId);
    }

    /**
     * Installs FFmpeg.
     */
    public void installFFmpeg(Long profileId) throws Exception {
        installationService.installFFmpeg(profileId);
    }

    /**
     * Installs SpotDL.
     */
    public void installSpotdl(Long profileId) throws Exception {
        installationService.installSpotdl(profileId);
    }

    /**
     * Installs Whisper.
     */
    public void installWhisper(Long profileId) throws Exception {
        installationService.installWhisper(profileId);
    }

    /**
     * Installs all required tools.
     */
    public void installAllRequirements(Long profileId) throws Exception {
        installationService.installAllRequirements(profileId);
    }

    /**
     * Uninstalls Python.
     */
    public void uninstallPython(Long profileId) throws Exception {
        installationService.uninstallPython(profileId);
    }

    /**
     * Uninstalls FFmpeg.
     */
    public void uninstallFFmpeg(Long profileId) throws Exception {
        installationService.uninstallFFmpeg(profileId);
    }

    /**
     * Uninstalls SpotDL.
     */
    public void uninstallSpotdl(Long profileId) throws Exception {
        installationService.uninstallSpotdl(profileId);
    }

    /**
     * Uninstalls Whisper.
     */
    public void uninstallWhisper(Long profileId) throws Exception {
        installationService.uninstallWhisper(profileId);
    }
}