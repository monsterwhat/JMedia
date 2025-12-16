package Services.Platform;

import API.WS.ImportStatusSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@ApplicationScoped
public class MacOSPlatformOperations implements PlatformOperations {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MacOSPlatformOperations.class);
    
    @Inject
    ImportStatusSocket importStatusSocket;
    
    @Override
    public boolean isPackageMangerInstalled() {
        return isCommandAvailable("brew") || isCommandAvailable("port");
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
        broadcast("Installing Homebrew (recommended package manager for macOS)...\n", profileId);
        broadcastInstallationProgress("brew", 0, true, profileId);
        
        String installScript = "/bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"";
        executeCommand(installScript, profileId);
        
        broadcastInstallationProgress("brew", 100, false, profileId);
        broadcast("Homebrew installation completed\n", profileId);
        broadcast("Note: You may need to add Homebrew to your PATH. Run: echo 'eval \"$(/opt/homebrew/bin/brew shellenv)\"' >> ~/.zprofile\n", profileId);
    }
    
    @Override
    public void installPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        
        if (isCommandAvailable("brew")) {
            broadcast("Installing Python using Homebrew...\n", profileId);
            executeCommand("brew install python", profileId);
        } else if (isCommandAvailable("port")) {
            broadcast("Installing Python using MacPorts...\n", profileId);
            executeCommand("sudo port install python3", profileId);
        } else {
            broadcast("No package manager found. Please install Python manually from python.org\n", profileId);
            broadcast("Download URL: https://www.python.org/downloads/macos/\n", profileId);
            throw new Exception("No package manager available. Please install Python manually.");
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
        
        if (isCommandAvailable("brew")) {
            broadcast("Installing FFmpeg using Homebrew...\n", profileId);
            executeCommand("brew install ffmpeg", profileId);
        } else if (isCommandAvailable("port")) {
            broadcast("Installing FFmpeg using MacPorts...\n", profileId);
            executeCommand("sudo port install ffmpeg", profileId);
        } else {
            throw new Exception("No package manager available. Please install FFmpeg manually.");
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
        
        if (isCommandAvailable("brew")) {
            broadcast("Uninstalling Python using Homebrew...\n", profileId);
            executeCommand("brew uninstall python", profileId);
        } else if (isCommandAvailable("port")) {
            broadcast("Uninstalling Python using MacPorts...\n", profileId);
            executeCommand("sudo port uninstall python3", profileId);
        } else {
            throw new Exception("No package manager available. Please uninstall Python manually.");
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
        
        if (isCommandAvailable("brew")) {
            broadcast("Uninstalling FFmpeg using Homebrew...\n", profileId);
            executeCommand("brew uninstall ffmpeg", profileId);
        } else if (isCommandAvailable("port")) {
            broadcast("Uninstalling FFmpeg using MacPorts...\n", profileId);
            executeCommand("sudo port uninstall ffmpeg", profileId);
        } else {
            throw new Exception("No package manager available. Please uninstall FFmpeg manually.");
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
        if (isCommandAvailable("brew")) return "Homebrew";
        if (isCommandAvailable("port")) return "MacPorts";
        return "None";
    }
    
    @Override
    public String getPackageManagerInstallMessage() {
        return "No package manager found. Please install Homebrew (recommended) or MacPorts.";
    }
    
    @Override
    public String getPythonInstallMessage() {
        return "Python is not installed. Please install Python using Homebrew: brew install python";
    }
    
    @Override
    public String getSpotdlInstallMessage() {
        return "SpotDL is not installed. Please install SpotDL using pip: pip install spotdl";
    }
    
    @Override
    public String getFFmpegInstallMessage() {
        return "FFmpeg is not installed. Please install FFmpeg using Homebrew: brew install ffmpeg";
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