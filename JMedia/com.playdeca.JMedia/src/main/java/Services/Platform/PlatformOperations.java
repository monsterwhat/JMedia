package Services.Platform;

import Models.DTOs.ImportInstallationStatus;
import java.io.IOException;

public interface PlatformOperations {
    
// Installation status checks
    boolean isPackageMangerInstalled();
    boolean isPythonInstalled();
    boolean isNodeInstalled();
    boolean isSpotdlInstalled();
    boolean isYtdlpInstalled();
    boolean isFFmpegInstalled();
    boolean isParakeetInstalled();
    boolean isTesseractInstalled();
    boolean isDenoInstalled();
    
// Installation methods
    void installPackageManger(Long profileId) throws Exception;
    void installPython(Long profileId) throws Exception;
    void installNode(Long profileId) throws Exception;
    void installSpotdl(Long profileId) throws Exception;
    void installYtdlp(Long profileId) throws Exception;
    void installFFmpeg(Long profileId) throws Exception;
    void installParakeet(Long profileId) throws Exception;
    void installTesseract(Long profileId) throws Exception;
    void installDeno(Long profileId) throws Exception;
    
// Uninstallation methods
    void uninstallPython(Long profileId) throws Exception;
    void uninstallNode(Long profileId) throws Exception;
    void uninstallSpotdl(Long profileId) throws Exception;
    void uninstallYtdlp(Long profileId) throws Exception;
    void uninstallFFmpeg(Long profileId) throws Exception;
    void uninstallParakeet(Long profileId) throws Exception;
    void uninstallTesseract(Long profileId) throws Exception;
    
// Update methods (defaults = force reinstall via the installX methods, which are upgrade-capable/idempotent)
    default void updatePackageManger(Long profileId) throws Exception { installPackageManger(profileId); }
    default void updatePython(Long profileId) throws Exception { installPython(profileId); }
    default void updateNode(Long profileId) throws Exception { installNode(profileId); }
    default void updateSpotdl(Long profileId) throws Exception { installSpotdl(profileId); }
    default void updateYtdlp(Long profileId) throws Exception { installYtdlp(profileId); }
    default void updateFFmpeg(Long profileId) throws Exception { installFFmpeg(profileId); }
    default void updateParakeet(Long profileId) throws Exception { installParakeet(profileId); }
    default void updateTesseract(Long profileId) throws Exception { installTesseract(profileId); }
    default void updateDeno(Long profileId) throws Exception { installDeno(profileId); }
    
    // Command execution
    void executeCommand(String command, Long profileId) throws Exception;
    void executeCommandAsAdmin(String command, Long profileId) throws Exception;
    
    // Executable detection
    String findPythonExecutable() throws Exception;
    String getParakeetPythonExecutable() throws Exception;
    String findNodeExecutable() throws Exception;
    String getDenoVersion();
    
// Installation status messages
    String getPackageManagerName();
    String getPackageManagerInstallMessage();
    String getPythonInstallMessage();
    String getNodeInstallMessage();
    String getSpotdlInstallMessage();
    String getYtdlpInstallMessage();
    String getFFmpegInstallMessage();
    String getParakeetInstallMessage();
    String getTesseractInstallMessage();
    
    // Platform-specific paths and configurations
    String getSystemPythonCommand();
    String[] getPythonExecutableVariants();
    String getSpotdlCommand();
    String getYtdlpCommand();
    String getFFmpegCommand();
    String getParakeetScriptCommand();
    String getNodeCommand();
    String getTesseractCommand();
    
    // Execution method detection
    boolean shouldUseSpotdlDirectCommand();
    
    // Cookies management
    String getCookiesStoragePath();
    boolean validateCookiesFile(String cookiesPath);

    // File system operations
    java.util.List<java.util.Map<String, String>> listFolders(String path) throws Exception;
}