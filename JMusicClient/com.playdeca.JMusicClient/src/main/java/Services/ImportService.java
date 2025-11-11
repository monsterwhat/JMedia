package Services;

import API.WS.ImportStatusSocket;
import Controllers.SettingsController;
import Models.DTOs.ImportInstallationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ImportService {

    @Inject
    ImportStatusSocket importStatusSocket;
    
    @Inject SettingsController settings;

    public void download(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String sessionId) throws Exception {
        checkImportInstallation(); // Ensure SpotDL and its dependencies are installed

        if (downloadPath == null || downloadPath.isEmpty()) {
            throw new Exception("Download path cannot be empty.");
        }

        // Normalize the incoming downloadPath using Java's Path API
        Path normalizedDownloadPath = Paths.get(downloadPath);

        // Ensure the download path exists
        File downloadDir = normalizedDownloadPath.toFile();
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        List<String> command = new ArrayList<>();
        boolean isYouTubeUrl = url.contains("youtube.com") || url.contains("youtu.be");

        if (isYouTubeUrl) {
            // Use yt-dlp directly for YouTube URLs
            command.add("yt-dlp");
            command.add("-x"); // Extract audio
            command.add("--audio-format");
            command.add(format != null && !format.isEmpty() ? format : "mp3"); // Default to mp3 if format is not specified
            command.add("--output");
            command.add(normalizedDownloadPath.resolve("%(title)s.%(ext)s").toString()); // Output template for yt-dlp
            command.add(url);

            System.out.println("[INFO] Executing yt-dlp command for YouTube URL: " + String.join(" ", command));
            importStatusSocket.sendToSession(sessionId, "Executing yt-dlp command: " + String.join(" ", command));
        } else {
            // Use spotdl for Spotify URLs or other general cases
            command.add("spotdl");
            command.add(url);
            command.add("--output");
            command.add(normalizedDownloadPath.toString());

            if (format != null && !format.isEmpty()) {
                command.add("--output-format");
                command.add(format);
            }

            if (downloadThreads != null && downloadThreads > 0) {
                command.add("--download-threads");
                command.add(downloadThreads.toString());
            }
            if (searchThreads != null && searchThreads > 0) {
                command.add("--search-threads");
                command.add(searchThreads.toString());
            }
            System.out.println("[INFO] Executing spotdl command: " + String.join(" ", command));
            importStatusSocket.sendToSession(sessionId, "Executing spotdl command: " + String.join(" ", command));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // Set PYTHONUNBUFFERED environment variable for Python processes (like spotdl)
        // to ensure real-time output flushing.
        if (!isYouTubeUrl) { // Only apply to spotdl, as yt-dlp seems to handle buffering fine
            processBuilder.environment().put("PYTHONUNBUFFERED", "1");
        }
        processBuilder.redirectErrorStream(true); // Redirect error stream to output stream
        Process process = processBuilder.start();

        try (InputStreamReader reader = new InputStreamReader(process.getInputStream())) {
            char[] buffer = new char[1024]; // Read in chunks of 1KB
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, charsRead);
                importStatusSocket.sendToSession(sessionId, chunk);
            }
        } catch (IOException e) {
            System.err.println("[ERROR] IOException while reading process output for session " + sessionId + ": " + e.getMessage());
            e.printStackTrace(); // Print stack trace for detailed error
            importStatusSocket.sendToSession(sessionId, "ERROR: Failed to read process output: " + e.getMessage());
            throw e; // Re-throw to be caught by the outer catch block
        }

        System.out.println("[INFO] Waiting for process to complete for session: " + sessionId);
        int exitCode = process.waitFor();
        System.out.println("[INFO] Process completed with exit code: " + exitCode + " for session: " + sessionId);

        if (exitCode != 0) {
            String errorMessage = (isYouTubeUrl ? "yt-dlp" : "SpotDL") + " process exited with error code " + exitCode + ". Check logs for details.";
            System.err.println("[ERROR] " + errorMessage);
            importStatusSocket.sendToSession(sessionId, "ERROR: " + errorMessage);
            throw new Exception(errorMessage);
        }

        String successMessage = (isYouTubeUrl ? "yt-dlp" : "SpotDL") + " download completed successfully for URL: " + url;
        System.out.println("[INFO] " + successMessage);
        importStatusSocket.sendToSession(sessionId, successMessage);

        // Trigger a library scan after successful import
        settings.scanImportFolder();
    }

    public ImportInstallationStatus getInstallationStatus() {
        boolean pythonInstalled = false;
        String pythonMessage = "";
        boolean importInstalled = false;
        String importMessage = "";
        boolean ffmpegInstalled = false;
        String ffmpegMessage = "";

        // Check for Python
        String pythonExecutable = null;
        String pythonVersionOutput = "";
        int pythonExitCode = -1;
        boolean microsoftStoreAliasDetected = false;

        // Attempt 1: Try 'python --version'
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "--version");
            pb.redirectErrorStream(true);
            Process pythonProcess = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()));
            String line;
            StringBuilder outputBuilder = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                outputBuilder.append(line).append(System.lineSeparator());
            }
            pythonVersionOutput = outputBuilder.toString();
            try {
                pythonExitCode = pythonProcess.waitFor();
            } catch (InterruptedException ex) {
                System.err.println("[ERROR] " + ex.getMessage());
                ex.printStackTrace();
            }

            if (pythonExitCode == 0) {
                pythonExecutable = "python";
                pythonInstalled = true;
                pythonMessage = "Python found: " + pythonVersionOutput.trim();
            } else {
                if (pythonVersionOutput.contains("Microsoft Store") || pythonVersionOutput.contains("App execution aliases")) {
                    microsoftStoreAliasDetected = true;
                }
                System.err.println("[WARN] Attempted 'python --version' failed. Output: " + pythonVersionOutput.trim());
            }
        } catch (IOException e) {
            System.err.println("[WARN] Failed to execute 'python --version'. Error: " + e.getMessage());
            e.printStackTrace();
        }

        // Attempt 2: If 'python' failed, try 'py -3 --version' (common on Windows)
        if (pythonExecutable == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("py", "-3", "--version");
                pb.redirectErrorStream(true);
                Process pythonProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()));
                String line;
                StringBuilder outputBuilder = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append(System.lineSeparator());
                }
                pythonVersionOutput = outputBuilder.toString();
                try {
                    pythonExitCode = pythonProcess.waitFor();
                } catch (InterruptedException ex) {
                    System.err.println("[ERROR] " + ex.getMessage());
                    ex.printStackTrace();
                }

                if (pythonExitCode == 0) {
                    pythonExecutable = "py -3";
                    pythonInstalled = true;
                    pythonMessage = "Python found: " + pythonVersionOutput.trim();
                } else {
                    System.err.println("[WARN] Attempted 'py -3 --version' failed. Output: " + pythonVersionOutput.trim());
                }
            } catch (IOException e) {
                System.err.println("[WARN] Failed to execute 'py -3 --version'. Error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Final Python message if not installed
        if (!pythonInstalled) {
            pythonMessage = "Python is not installed or not found in PATH. ";
            if (microsoftStoreAliasDetected) {
                pythonMessage += "The 'python' command is currently aliased to the Microsoft Store. ";
                pythonMessage += "Please install Python from python.org and ensure it's added to your PATH, or disable the 'Python' app execution alias in Windows Settings. ";
            } else {
                pythonMessage += "Please install Python from python.org and ensure it's added to your system's PATH. ";
                pythonMessage += "On Windows, consider installing the 'py' launcher. ";
            }
            pythonMessage += "Attempted 'python --version' and 'py -3 --version'.";
        } else {
            System.out.println("[INFO] Python check passed using '" + pythonExecutable + "'.");
        }

        // Check for spotdl
        try {
            Process importProcess = new ProcessBuilder("spotdl", "--version").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(importProcess.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
            int exitCode;

            exitCode = importProcess.waitFor();

            if (exitCode == 0) {
                importInstalled = true;
                importMessage = "SpotDL found: " + output.toString().trim();
            } else {
                importMessage = "SpotDL is not installed or not found in PATH. Please install SpotDL (pip install spotdl). Output: " + output.toString();
            }
            System.out.println("[INFO] SpotDL check passed: " + output.toString().trim());
        } catch (IOException e) {
            importMessage = "Failed to execute 'spotdl --version'. SpotDL might not be installed or configured correctly. Error: " + e.getMessage();
            e.printStackTrace();
        } catch (InterruptedException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            ex.printStackTrace();
        }

        // Check for ffmpeg
        try {
            Process ffmpegProcess = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
            int exitCode;

            exitCode = ffmpegProcess.waitFor();

            if (exitCode == 0) {
                ffmpegInstalled = true;
                ffmpegMessage = "FFmpeg found: " + output.toString().trim().split("\n")[0];
            } else {
                ffmpegMessage = "FFmpeg is not installed or not found in PATH. Please install FFmpeg. Output: " + output.toString();
            }
            System.out.println("[INFO] FFmpeg check passed: " + output.toString().trim().split("\n")[0]); // Log only the first line
        } catch (IOException e) {
            ffmpegMessage = "Failed to execute 'ffmpeg -version'. FFmpeg might not be installed or configured correctly. Error: " + e.getMessage();
            e.printStackTrace();
        } catch (InterruptedException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            ex.printStackTrace();
        }

        return new ImportInstallationStatus(pythonInstalled, importInstalled, ffmpegInstalled, pythonMessage, importMessage, ffmpegMessage);
    }

    private void checkImportInstallation() throws Exception {
        ImportInstallationStatus status = getInstallationStatus();
        if (!status.isAllInstalled()) {
            StringBuilder errorMessage = new StringBuilder("SpotDL functionality requires the following external tools:\n");
            if (!status.pythonInstalled) {
                errorMessage.append("- Python: ").append(status.pythonMessage).append("\n");
            }
            if (!status.spotdlInstalled) {
                errorMessage.append("- SpotDL: ").append(status.spotdlMessage).append("\n");
            }
            if (!status.ffmpegInstalled) {
                errorMessage.append("- FFmpeg: ").append(status.ffmpegMessage).append("\n");
            }
            throw new Exception(errorMessage.toString());
        }
    }
}
