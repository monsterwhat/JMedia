package Services.Platform;

import Models.DTOs.ImportInstallationStatus;
import java.io.IOException;

public interface PlatformOperations {
    
    // Installation status checks
    boolean isPackageMangerInstalled();
    boolean isPythonInstalled();
    boolean isSpotdlInstalled();
    boolean isFFmpegInstalled();
    boolean isWhisperInstalled();
    
    // Installation methods
    void installPackageManger(Long profileId) throws Exception;
    void installPython(Long profileId) throws Exception;
    void installSpotdl(Long profileId) throws Exception;
    void installFFmpeg(Long profileId) throws Exception;
    void installWhisper(Long profileId) throws Exception;
    
    // Uninstallation methods
    void uninstallPython(Long profileId) throws Exception;
    void uninstallSpotdl(Long profileId) throws Exception;
    void uninstallFFmpeg(Long profileId) throws Exception;
    void uninstallWhisper(Long profileId) throws Exception;
    
    // Command execution
    void executeCommand(String command, Long profileId) throws Exception;
    void executeCommandAsAdmin(String command, Long profileId) throws Exception;
    
    // Python executable detection
    String findPythonExecutable() throws Exception;
    
    // Installation status messages
    String getPackageManagerName();
    String getPackageManagerInstallMessage();
    String getPythonInstallMessage();
    String getSpotdlInstallMessage();
    String getFFmpegInstallMessage();
    String getWhisperInstallMessage();
    
    // Platform-specific paths and configurations
    String getSystemPythonCommand();
    String[] getPythonExecutableVariants();
    String getSpotdlCommand();
    String getFFmpegCommand();
    String getWhisperCommand();
}