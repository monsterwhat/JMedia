package Services.Platform;

import API.WS.ImportStatusSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

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
    public String getParakeetPythonExecutable() throws Exception {
        return findPythonExecutable();
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

    private boolean isParakeetDepsAvailable(String pythonExecutable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-c", "import torch; import librosa; from transformers import AutoModelForTDT; print('parakeet_available')");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
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
    public boolean isYtdlpInstalled() {
        try {
            // Try direct command first
            if (isCommandAvailable("yt-dlp")) {
                return true;
            }
            
            // Try as Python module with each Python variant
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isPythonModuleAvailable(pythonExecutable, "yt_dlp")) {
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
            if (isCommandAvailable("ffmpeg")) {
                return true;
            }

            String[] chocolateyPaths = {
                "C:\\ProgramData\\chocolatey\\lib\\ffmpeg\\tools\\ffmpeg.exe",
                "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe"
            };
            for (String p : chocolateyPaths) {
                if (new File(p).exists()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
    
    @Override
    public boolean isParakeetInstalled() {
        try {
            String[] pythonExecutables = getPythonExecutableVariants();
            for (String pythonExecutable : pythonExecutables) {
                if (isParakeetDepsAvailable(pythonExecutable)) {
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
    public boolean isNodeInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void installNode(Long profileId) throws Exception {
        broadcastInstallationProgress("node", 0, true, profileId);
        broadcast("Installing Node.js via Chocolatey...\n", profileId);
        
        try {
            String chocoInstallScript = "choco install nodejs -y";
            executePowerShellCommandAsAdmin(chocoInstallScript, profileId);
            broadcastInstallationProgress("node", 100, false, profileId);
            broadcast("Node.js installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey not available, downloading Node.js installer...\n", profileId);
            broadcastInstallationProgress("node", 25, true, profileId);
            
            String downloadScript = "Invoke-WebRequest -Uri 'https://nodejs.org/dist/v22.13.1/node-v22.13.1-x64.msi' -OutFile '$env:TEMP\\node-installer.msi'";
            executePowerShellCommandAsAdmin(downloadScript, profileId);
            
            broadcastInstallationProgress("node", 50, true, profileId);
            broadcast("Installing Node.js (this may take a few minutes)...\n", profileId);
            
            String installScript = "Start-Process msiexec.exe -ArgumentList '/i', '$env:TEMP\\node-installer.msi', '/quiet', '/norestart' -Wait -Verb RunAs";
            executePowerShellCommandAsAdmin(installScript, profileId);
            
            executePowerShellCommandAsAdmin("Remove-Item '$env:TEMP\\node-installer.msi' -ErrorAction SilentlyContinue", profileId);
            
            broadcastInstallationProgress("node", 100, false, profileId);
            broadcast("Node.js installation completed via manual installer\n", profileId);
        }
    }
    
    @Override
    public String findNodeExecutable() throws Exception {
        // Try node command
        ProcessBuilder pb = new ProcessBuilder("node", "--version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (process.waitFor() == 0) {
            return "node";
        }
        return null;
    }
    
    @Override
    public String getNodeCommand() {
        return "node";
    }
    
    @Override
    public String getNodeInstallMessage() {
        return "Node.js not found. Install via Chocolatey or download from nodejs.org";
    }
    
    @Override
    public void uninstallNode(Long profileId) throws Exception {
        broadcast("Uninstalling Node.js via Chocolatey...\n", profileId);
        String chocoUninstallScript = "choco uninstall nodejs -y";
        executePowerShellCommandAsAdmin(chocoUninstallScript, profileId);
        broadcast("Node.js uninstallation completed\n", profileId);
    }
    
@Override
    public void installSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        
        String pythonExecutable = findPythonExecutable();
        
        try {
            broadcast("Installing SpotDL via pip...\n", profileId);
            String installScript = pythonExecutable + " -m pip install --upgrade spotdl";
            executeCommand(installScript, profileId);
            broadcastInstallationProgress("spotdl", 100, false, profileId);
            broadcast("SpotDL installation completed\n", profileId);
        } catch (Exception e) {
            broadcast("Standard installation failed, trying user install...\n", profileId);
            broadcastInstallationProgress("spotdl", 50, true, profileId);
            
            try {
                String userInstallScript = pythonExecutable + " -m pip install --user --upgrade spotdl";
                executeCommand(userInstallScript, profileId);
                broadcastInstallationProgress("spotdl", 100, false, profileId);
                broadcast("SpotDL installation completed (user install)\n", profileId);
            } catch (Exception e2) {
                broadcast("User installation also failed. Please check logs for details.\n", profileId);
                broadcast("You may need to install SpotDL manually: pip install spotdl\n", profileId);
                throw new Exception("SpotDL installation failed: " + e2.getMessage(), e2);
            }
        }
    }
    
    @Override
    public void installYtdlp(Long profileId) throws Exception {
        broadcastInstallationProgress("ytdlp", 0, true, profileId);
        broadcast("Installing yt-dlp...\n", profileId);
        
        try {
            // Try Chocolatey first
            broadcast("Attempting to install yt-dlp via Chocolatey...\n", profileId);
            String chocoInstallScript = "choco install yt-dlp -y";
            executePowerShellCommandAsAdmin(chocoInstallScript, profileId);
            broadcastInstallationProgress("ytdlp", 100, false, profileId);
            broadcast("yt-dlp installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey installation failed, trying pip...\n", profileId);
            broadcastInstallationProgress("ytdlp", 50, true, profileId);
            
            // Fallback to pip installation
            String pythonExecutable = findPythonExecutable();
            String pipInstallScript = pythonExecutable + " -m pip install yt-dlp";
            executeCommand(pipInstallScript, profileId);
            
            broadcastInstallationProgress("ytdlp", 100, false, profileId);
            broadcast("yt-dlp installation completed via pip\n", profileId);
        }
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
    public void installParakeet(Long profileId) throws Exception {
        broadcastInstallationProgress("parakeet", 0, true, profileId);
        broadcast("Installing Parakeet dependencies (transformers, torch, librosa)...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        broadcast("Step 1/3: Installing torch...\n", profileId);
        executePipCommand(pythonExecutable + " -m pip install torch", profileId);
        broadcast("Step 2/3: Installing librosa...\n", profileId);
        executePipCommand(pythonExecutable + " -m pip install librosa", profileId);
        broadcast("Step 3/3: Installing transformers from source (Parakeet TDT)...\n", profileId);
        executePipCommand(pythonExecutable + " -m pip install git+https://github.com/huggingface/transformers", profileId);
        
        broadcastInstallationProgress("parakeet", 100, false, profileId);
        broadcast("Parakeet dependencies installation completed\n", profileId);
        broadcast("[PARAKEET_INSTALLATION_FINISHED]", profileId);
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
    public void uninstallYtdlp(Long profileId) throws Exception {
        broadcastInstallationProgress("ytdlp", 0, true, profileId);
        broadcast("Uninstalling yt-dlp...\n", profileId);
        
        try {
            // Try Chocolatey first
            executeCommand("choco uninstall yt-dlp -y", profileId);
            broadcastInstallationProgress("ytdlp", 100, false, profileId);
            broadcast("yt-dlp uninstallation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            // Fallback to pip uninstallation
            String pythonExecutable = findPythonExecutable();
            String pipUninstallScript = pythonExecutable + " -m pip uninstall yt-dlp -y";
            executeCommand(pipUninstallScript, profileId);
            broadcastInstallationProgress("ytdlp", 100, false, profileId);
            broadcast("yt-dlp uninstallation completed via pip\n", profileId);
        }
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
    public void uninstallParakeet(Long profileId) throws Exception {
        broadcastInstallationProgress("parakeet", 0, true, profileId);
        broadcast("Uninstalling Parakeet dependencies...\n", profileId);
        
        String pythonExecutable = findPythonExecutable();
        executePipCommand(pythonExecutable + " -m pip uninstall transformers librosa torch -y", profileId);
        
        broadcastInstallationProgress("parakeet", 100, false, profileId);
        broadcast("Parakeet dependencies uninstallation completed\n", profileId);
        broadcast("[PARAKEET_UNINSTALLATION_FINISHED]", profileId);
    }
    
    @Override
    public void executeCommand(String command, Long profileId) throws Exception {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command.split(" "));
            pb.redirectErrorStream(true);
            
            process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        broadcast(line.trim() + "\n", profileId);
                    }
                }
            }
            
            // Wait for process with timeout to prevent hanging
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                LOGGER.warn("Command process timed out: " + command);
                process.destroyForcibly();
                throw new Exception("Command timed out: " + command);
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new Exception("Command failed with exit code: " + exitCode + ": " + command);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void executePipCommand(String command, Long profileId) throws Exception {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command.split(" "));
            pb.redirectErrorStream(true);

            process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        broadcast(line.trim() + "\n", profileId);
                    }
                }
            }

            // Use 30-minute timeout for pip installs (torch is ~2GB)
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                LOGGER.warn("Pip command timed out after 30 min: " + command);
                process.destroyForcibly();
                throw new Exception("Pip command timed out: " + command);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new Exception("Pip command failed with exit code: " + exitCode + ": " + command);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
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
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(executable, "--version");
                pb.redirectErrorStream(true);
                process = pb.start();
                
                // Wait with timeout to prevent hanging
                boolean finished = process.waitFor(10, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    LOGGER.info("Found Python executable: {}", executable);
                    return executable;
                }
            } catch (Exception e) {
                LOGGER.debug("Python executable {} not available: {}", executable, e.getMessage());
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
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
    public String getYtdlpInstallMessage() {
        return "yt-dlp is not installed or not found in PATH. Please install yt-dlp (pip install yt-dlp or choco install yt-dlp).";
    }
    
    @Override
    public String getFFmpegInstallMessage() {
        return "FFmpeg is not installed or not found in PATH. Please install FFmpeg.";
    }
    
    @Override
    public String getParakeetInstallMessage() {
        return "Parakeet TDT dependencies not installed. Please install torch, librosa, and transformers from source.";
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
    public String getYtdlpCommand() {
        return "yt-dlp";
    }
    
    @Override
    public String getFFmpegCommand() {
        return "ffmpeg";
    }
    
    @Override
    public String getParakeetScriptCommand() {
        return "run_parakeet.py";
    }
    
    @Override
    public boolean shouldUseSpotdlDirectCommand() {
        // Windows typically uses python -m spotdl
        return false;
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
    
    @Override
    public String getCookiesStoragePath() {
        String userHome = System.getProperty("user.home");
        return userHome + File.separator + ".jmedia" + File.separator + "cookies.txt";
    }
    
    @Override
    public boolean validateCookiesFile(String cookiesPath) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(cookiesPath);
            if (!java.nio.file.Files.exists(path)) {
                return false;
            }
            
            String content = java.nio.file.Files.readString(path);
            if (content == null || content.trim().isEmpty()) {
                return false;
            }
            
            String[] lines = content.split("\r?\n");
            int validLines = 0;
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                String[] parts = line.split("\t");
                if (parts.length >= 7) {
                    validLines++;
                }
            }
            
            return validLines > 0;
        } catch (Exception e) {
            LOGGER.debug("Error validating cookies file: " + e.getMessage());
            return false;
        }
    }

    @Override
    public java.util.List<java.util.Map<String, String>> listFolders(String path) throws Exception {
        java.util.List<java.util.Map<String, String>> folders = new java.util.ArrayList<>();
        
        if (path == null || path.trim().isEmpty()) {
            File[] roots = File.listRoots();
            for (File root : roots) {
                java.util.Map<String, String> f = new java.util.HashMap<>();
                f.put("name", root.getPath());
                f.put("path", root.getAbsolutePath());
                folders.add(f);
            }
        } else {
            File currentDir = new File(path);
            if (currentDir.exists() && currentDir.isDirectory()) {
                File[] files = currentDir.listFiles(File::isDirectory);
                if (files != null) {
                    java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File f : files) {
                        java.util.Map<String, String> map = new java.util.HashMap<>();
                        map.put("name", f.getName());
                        map.put("path", f.getAbsolutePath());
                        folders.add(map);
                    }
                }
            }
        }
        return folders;
    }
}