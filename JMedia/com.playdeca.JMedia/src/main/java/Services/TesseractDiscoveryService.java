package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class TesseractDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(TesseractDiscoveryService.class);

    private String tesseractPath;

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String resolveViaWhere(String tool) {
        if (!isWindows()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("where", tool);
            Process process = pb.start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("where.exe {} failed: {}", tool, e.getMessage());
        }
        return null;
    }

    private String resolveViaWhich(String tool) {
        if (isWindows()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("which", tool);
            Process process = pb.start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("which {} failed: {}", tool, e.getMessage());
        }
        return null;
    }

    private boolean probeExecutable(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "--version");
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.debug("Probe timed out for: {}", path);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("Probe failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    public synchronized String findTesseractExecutable() {
        if (tesseractPath != null) {
            return tesseractPath;
        }

        // 1. bare name PATH lookups
        if (probeExecutable("tesseract")) {
            tesseractPath = "tesseract";
            return tesseractPath;
        }
        if (probeExecutable("tesseract.exe")) {
            tesseractPath = "tesseract.exe";
            return tesseractPath;
        }

        // 2. resolve from system PATH (where.exe on Windows, which on Linux/macOS)
        String resolved = isWindows() ? resolveViaWhere("tesseract") : resolveViaWhich("tesseract");
        if (resolved != null && probeExecutable(resolved)) {
            tesseractPath = resolved;
            return tesseractPath;
        }

        // 3. hardcoded common install paths
        String[] hardcoded = {
            "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe",
            "C:\\ProgramData\\chocolatey\\bin\\tesseract.exe",
            "/usr/bin/tesseract",
            "/usr/local/bin/tesseract",
            "/opt/homebrew/bin/tesseract"
        };
        for (String p : hardcoded) {
            if (new File(p).exists() && probeExecutable(p)) {
                tesseractPath = p;
                return tesseractPath;
            }
        }

        LOG.warn("Tesseract not found after all detection attempts");
        return null;
    }
}
