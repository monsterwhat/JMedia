package Services.Platform;

import API.WS.ImportStatusSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@ApplicationScoped
public class LinuxPlatformOperations implements PlatformOperations {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxPlatformOperations.class);
    
    @Inject
    ImportStatusSocket importStatusSocket;
    
    @Override
    public boolean isPackageMangerInstalled() {
        // Check for common package managers
        return isCommandAvailable("apt") || isCommandAvailable("yum") || isCommandAvailable("dnf") || 
               isCommandAvailable("pacman") || isCommandAvailable("zypper");
    }
    
    @Override
    public boolean isPythonInstalled() {
        return isCommandAvailable("python3") || isCommandAvailable("python");
    }
    
    @Override
    public boolean isSpotdlInstalled() {
        try {
            LOGGER.debug("Checking if SpotDL is installed...");
            
            // Try direct command first
            boolean directCommand = isCommandAvailable("spotdl");
            LOGGER.debug("Direct 'spotdl' command available: {}", directCommand);
            if (directCommand) {
                LOGGER.info("SpotDL found via direct command");
                return true;
            }
            
            // Check pipx installation location (Ubuntu: ~/.local/bin/spotdl)
            String userHome = System.getProperty("user.home");
            String pipxSpotdlPath = userHome + "/.local/bin/spotdl";
            boolean pipxCheck = java.nio.file.Files.exists(java.nio.file.Paths.get(pipxSpotdlPath));
            LOGGER.debug("Pipx SpotDL path exists: {}", pipxCheck);
            if (pipxCheck) {
                LOGGER.info("SpotDL found via pipx installation: {}", pipxSpotdlPath);
                return true;
            }
            
            // Try as Python module with each variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isCommandAvailable(pythonExecutable)) {
                    boolean moduleCheck = executeCommandForCheck(pythonExecutable + " -m spotdl --version");
                    LOGGER.debug("Python module check for {} -m spotdl: {}", pythonExecutable, moduleCheck);
                    if (moduleCheck) {
                        LOGGER.info("SpotDL found via Python module: {} -m spotdl", pythonExecutable);
                        return true;
                    }
                }
            }
            
            LOGGER.debug("SpotDL not found through any detection method");
        } catch (Exception e) {
            LOGGER.error("Error checking SpotDL installation", e);
            return false;
        }
        return false;
    }
    
    @Override
    public boolean isYtdlpInstalled() {
        try {
            LOGGER.debug("Checking if yt-dlp is installed...");
            
            // Try direct command first
            boolean directCommand = isCommandAvailable("yt-dlp");
            LOGGER.debug("Direct 'yt-dlp' command available: {}", directCommand);
            if (directCommand) {
                LOGGER.info("yt-dlp found via direct command");
                return true;
            }
            
            // Check pipx installation location (Ubuntu: ~/.local/bin/yt-dlp)
            String userHome = System.getProperty("user.home");
            String pipxYtdlpPath = userHome + "/.local/bin/yt-dlp";
            boolean pipxCheck = java.nio.file.Files.exists(java.nio.file.Paths.get(pipxYtdlpPath));
            LOGGER.debug("Pipx yt-dlp path exists: {}", pipxCheck);
            if (pipxCheck) {
                LOGGER.info("yt-dlp found via pipx installation: {}", pipxYtdlpPath);
                return true;
            }
            
            // Try as Python module with each variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isCommandAvailable(pythonExecutable)) {
                    boolean moduleCheck = executeCommandForCheck(pythonExecutable + " -m yt_dlp --version");
                    LOGGER.debug("Python module check for {} -m yt_dlp: {}", pythonExecutable, moduleCheck);
                    if (moduleCheck) {
                        LOGGER.info("yt-dlp found via Python module: {} -m yt_dlp", pythonExecutable);
                        return true;
                    }
                }
            }
            
            LOGGER.debug("yt-dlp not found through any detection method");
        } catch (Exception e) {
            LOGGER.error("Error checking yt-dlp installation", e);
            return false;
        }
        return false;
    }
    
    @Override
    public boolean isFFmpegInstalled() {
        return isCommandAvailable("ffmpeg");
    }
    
    @Override
    public boolean isWhisperInstalled() {
        try {
            LOGGER.debug("Checking if Whisper is installed...");
            
            // Check pipx installation location (Ubuntu: ~/.local/bin/whisper)
            String userHome = System.getProperty("user.home");
            String pipxWhisperPath = userHome + "/.local/bin/whisper";
            boolean pipxCheck = java.nio.file.Files.exists(java.nio.file.Paths.get(pipxWhisperPath));
            LOGGER.debug("Pipx Whisper path exists: {}", pipxCheck);
            if (pipxCheck) {
                LOGGER.info("Whisper found via pipx installation: {}", pipxWhisperPath);
                return true;
            }
            
            // Try as Python module with each variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isCommandAvailable(pythonExecutable)) {
                    boolean moduleCheck = executeCommandForCheck(pythonExecutable + " -m whisper -h");
                    LOGGER.debug("Python module check for {} -m whisper: {}", pythonExecutable, moduleCheck);
                    if (moduleCheck) {
                        LOGGER.info("Whisper found via Python module: {} -m whisper", pythonExecutable);
                        return true;
                    }
                }
            }
            
            LOGGER.debug("Whisper not found through any detection method");
        } catch (Exception e) {
            LOGGER.error("Error checking Whisper installation", e);
            return false;
        }
        return false;
    }
    
    @Override
    public void installPackageManger(Long profileId) throws Exception {
        broadcast("Linux distributions typically come with package managers pre-installed.\n", profileId);
        broadcast("Detected package managers: apt, yum, dnf, pacman, or zypper should be available.\n", profileId);
        broadcast("If no package manager is found, please install one for your distribution.\n", profileId);
    }
    
    @Override
    public void installPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Installing Python using system package manager...\n", profileId);
        
        if (isCommandAvailable("apt")) {
            executeCommandAsRoot("apt update && apt install -y python3 python3-pip python3-venv python3-full", profileId);
        } else if (isCommandAvailable("yum")) {
            executeCommandAsRoot("yum install -y python3 python3-pip", profileId);
        } else if (isCommandAvailable("dnf")) {
            executeCommandAsRoot("dnf install -y python3 python3-pip", profileId);
        } else if (isCommandAvailable("pacman")) {
            executeCommandAsRoot("pacman -S --noconfirm python python-pip", profileId);
        } else if (isCommandAvailable("zypper")) {
            executeCommandAsRoot("zypper install -y python3 python3-pip", profileId);
        } else {
            throw new Exception("No supported package manager found. Please install Python manually.");
        }
        
        // Verify pip is working after installation
        broadcast("Verifying pip installation...\n", profileId);
        String pythonExecutable = findPythonExecutable();
        try {
            executeCommand(pythonExecutable + " -m pip --version", profileId);
        } catch (Exception e) {
            broadcast("Pip verification failed, attempting to fix...\n", profileId);
            // Try to fix pip installation
            if (isCommandAvailable("apt")) {
                executeCommandAsRoot("apt install -y python3-distutils python3-setuptools", profileId);
                executeCommandAsRoot(pythonExecutable + " -m ensurepip --upgrade", profileId);
            }
        }
        
        // Install pipx for better Python application management (Ubuntu/Debian)
        if (isCommandAvailable("apt") && !isCommandAvailable("pipx")) {
            try {
                broadcast("Installing pipx for better Python application management...\n", profileId);
                executeCommandAsRoot("apt install -y pipx", profileId);
                executeCommandAsRoot("pipx ensurepath", profileId);
            } catch (Exception e) {
                broadcast("Warning: Could not install pipx, will use alternative installation methods\n", profileId);
            }
        }
        
        broadcastInstallationProgress("python", 100, false, profileId);
        broadcast("Python installation completed\n", profileId);
    }
    
    @Override
    public void installSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Installing SpotDL...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        
        // Try different installation methods in order of preference
        boolean success = false;
        
        // Method 1: Try pipx (recommended for system-wide applications)
        if (isCommandAvailable("pipx")) {
            try {
                broadcast("Installing with pipx...\n", profileId);
                executeCommand("pipx install spotdl", profileId);
                success = true;
            } catch (Exception e) {
                broadcast("pipx installation failed, trying user installation...\n", profileId);
            }
        }
        
        // Method 2: Try user installation with --user flag
        if (!success) {
            try {
                broadcast("Installing with pip --user...\n", profileId);
                executeCommand(pythonExecutable + " -m pip install --user spotdl", profileId);
                success = true;
            } catch (Exception e) {
                broadcast("User installation failed\n", profileId);
            }
        }
        
        if (!success) {
            throw new Exception("Failed to install SpotDL. Please install pipx or ensure user directory permissions are correct.");
        }
        
        if (success) {
            broadcastInstallationProgress("spotdl", 100, false, profileId);
            broadcast("SpotDL installation completed\n", profileId);
        }
    }
    
    @Override
    public void installYtdlp(Long profileId) throws Exception {
        broadcastInstallationProgress("ytdlp", 0, true, profileId);
        broadcast("Installing yt-dlp...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        
        // Try different installation methods in order of preference
        boolean success = false;
        
        // Method 1: Try system package manager (if available)
        try {
            if (isCommandAvailable("apt")) {
                broadcast("Installing via APT package manager...\n", profileId);
                executeCommandAsRoot("apt update && apt install -y yt-dlp", profileId);
                success = true;
            } else if (isCommandAvailable("dnf")) {
                broadcast("Installing via DNF package manager...\n", profileId);
                executeCommandAsRoot("dnf install -y yt-dlp", profileId);
                success = true;
            } else if (isCommandAvailable("pacman")) {
                broadcast("Installing via Pacman package manager...\n", profileId);
                executeCommandAsRoot("pacman -S --noconfirm yt-dlp", profileId);
                success = true;
            }
        } catch (Exception e) {
            broadcast("Package manager installation failed, trying pipx...\n", profileId);
        }
        
        // Method 2: Try pipx (recommended for system-wide applications)
        if (!success && isCommandAvailable("pipx")) {
            try {
                broadcast("Installing with pipx...\n", profileId);
                executeCommand("pipx install yt-dlp", profileId);
                success = true;
            } catch (Exception e) {
                broadcast("pipx installation failed, trying user installation...\n", profileId);
            }
        }
        
        // Method 3: Try user installation with --user flag
        if (!success) {
            try {
                broadcast("Installing with pip --user...\n", profileId);
                executeCommand(pythonExecutable + " -m pip install --user yt-dlp", profileId);
                success = true;
            } catch (Exception e) {
                broadcast("User installation failed\n", profileId);
            }
        }
        
        if (!success) {
            throw new Exception("Failed to install yt-dlp. Please install manually or ensure user directory permissions are correct.");
        }
        
        if (success) {
            broadcastInstallationProgress("ytdlp", 100, false, profileId);
            broadcast("yt-dlp installation completed\n", profileId);
        }
    }
    
    @Override
    public void installFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Installing FFmpeg using system package manager...\n", profileId);
        
        if (isCommandAvailable("apt")) {
            executeCommandAsRoot("apt update && apt install -y ffmpeg", profileId);
        } else if (isCommandAvailable("yum")) {
            executeCommandAsRoot("yum install -y ffmpeg", profileId);
        } else if (isCommandAvailable("dnf")) {
            executeCommandAsRoot("dnf install -y ffmpeg", profileId);
        } else if (isCommandAvailable("pacman")) {
            executeCommandAsRoot("pacman -S --noconfirm ffmpeg", profileId);
        } else if (isCommandAvailable("zypper")) {
            executeCommandAsRoot("zypper install -y ffmpeg", profileId);
        } else {
            throw new Exception("No supported package manager found. Please install FFmpeg manually.");
        }
        
        broadcastInstallationProgress("ffmpeg", 100, false, profileId);
        broadcast("FFmpeg installation completed\n", profileId);
    }
    
    @Override
    public void installWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Installing Whisper...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        
        // Try user installation with --user flag
        try {
            broadcast("Installing with pip --user...\n", profileId);
            executeCommand(pythonExecutable + " -m pip install --user openai-whisper", profileId);
        } catch (Exception e) {
            throw new Exception("Failed to install Whisper. Please ensure user directory permissions are correct.");
        }
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper installation completed\n", profileId);
    }
    
    @Override
    public void uninstallPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Uninstalling Python using system package manager...\n", profileId);
        
        if (isCommandAvailable("apt")) {
            executeCommandAsRoot("apt remove -y python3 python3-pip", profileId);
        } else if (isCommandAvailable("yum")) {
            executeCommandAsRoot("yum remove -y python3 python3-pip", profileId);
        } else if (isCommandAvailable("dnf")) {
            executeCommandAsRoot("dnf remove -y python3 python3-pip", profileId);
        } else if (isCommandAvailable("pacman")) {
            executeCommandAsRoot("pacman -R --noconfirm python python-pip", profileId);
        } else if (isCommandAvailable("zypper")) {
            executeCommandAsRoot("zypper remove -y python3 python3-pip", profileId);
        } else {
            throw new Exception("No supported package manager found. Please uninstall Python manually.");
        }
        
        broadcastInstallationProgress("python", 100, false, profileId);
        broadcast("Python uninstallation completed\n", profileId);
    }
    
@Override
    public void uninstallSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Uninstalling SpotDL...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        executeCommand(pythonExecutable + " -m pip uninstall spotdl -y", profileId);
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL uninstallation completed\n", profileId);
    }
    
    @Override
    public void uninstallYtdlp(Long profileId) throws Exception {
        broadcastInstallationProgress("ytdlp", 0, true, profileId);
        broadcast("Uninstalling yt-dlp...\n", profileId);
        
        boolean success = false;
        
        // Try package manager first
        try {
            if (isCommandAvailable("apt")) {
                executeCommandAsRoot("apt remove -y yt-dlp", profileId);
                success = true;
            } else if (isCommandAvailable("dnf")) {
                executeCommandAsRoot("dnf remove -y yt-dlp", profileId);
                success = true;
            } else if (isCommandAvailable("pacman")) {
                executeCommandAsRoot("pacman -R --noconfirm yt-dlp", profileId);
                success = true;
            }
        } catch (Exception e) {
            broadcast("Package manager uninstallation failed, trying pip...\n", profileId);
        }
        
        // Fallback to pip uninstallation
        if (!success) {
            String pythonExecutable = findPythonExecutable();
            executeCommand(pythonExecutable + " -m pip uninstall yt-dlp -y", profileId);
        }
        
        broadcastInstallationProgress("ytdlp", 100, false, profileId);
        broadcast("yt-dlp uninstallation completed\n", profileId);
    }
    
    @Override
    public void uninstallFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Uninstalling FFmpeg using system package manager...\n", profileId);
        
        if (isCommandAvailable("apt")) {
            executeCommandAsRoot("apt remove -y ffmpeg", profileId);
        } else if (isCommandAvailable("yum")) {
            executeCommandAsRoot("yum remove -y ffmpeg", profileId);
        } else if (isCommandAvailable("dnf")) {
            executeCommandAsRoot("dnf remove -y ffmpeg", profileId);
        } else if (isCommandAvailable("pacman")) {
            executeCommandAsRoot("pacman -R --noconfirm ffmpeg", profileId);
        } else if (isCommandAvailable("zypper")) {
            executeCommandAsRoot("zypper remove -y ffmpeg", profileId);
        } else {
            throw new Exception("No supported package manager found. Please uninstall FFmpeg manually.");
        }
        
        broadcastInstallationProgress("ffmpeg", 100, false, profileId);
        broadcast("FFmpeg uninstallation completed\n", profileId);
    }
    
    @Override
    public void uninstallWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Uninstalling Whisper...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        executeCommand(pythonExecutable + " -m pip uninstall openai-whisper -y", profileId);
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper uninstallation completed\n", profileId);
    }
    
    @Override
    public void executeCommand(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    String lineContent = line.trim() + "\n";
                    broadcast(lineContent, profileId);
                    output.append(lineContent);
                }
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorMsg = "Command failed with exit code: " + exitCode + ": " + command;
            if (output.length() > 0) {
                errorMsg += "\nOutput: " + output.toString();
            }
            throw new Exception(errorMsg);
        }
    }
    
    @Override
    public void executeCommandAsAdmin(String command, Long profileId) throws Exception {
        executeCommandAsRoot(command, profileId);
    }
    
    @Override
    public String findPythonExecutable() throws Exception {
        String[] pythonExecutables = getPythonExecutableVariants();
        
        for (String executable : pythonExecutables) {
            if (isCommandAvailable(executable)) {
                LOGGER.info("Found Python executable: {}", executable);
                return executable;
            }
        }
        
        throw new Exception("Python executable not found. Please ensure Python is installed and available in PATH.");
    }
    
    @Override
    public String getPackageManagerName() {
        if (isCommandAvailable("apt")) return "APT (Debian/Ubuntu)";
        if (isCommandAvailable("yum")) return "YUM (RHEL/CentOS)";
        if (isCommandAvailable("dnf")) return "DNF (Fedora)";
        if (isCommandAvailable("pacman")) return "Pacman (Arch)";
        if (isCommandAvailable("zypper")) return "Zypper (openSUSE)";
        return "Unknown";
    }
    
    @Override
    public String getPackageManagerInstallMessage() {
        return "No supported package manager found. Please install apt, yum, dnf, pacman, or zypper for your distribution.";
    }
    
    @Override
    public String getPythonInstallMessage() {
        return "Python is not installed. Please install Python 3 using your system package manager.";
    }
    
@Override
    public String getSpotdlInstallMessage() {
        return "SpotDL is not installed. Please install SpotDL using pip: pip install spotdl";
    }
    
    @Override
    public String getYtdlpInstallMessage() {
        return "yt-dlp is not installed. Please install yt-dlp using your package manager (apt install yt-dlp) or pip: pip install yt-dlp";
    }
    
    @Override
    public String getFFmpegInstallMessage() {
        return "FFmpeg is not installed. Please install FFmpeg using your system package manager.";
    }
    
    @Override
    public String getWhisperInstallMessage() {
        return "Whisper is not installed. Please install Whisper using pip: pip install openai-whisper";
    }
    
    @Override
    public String getSystemPythonCommand() {
        return "python3";
    }
    
    @Override
    public String[] getPythonExecutableVariants() {
        return new String[]{"python3", "python", "python3.13", "python3.12", "python3.11"};
    }
    
    @Override
    public String getSpotdlCommand() {
        // Check for pipx installation first
        String userHome = System.getProperty("user.home");
        String pipxSpotdlPath = userHome + "/.local/bin/spotdl";
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(pipxSpotdlPath))) {
            return pipxSpotdlPath;
        }
        
        // Fall back to standard command
        return "spotdl";
    }
    
    @Override
    public String getYtdlpCommand() {
        // Check for pipx installation first
        String userHome = System.getProperty("user.home");
        String pipxYtdlpPath = userHome + "/.local/bin/yt-dlp";
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(pipxYtdlpPath))) {
            return pipxYtdlpPath;
        }
        
        // Fall back to standard command
        return "yt-dlp";
    }
    
    @Override
    public String getFFmpegCommand() {
        return "ffmpeg";
    }
    
    @Override
    public String getWhisperCommand() {
        return "whisper";
    }
    
    @Override
    public boolean shouldUseSpotdlDirectCommand() {
        // Check if spotdl is available as direct command (pipx installation)
        String userHome = System.getProperty("user.home");
        String pipxSpotdlPath = userHome + "/.local/bin/spotdl";
        return java.nio.file.Files.exists(java.nio.file.Paths.get(pipxSpotdlPath)) || isCommandAvailable("spotdl");
    }
    
    private boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (process.waitFor() == 0) {
                return true;
            }
            
            // For Ubuntu, also check ~/.local/bin (pipx installation location)
            String userHome = System.getProperty("user.home");
            String localBinPath = userHome + "/.local/bin/" + command;
            return java.nio.file.Files.exists(java.nio.file.Paths.get(localBinPath)) && 
                   java.nio.file.Files.isExecutable(java.nio.file.Paths.get(localBinPath));
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean executeCommandForCheck(String command) {
        try {
            LOGGER.debug("Executing check command: {}", command);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            LOGGER.debug("Command '{}' exited with code: {}", command, exitCode);
            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.debug("Exception executing check command '{}': {}", command, e.getMessage());
            return false;
        }
    }
    
    private void executeCommandAsRoot(String command, Long profileId) throws Exception {
        broadcast("Executing with sudo: " + command + "\n", profileId);
        ProcessBuilder pb = new ProcessBuilder("sudo", "bash", "-c", command);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    broadcast(line.trim() + "\n", profileId);
                }
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("Sudo command failed with exit code: " + exitCode + ": " + command);
        }
    }
    
    private void broadcast(String message, Long profileId) {
        importStatusSocket.broadcast(message, profileId);
    }
    
    private void broadcastInstallationProgress(String component, int progress, boolean isInstalling, Long profileId) {
        String progressMessage = String.format(
                "{\"type\": \"installation-progress\", \"component\": \"%s\", \"progress\": %d, \"installing\": %s}\n",
                component, progress, isInstalling
        );
        importStatusSocket.broadcast(progressMessage, profileId);
    }
}