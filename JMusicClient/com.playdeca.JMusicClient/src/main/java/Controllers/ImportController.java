package Controllers;

import Services.ImportService;
import Models.DTOs.ImportInstallationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ImportController {

    @Inject
    ImportService importService;

    public void download(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String sessionId) throws Exception {
        importService.download(url, format, downloadThreads, searchThreads, downloadPath, sessionId);
    }

    public ImportInstallationStatus getInstallationStatus() {
        return importService.getInstallationStatus();
    }
}
