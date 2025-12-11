package Controllers;

import Models.Settings;
import Services.SettingsService;
import Services.ImportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;

@ApplicationScoped
public class SetupController {

    @Inject
    private SettingsService settingsService;

    @Inject
    private ImportService importService;

    public boolean isFirstTimeSetup() {
        Settings settings = settingsService.getOrCreateSettings();
        return settings.getFirstTimeSetup() != null ? settings.getFirstTimeSetup() : true;
    }

    public void completeSetup(String musicLibraryPath, String videoLibraryPath, 
                             Boolean installImportFeatures, String outputFormat,
                             Integer downloadThreads, Integer searchThreads, Boolean runAsService) {
        Settings settings = settingsService.getOrCreateSettings();
        
        // Set library paths
        if (musicLibraryPath != null && !musicLibraryPath.isBlank()) {
            settings.setLibraryPath(musicLibraryPath);
        }
        if (videoLibraryPath != null && !videoLibraryPath.isBlank()) {
            settings.setVideoLibraryPath(videoLibraryPath);
        }
        
        // Set import settings if features are requested
        if (installImportFeatures != null && installImportFeatures) {
            settings.setOutputFormat(outputFormat != null ? outputFormat : "mp3");
            settings.setDownloadThreads(downloadThreads != null ? downloadThreads : 4);
            settings.setSearchThreads(searchThreads != null ? searchThreads : 4);
        }
        
        // Set service mode
        if (runAsService != null) {
            settings.setRunAsService(runAsService);
        }
        
        // Mark setup as completed
        settings.setFirstTimeSetup(false);
        
        settingsService.save(settings);
    }

    public void resetSetup() {
        Settings settings = settingsService.getOrCreateSettings();
        settings.setFirstTimeSetup(true);
        settingsService.save(settings);
    }

    public boolean validateMusicLibraryPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File folder = new File(path);
        return folder.exists() && folder.isDirectory();
    }

    public boolean validateVideoLibraryPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File folder = new File(path);
        return folder.exists() && folder.isDirectory();
    }

    public ImportService getImportService() {
        return importService;
    }
}