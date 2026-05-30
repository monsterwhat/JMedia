package Services;

import Models.Video;
import Models.SubtitleTrack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ParakeetService {

    private static final Logger LOG = LoggerFactory.getLogger(ParakeetService.class);

    private static final java.util.Map<String, String> LANGUAGE_MAP = java.util.Map.ofEntries(
        java.util.Map.entry("bg", "Bulgarian"),
        java.util.Map.entry("hr", "Croatian"),
        java.util.Map.entry("cs", "Czech"),
        java.util.Map.entry("da", "Danish"),
        java.util.Map.entry("nl", "Dutch"),
        java.util.Map.entry("en", "English"),
        java.util.Map.entry("et", "Estonian"),
        java.util.Map.entry("fi", "Finnish"),
        java.util.Map.entry("fr", "French"),
        java.util.Map.entry("de", "German"),
        java.util.Map.entry("el", "Greek"),
        java.util.Map.entry("hu", "Hungarian"),
        java.util.Map.entry("it", "Italian"),
        java.util.Map.entry("lv", "Latvian"),
        java.util.Map.entry("lt", "Lithuanian"),
        java.util.Map.entry("mt", "Maltese"),
        java.util.Map.entry("pl", "Polish"),
        java.util.Map.entry("pt", "Portuguese"),
        java.util.Map.entry("ro", "Romanian"),
        java.util.Map.entry("ru", "Russian"),
        java.util.Map.entry("sk", "Slovak"),
        java.util.Map.entry("sl", "Slovenian"),
        java.util.Map.entry("es", "Spanish"),
        java.util.Map.entry("sv", "Swedish"),
        java.util.Map.entry("uk", "Ukrainian")
    );

    @Inject
    SubtitleDownloadService subtitleDownloadService;

    @Inject
    Services.Platform.PlatformOperationsFactory platformOperationsFactory;

    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private static final Path SCRIPT_PATH = Paths.get("src", "main", "resources", "scripts", "run_parakeet.py");

    public boolean isParakeetAvailable() {
        try {
            Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            String python = platformOps.findPythonExecutable();

            ProcessBuilder pb = new ProcessBuilder(
                python, "-c",
                "from transformers import AutoModelForTDT, AutoProcessor; " +
                "AutoProcessor.from_pretrained('nvidia/parakeet-tdt-0.6b-v3'); " +
                "print('ok')"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("Parakeet not available: {}", e.getMessage());
            return false;
        }
    }

    public static String getLanguageName(String isoCode) {
        return LANGUAGE_MAP.getOrDefault(isoCode, "English");
    }

    public static java.util.Map<String, String> getSupportedLanguages() {
        return LANGUAGE_MAP;
    }

    public void cancelGeneration() {
        cancelled.set(true);
        Process p = currentProcess.get();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            LOG.info("Parakeet process forcibly terminated");
        }
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode) {
        return generateSubtitle(video, languageCode, null);
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode, Consumer<Double> progressCallback) {
        cancelled.set(false);
        String languageName = getLanguageName(languageCode);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (cancelled.get()) {
                    throw new InterruptedException("Generation cancelled");
                }

                if (video.path == null || video.path.isBlank()) {
                    throw new RuntimeException("Video path is null or empty for: " + video.filename);
                }
                Path videoPath = Paths.get(video.path);
                if (!Files.exists(videoPath)) {
                    throw new RuntimeException("Video file not found: " + video.path);
                }
                Path outputDir = videoPath.getParent();

                LOG.info("Starting Parakeet transcription for: {} (language: {})", video.filename, languageName);

                Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
                String pythonExec = platformOps.findPythonExecutable();

                // Resolve script path
                Path scriptPath;
                if (SCRIPT_PATH.toFile().exists()) {
                    scriptPath = SCRIPT_PATH;
                } else {
                    scriptPath = Paths.get("resources", "scripts", "run_parakeet.py");
                }
                if (!scriptPath.toFile().exists()) {
                    scriptPath = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "scripts", "run_parakeet.py");
                }

                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(pythonExec);
                command.add(scriptPath.toString());
                command.add("--audio");
                command.add(videoPath.toString());
                command.add("--output");
                command.add(outputDir.toString());
                command.add("--language");
                command.add(languageCode);

                LOG.debug("Parakeet command: {}", String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentProcess.set(process);

                // Capture output and parse progress
                java.util.ArrayList<String> outputLines = new java.util.ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled.get()) {
                            process.destroyForcibly();
                            throw new InterruptedException("Generation cancelled");
                        }

                        outputLines.add(line);
                        if (outputLines.size() > 20) {
                            outputLines.remove(0);
                        }

                        if (line.startsWith("PROGRESS:")) {
                            try {
                                double progress = Double.parseDouble(line.substring(9));
                                if (progressCallback != null) {
                                    progressCallback.accept(progress);
                                }
                            } catch (NumberFormatException e) {
                                LOG.debug("Failed to parse progress from: {}", line);
                            }
                        } else if (line.startsWith("DEVICE:")) {
                            LOG.info("Parakeet using device: {}", line.substring(7));
                        } else if (line.startsWith("PARAKEET:")) {
                            LOG.info("Parakeet: {}", line.substring(9));
                        } else if (line.startsWith("ERROR:")) {
                            LOG.warn("Parakeet: {}", line);
                        } else if (line.startsWith("WARN:")) {
                            LOG.warn("Parakeet: {}", line);
                        } else {
                            LOG.debug("Parakeet: {}", line);
                        }
                    }
                }

                boolean finished = process.waitFor(60, TimeUnit.MINUTES);
                currentProcess.set(null);
                if (cancelled.get()) {
                    if (process.isAlive()) process.destroyForcibly();
                    throw new InterruptedException("Generation cancelled");
                }
                if (!finished) {
                    process.destroyForcibly();
                    LOG.error("Parakeet process timed out after 60 minutes for: {}", video.filename);
                    throw new RuntimeException("Parakeet process timed out after 60 minutes");
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    LOG.info("Parakeet transcription completed for: {}", video.filename);

                    // Refresh tracks so the new .srt file is detected
                    subtitleDownloadService.refreshSubtitleTracks(video);

                    // Mark generated tracks as AI-generated (needs transaction for background thread)
                    QuarkusTransaction.requiringNew().run(() -> {
                        List<SubtitleTrack> tracks = SubtitleTrack.list("video.id", video.id);
                        for (SubtitleTrack track : tracks) {
                            if (!track.isAiGenerated) {
                                track.isAiGenerated = true;
                                track.persist();
                            }
                        }
                    });

                    return "Success";
                } else {
                    String details = String.join("\n", outputLines.subList(Math.max(0, outputLines.size() - 10), outputLines.size()));
                    LOG.error("Parakeet failed with exit code: {}. Last output:\n{}", exitCode, details);
                    throw new RuntimeException("Parakeet process failed (exit " + exitCode + "). Last output: " + details);
                }

            } catch (InterruptedException e) {
                LOG.info("Parakeet transcription cancelled for: {}", video.filename);
                throw new RuntimeException("Generation cancelled");
            } catch (Exception e) {
                LOG.error("Error transcribing with Parakeet", e);
                throw new RuntimeException("Error transcribing with Parakeet: " + e.getMessage());
            }
        });
    }
}
