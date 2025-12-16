package Services.Platform;

import API.WS.ImportStatusSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class WindowsPlatformOperations implements PlatformOperations {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsPlatformOperations.class);
    
    @Inject
    ImportStatusSocket importStatusSocket;
    
    @Override
    public boolean isPackageMangerInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("choco", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isPythonInstalled() {
        try {
            // Try python first
            ProcessBuilder pb = new ProcessBuilder("python", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (process.waitFor() == 0) {
                return true;
            }
            
            // Try py -3
            pb = new ProcessBuilder("py", "-3", "--version");
            pb.redirectErrorStream(true);
            process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("where", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPythonModuleAvailable(String pythonExecutable, String moduleName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-m", moduleName, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isWhisperModuleAvailable(String pythonExecutable) {
        try {
            // Try multiple approaches to detect whisper
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-c", "import whisper; print('whisper_available')");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            // If direct import works, whisper is available
            if (exitCode == 0) {
                return true;
            }
            
            // Fallback to help command check
            pb = new ProcessBuilder(pythonExecutable, "-m", "whisper", "--help");
            pb.redirectErrorStream(true);
            process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            
            String outputStr = output.toString().toLowerCase();
            exitCode = process.waitFor();
            
            // Check for multiple indicators that whisper is installed
            return exitCode == 0 && (
                outputStr.contains("usage:") || 
                outputStr.contains("whisper") || 
                outputStr.contains("openai-whisper") ||
                outputStr.contains("transcribe")
            );
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isSpotdlInstalled() {
        try {
            // Try direct command first
            if (isCommandAvailable("spotdl")) {
                return true;
            }
            
            // Try as Python module with each Python variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isPythonModuleAvailable(pythonExecutable, "spotdl")) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    } 
    
    @Override
    public boolean isFFmpegInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isWhisperInstalled() {
        try {
            // Try as Python module with each Python variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isWhisperModuleAvailable(pythonExecutable)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
    
    @Override
    public void installPackageManger(Long profileId) throws Exception {
        broadcastInstallationProgress("choco", 0, true, profileId);
        broadcast("Installing Chocolatey using administrator PowerShell...\n", profileId);
        
        String chocoInstallScript = "Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))";
        
        try {
            executePowerShellCommandAsAdmin(chocoInstallScript, profileId);
            broadcastInstallationProgress("choco", 100, false, profileId);
            broadcast("Chocolatey installation completed\n", profileId);
        } catch (Exception e) {
            broadcast("ERROR: Failed to install Chocolatey: " + e.getMessage() + "\n", profileId);
            throw new Exception("Chocolatey installation failed: " + e.getMessage());
        }
    }
    
    @Override
    public void installPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Installing Python via Chocolatey...\n", profileId);
        
        try {
            String chocoInstallScript = "choco install python --version=3.13.9 -y";
            executePowerShellCommandAsAdmin(chocoInstallScript, profileId);
            broadcastInstallationProgress("python", 100, false, profileId);
            broadcast("Python installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey not available, downloading Python installer...\n", profileId);
            broadcastInstallationProgress("python", 25, true, profileId);
            
            String downloadScript = "Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.13.9/python-3.13.9-amd64.exe' -OutFile '$env:TEMP\\python-installer.exe'";
            executePowerShellCommandAsAdmin(downloadScript, profileId);
            
            broadcastInstallationProgress("python", 50, true, profileId);
            broadcast("Installing Python (this may take a few minutes)...\n", profileId);
            
            String installScript = "Start-Process -FilePath '$env:TEMP\\python-installer.exe' -ArgumentList '/quiet InstallAllUsers=1 PrependPath=1 Include_test=0' -Wait -Verb RunAs";
            executePowerShellCommandAsAdmin(installScript, profileId);
            
            executePowerShellCommandAsAdmin("Remove-Item '$env:TEMP\\python-installer.exe' -ErrorAction SilentlyContinue", profileId);
            
            broadcastInstallationProgress("python", 100, false, profileId);
            broadcast("Python installation completed via manual installer\n", profileId);
        }
    }
    
    @Override
    public void installSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Installing SpotDL via pip...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        String installScript = pythonExecutable + " -m pip install spotdl";
        executeCommand(installScript, profileId);
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL installation completed\n", profileId);
    }
    
    @Override
    public void installFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Installing FFmpeg via Chocolatey...\n", profileId);
        
        try {
            String chocoInstallScript = "choco install ffmpeg -y";
            executePowerShellCommandAsAdmin(chocoInstallScript, profileId);
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey not available, downloading FFmpeg manually...\n", profileId);
            broadcastInstallationProgress("ffmpeg", 25, true, profileId);
            
            String downloadScript = "Invoke-WebRequest -Uri 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip' -OutFile '$env:TEMP\\ffmpeg.zip'";
            executePowerShellCommandAsAdmin(downloadScript, profileId);
            
            broadcastInstallationProgress("ffmpeg", 50, true, profileId);
            broadcast("Extracting FFmpeg...\n", profileId);
            
            String extractScript = "Expand-Archive -Path '$env:TEMP\\ffmpeg.zip' -DestinationPath '$env:TEMP\\ffmpeg' -Force";
            executePowerShellCommandAsAdmin(extractScript, profileId);
            
            broadcastInstallationProgress("ffmpeg", 75, true, profileId);
            broadcast("Installing FFmpeg to system...\n", profileId);
            
            String installScript = "New-Item -Path 'C:\\Program Files\\FFmpeg' -ItemType Directory -Force; "
                    + "Get-ChildItem '$env:TEMP\\ffmpeg\\ffmpeg-*' | ForEach-Object { Move-Item $_.FullName 'C:\\Program Files\\FFmpeg\\bin' -Force }; "
                    + "[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';C:\\Program Files\\FFmpeg\\bin', 'Machine')";
            executePowerShellCommandAsAdmin(installScript, profileId);
            
            executePowerShellCommandAsAdmin("Remove-Item '$env:TEMP\\ffmpeg.zip' -ErrorAction SilentlyContinue; Remove-Item '$env:TEMP\\ffmpeg' -Recurse -ErrorAction SilentlyContinue", profileId);
            
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg installation completed via manual installer\n", profileId);
        }
    }
    
    @Override
    public void installWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Installing Whisper via pip...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        String installScript = pythonExecutable + " -m pip install openai-whisper";
        executeCommand(installScript, profileId);
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper installation completed\n", profileId);
    }
    
    @Override
    public void uninstallPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Uninstalling Python...\n", profileId);
        
        String uninstallScript = "Get-WmiObject -Class Win32_Product | Where-Object {$_.Name -like '*Python*'} | ForEach-Object { $_.Uninstall() }";
        executePowerShellCommandAsAdmin(uninstallScript, profileId);
        
        String removeFromPathScript = "[Environment]::SetEnvironmentVariable('Path', ([Environment]::GetEnvironmentVariable('Path', 'Machine') -split ';' | Where-Object { $_ -notlike '*Python*' }) -join ';', 'Machine')";
        executePowerShellCommandAsAdmin(removeFromPathScript, profileId);
        
        broadcastInstallationProgress("python", 100, false, profileId);
        broadcast("Python uninstallation completed\n", profileId);
    }
    
    @Override
    public void uninstallSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Uninstalling SpotDL...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        String uninstallScript = pythonExecutable + " -m pip uninstall spotdl -y";
        executeCommand(uninstallScript, profileId);
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL uninstallation completed\n", profileId);
    }
    
    @Override
    public void uninstallFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Uninstalling FFmpeg via Chocolatey...\n", profileId);
        
        try {
            executeCommand("choco uninstall ffmpeg -y", profileId);
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg uninstallation completed\n", profileId);
        } catch (Exception e) {
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg uninstallation completed with warnings\n", profileId);
        }
    }
    
    @Override
    public void uninstallWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Uninstalling Whisper...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        String uninstallScript = pythonExecutable + " -m pip uninstall openai-whisper -y";
        executeCommand(uninstallScript, profileId);
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper uninstallation completed\n", profileId);
    }
    
    @Override
    public void executeCommand(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command.split(" "));
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
        executePowerShellCommandAsAdmin(command, profileId);
    }
    
    @Override
    public String findPythonExecutable() throws Exception {
        String[] pythonExecutables = getPythonExecutableVariants();
        
        for (String executable : pythonExecutables) {
            try {
                ProcessBuilder pb = new ProcessBuilder(executable, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                if (process.waitFor() == 0) {
                    LOGGER.info("Found Python executable: {}", executable);
                    return executable;
                }
            } catch (Exception e) {
                LOGGER.debug("Python executable {} not available: {}", executable, e.getMessage());
            }
        }
        
        throw new Exception("Python executable not found. Please ensure Python is installed and available in PATH.");
    }
    
    @Override
    public String getPackageManagerName() {
        return "Chocolatey";
    }
    
    @Override
    public String getPackageManagerInstallMessage() {
        return "Chocolatey is not installed or not found in PATH. Please install Chocolatey first.";
    }
    
    @Override
    public String getPythonInstallMessage() {
        return "Python is not installed or not found in PATH. Please install Python from python.org and ensure it's added to your system's PATH.";
    }
    
    @Override
    public String getSpotdlInstallMessage() {
        return "SpotDL is not installed or not found in PATH. Please install SpotDL (pip install spotdl).";
    }
    
    @Override
    public String getFFmpegInstallMessage() {
        return "FFmpeg is not installed or not found in PATH. Please install FFmpeg.";
    }
    
    @Override
    public String getWhisperInstallMessage() {
        return "Whisper is not installed or not found in PATH. Please install OpenAI's Whisper (pip install openai-whisper).";
    }
    
    @Override
    public String getSystemPythonCommand() {
        return "python";
    }
    
    @Override
    public String[] getPythonExecutableVariants() {
        return new String[]{"python", "py", "python3", "python3.13"};
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
    
    private void executePowerShellCommand(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", command);
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
            throw new Exception("PowerShell command failed with exit code: " + exitCode);
        }
    }
    
    private void executePowerShellCommandAsAdmin(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", "Start-Process powershell.exe -ArgumentList '-Command \"" + command.replace("\"", "\"\"") + "\"' -Verb RunAs -Wait");
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
            throw new Exception("Administrator PowerShell command failed with exit code: " + exitCode);
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