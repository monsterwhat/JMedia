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
            // Try direct command first
            if (isCommandAvailable("spotdl")) {
                return true;
            }
            
            // Try as Python module with each variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isCommandAvailable(pythonExecutable)) {
                    return executeCommandForCheck(pythonExecutable + " -m spotdl --version");
                }
            }
        } catch (Exception e) {
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
            // Try as Python module with each variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isCommandAvailable(pythonExecutable)) {
                    return executeCommandForCheck(pythonExecutable + " -m whisper -h");
                }
            }
        } catch (Exception e) {
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
            executeCommandAsRoot("apt update && apt install -y python3 python3-pip", profileId);
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
        
        broadcastInstallationProgress("python", 100, false, profileId);
        broadcast("Python installation completed\n", profileId);
    }
    
    @Override
    public void installSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Installing SpotDL via pip...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        executeCommand(pythonExecutable + " -m pip install spotdl", profileId);
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL installation completed\n", profileId);
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
        broadcast("Installing Whisper via pip...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        executeCommand(pythonExecutable + " -m pip install openai-whisper", profileId);
        
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
            throw new Exception("Command failed with exit code: " + exitCode + ": " + command);
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
        return "spotdl";
    }
    
    @Override
    public String getFFmpegCommand() {
        return "ffmpeg";
    }
    
    @Override
    public String getWhisperCommand() {
        return "whisper";
    }
    
    private boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean executeCommandForCheck(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
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