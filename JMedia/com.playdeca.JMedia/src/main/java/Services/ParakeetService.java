package Services;

import Models.Video.Video;
import Models.Video.SubtitleTrack;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        java.util.Map.entry("uk", "Ukrainian"),
        java.util.Map.entry("ja", "Japanese"),
        java.util.Map.entry("zh", "Chinese"),
        java.util.Map.entry("ko", "Korean"),
        java.util.Map.entry("ar", "Arabic"),
        java.util.Map.entry("hi", "Hindi"),
        java.util.Map.entry("tr", "Turkish"),
        java.util.Map.entry("no", "Norwegian"),
        java.util.Map.entry("th", "Thai"),
        java.util.Map.entry("vi", "Vietnamese"),
        java.util.Map.entry("id", "Indonesian"),
        java.util.Map.entry("ms", "Malay"),
        java.util.Map.entry("he", "Hebrew")
    );

    @Inject
    SubtitleTrackService subtitleTrackService;

    @Inject
    Services.Platform.PlatformOperationsFactory platformOperationsFactory;

    @Inject
    FFmpegDiscoveryService ffmpegDiscoveryService;

    @Inject
    SettingsService settingsService;

    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Pollable state for the single-video generation path (started from the player modal).
     * The batch job flow (AiSubtitleJobService) passes its own progress callback and
     * tracks progress separately, so it does not update this state.
     */
    private final AtomicReference<GenerationState> generationState = new AtomicReference<>(new GenerationState());

    private static final Path SCRIPT_PATH = Paths.get("src", "main", "resources", "scripts", "run_parakeet.py");

    private static final java.util.Set<String> ALLOWED_SUBTITLE_EXTENSIONS = java.util.Set.of(
        ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx"
    );
    private static final java.util.Set<String> VIDEO_CONTAINER_EXTENSIONS = java.util.Set.of(
        ".mp4", ".mkv", ".avi", ".webm", ".ts", ".mov", ".m4v", ".flv", ".wmv", ".mpg", ".mpeg"
    );

    private static final ExecutorService PARKEET_EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("parakeet-").factory()
    );

    public boolean isParakeetAvailable() {
        try {
            Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            String python = platformOps.getParakeetPythonExecutable();

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
        GenerationState state = generationState.get();
        state.running = false;
        state.error = "Generation cancelled";
    }

    @PreDestroy
    void shutdown() {
        Process p = currentProcess.get();
        if (p != null && p.isAlive()) {
            LOG.info("Terminating in-flight Parakeet process (pid {}) on shutdown", p.pid());
            p.destroyForcibly();
        }
    }

    public java.util.Map<String, Object> getGenerationStatus() {
        GenerationState state = generationState.get();
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("running", state.running);
        status.put("videoId", state.running ? state.videoId : null);
        status.put("progress", state.progress);
        status.put("stage", state.stage != null ? state.stage : "idle");
        status.put("error", state.error);
        return status;
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode) {
        return generateSubtitle(video, languageCode, -1, null);
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode, Consumer<Double> progressCallback) {
        return generateSubtitle(video, languageCode, -1, progressCallback);
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode, int audioTrackIndex) {
        return generateSubtitle(video, languageCode, audioTrackIndex, null);
    }

    public CompletableFuture<String> generateSubtitle(Video video, String languageCode, int audioTrackIndex, Consumer<Double> progressCallback) {
        cancelled.set(false);
        // "spl" is a sublanguage id for Spanish (Latin America), not a
        // valid language code for Parakeet/NLLB; normalize it so translation runs and
        // the generated file carries the expected ".es.srt" suffix.
        if (languageCode != null && "spl".equalsIgnoreCase(languageCode.trim())) {
            languageCode = "es";
        }
        final String lang = languageCode;
        String languageName = getLanguageName(lang);

        final boolean trackState = (progressCallback == null);
        final GenerationState state = trackState ? startGenerationState(video.id) : null;

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

                LOG.info("Starting Parakeet transcription for: {} (language: {}, audioTrack: {})", video.filename, languageName, audioTrackIndex >= 0 ? audioTrackIndex : "default");

                Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
                String pythonExec = platformOps.getParakeetPythonExecutable();

                // Resolve script path (filesystem or extract from JAR)
                Path scriptPath;
                if (SCRIPT_PATH.toFile().exists()) {
                    scriptPath = SCRIPT_PATH;
                } else if (Paths.get("resources", "scripts", "run_parakeet.py").toFile().exists()) {
                    scriptPath = Paths.get("resources", "scripts", "run_parakeet.py");
                } else if (Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "scripts", "run_parakeet.py").toFile().exists()) {
                    scriptPath = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "scripts", "run_parakeet.py");
                } else {
                    // Extract from JAR to persistent location once
                    Path jmediaDir = Paths.get(System.getProperty("user.home"), ".jmedia", "scripts");
                    Files.createDirectories(jmediaDir);
                    scriptPath = jmediaDir.resolve("run_parakeet.py");
                    if (!scriptPath.toFile().exists()) {
                        try (InputStream is = getClass().getClassLoader().getResourceAsStream("scripts/run_parakeet.py")) {
                            if (is == null) {
                                throw new RuntimeException("Script 'scripts/run_parakeet.py' not found in classpath");
                            }
                            Files.copy(is, scriptPath);
                            LOG.info("Extracted Parakeet script from JAR to: {}", scriptPath);
                        }
                    }
                }

                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(pythonExec);
                command.add(scriptPath.toString());
                command.add("--audio");
                command.add(videoPath.toString());
                command.add("--output");
                command.add(outputDir.toString());
                command.add("--language");
                command.add(lang);
                if (video.primaryAudioLanguage != null && !video.primaryAudioLanguage.isBlank()) {
                    command.add("--source-language");
                    command.add(video.primaryAudioLanguage);
                }
                if (audioTrackIndex >= 0) {
                    command.add("--audio-index");
                    command.add(String.valueOf(audioTrackIndex));
                }

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
                                if (state != null) {
                                    state.progress = (int) Math.round(progress);
                                    state.stage = "transcribing";
                                }
                            } catch (NumberFormatException e) {
                                LOG.debug("Failed to parse progress from: {}", line);
                            }
                        } else if (line.startsWith("STAGE:")) {
                            if (state != null) {
                                state.stage = line.substring(6);
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

                try {
                    process.onExit().get(60, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    process.destroyForcibly();
                    LOG.error("Parakeet process timed out after 60 minutes for: {}", video.filename);
                    throw new RuntimeException("Parakeet process timed out after 60 minutes");
                }
                currentProcess.set(null);
                if (state != null) {
                    state.stage = "finalizing";
                }
                if (cancelled.get()) {
                    throw new InterruptedException("Generation cancelled");
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    LOG.info("Parakeet transcription completed for: {}", video.filename);

                    // Capture the set of track paths that existed before the refresh so the
                    // newly created AI track can be identified afterwards.
                    java.util.Set<String> preRefreshPaths = QuarkusTransaction.requiringNew().call(() -> {
                        java.util.Set<String> paths = new java.util.HashSet<>();
                        List<SubtitleTrack> preRefreshTracks = SubtitleTrack.list("video.id", video.id);
                        for (SubtitleTrack track : preRefreshTracks) {
                            if (track.fullPath != null) {
                                paths.add(track.fullPath);
                            }
                        }
                        return paths;
                    });

                    // Refresh tracks so the new .srt file is detected
                    subtitleTrackService.refreshSubtitleTracks(video);

                    // Mark ONLY the newly generated track as AI-generated (needs transaction for background thread)
                    QuarkusTransaction.requiringNew().run(() -> {
                        List<SubtitleTrack> tracks = SubtitleTrack.list("video.id", video.id);
                        SubtitleTrack target = null;
                        if (lang != null && !lang.isBlank()) {
                            String langSuffix = "." + lang.toLowerCase() + ".srt";
                            for (SubtitleTrack track : tracks) {
                                if ((track.fullPath != null && track.fullPath.toLowerCase().endsWith(langSuffix))
                                        || (track.filename != null && track.filename.toLowerCase().endsWith(langSuffix))) {
                                    target = track;
                                    break;
                                }
                            }
                        }
                        if (target == null) {
                            // Fallback: most recently added track (highest id) with a non-null
                            // fullPath that did not exist before the refresh.
                            target = tracks.stream()
                                .filter(t -> t.fullPath != null && !preRefreshPaths.contains(t.fullPath))
                                .max(java.util.Comparator.comparing(t -> t.id))
                                .orElse(null);
                        }
                        if (target != null) {
                            target.isAiGenerated = true;
                            target.persist();
                            LOG.info("Marked subtitle track '{}' (id={}) as AI-generated for video: {}", target.filename, target.id, video.filename);
                        } else {
                            LOG.warn("No new subtitle track found to mark as AI-generated for video: {}", video.filename);
                        }
                    });

                    completeGenerationState(state);
                    return "Success";
                } else {
                    String details = String.join("\n", outputLines.subList(Math.max(0, outputLines.size() - 10), outputLines.size()));
                    LOG.error("Parakeet failed with exit code: {}. Last output:\n{}", exitCode, details);
                    throw new RuntimeException("Parakeet process failed (exit " + exitCode + "). Last output: " + details);
                }

            } catch (InterruptedException e) {
                LOG.info("Parakeet transcription cancelled for: {}", video.filename);
                failGenerationState(state, "Generation cancelled");
                throw new RuntimeException("Generation cancelled");
            } catch (IOException e) {
                if (cancelled.get()) {
                    LOG.info("Parakeet transcription cancelled for: {}", video.filename);
                    failGenerationState(state, "Generation cancelled");
                    throw new RuntimeException("Generation cancelled");
                }
                LOG.error("Error transcribing with Parakeet", e);
                failGenerationState(state, "Error transcribing with Parakeet: " + e.getMessage());
                throw new RuntimeException("Error transcribing with Parakeet: " + e.getMessage());
            } catch (Exception e) {
                LOG.error("Error transcribing with Parakeet", e);
                failGenerationState(state, "Error transcribing with Parakeet: " + e.getMessage());
                throw new RuntimeException("Error transcribing with Parakeet: " + e.getMessage());
            }
        }, PARKEET_EXECUTOR);
    }

    public CompletableFuture<String> translateSubtitle(SubtitleTrack track, String languageCode, Consumer<Double> progressCallback) {
        cancelled.set(false);
        // "spl" is a sublanguage id for Spanish (Latin America), not a
        // valid language code for Parakeet/NLLB; normalize it so translation runs and
        // the generated file carries the expected ".es.srt" suffix.
        if (languageCode != null && "spl".equalsIgnoreCase(languageCode.trim())) {
            languageCode = "es";
        }
        final String lang = languageCode;
        String languageName = getLanguageName(lang);

        // Early-return guard: if the track is already in the target language, do not
        // start a process (compare normalized codes, e.g. "eng" vs "en").
        if (lang != null && track.languageCode != null && !track.languageCode.isBlank()) {
            String sourceLang = track.languageCode.toLowerCase();
            String targetLang = lang.toLowerCase();
            if (sourceLang.equalsIgnoreCase(targetLang)
                    || (track.languageCode.length() == 3 && sourceLang.startsWith(targetLang))) {
                LOG.info("Subtitle already in target language {} for track '{}' (id={}), skipping translation", lang, track.filename, track.id);
                return CompletableFuture.completedFuture("Success");
            }
        }

        if (track.video == null) {
            throw new RuntimeException("Subtitle track has no associated video: " + track.filename);
        }

        final boolean trackState = (progressCallback == null);
        final GenerationState state = trackState ? startGenerationState(track.video.id) : null;

        return CompletableFuture.supplyAsync(() -> {
            Path tempExtractedFile = null;
            try {
                if (cancelled.get()) {
                    throw new InterruptedException("Generation cancelled");
                }

                if (track.video.path == null || track.video.path.isBlank()) {
                    throw new RuntimeException("Video path is null or empty for: " + track.video.filename);
                }
                Path resolvedVideoPath = resolveVideoAbsolutePath(track.video.path);
                if (!Files.exists(resolvedVideoPath)) {
                    throw new RuntimeException("Video file not found: " + track.video.path);
                }
                Path outputDir = resolvedVideoPath.getParent();
                if (outputDir == null) {
                    outputDir = resolvedVideoPath.toAbsolutePath().getParent();
                }

                boolean isEmbedded = track.isEmbedded || track.trackIndex != null;
                String translateInputPath;

                if (isEmbedded) {
                    if (track.trackIndex == null) {
                        throw new RuntimeException("Embedded subtitle track has no stream index (trackIndex is null): " + track.filename);
                    }
                    if (isImageBasedSubtitleCodec(track.codec)) {
                        throw new RuntimeException("Embedded subtitle codec '" + track.codec + "' is image-based (PGS/DVD) and cannot be translated as text: " + track.filename);
                    }
                    LOG.info("Embedded subtitle track detected (index {} codec {}), extracting via ffmpeg for translation: {}", track.trackIndex, track.codec, track.filename);
                    tempExtractedFile = extractEmbeddedSubtitleToTempFile(track, resolvedVideoPath);
                    if (tempExtractedFile == null || !Files.exists(tempExtractedFile) || Files.size(tempExtractedFile) == 0) {
                        throw new RuntimeException("Failed to extract embedded subtitle track " + track.trackIndex + " — ffmpeg produced no output for: " + track.filename);
                    }
                    String extractedExt = getFileExtension(tempExtractedFile.toString());
                    String extractedExtLower = extractedExt != null ? "." + extractedExt.toLowerCase() : "";
                    if (!ALLOWED_SUBTITLE_EXTENSIONS.contains(extractedExtLower)) {
                        throw new RuntimeException("Extracted subtitle has unsupported extension '" + extractedExtLower + "': " + tempExtractedFile);
                    }
                    translateInputPath = tempExtractedFile.toAbsolutePath().toString();
                    LOG.info("Extracted embedded track {} to temp file {} ({} bytes)", track.trackIndex, translateInputPath, Files.size(tempExtractedFile));
                } else {
                    if (track.fullPath == null || track.fullPath.isBlank()) {
                        throw new RuntimeException("Subtitle track has no file path: " + track.filename);
                    }
                    String ext = getFileExtension(track.fullPath);
                    String extLower = ext != null ? "." + ext.toLowerCase() : "";
                    if (ext == null || ext.isBlank()) {
                        throw new RuntimeException("Subtitle file has no extension and cannot be validated as subtitle: " + track.fullPath);
                    }
                    if (VIDEO_CONTAINER_EXTENSIONS.contains(extLower)) {
                        throw new RuntimeException("Refusing to translate video container '" + track.fullPath + "' (." + ext + " is a video format, not a subtitle format). For embedded tracks, extraction via ffmpeg is required.");
                    }
                    if (!ALLOWED_SUBTITLE_EXTENSIONS.contains(extLower)) {
                        throw new RuntimeException("Subtitle file has unsupported extension '." + ext + "' (expected .srt/.vtt/.ass/.ssa/.sub/.idx): " + track.fullPath + " — video containers (.mp4/.mkv/.avi/.webm/.ts) cannot be translated");
                    }
                    Path inputPath = Paths.get(track.fullPath);
                    if (!inputPath.isAbsolute()) {
                        Path videoDir = resolvedVideoPath.getParent();
                        if (videoDir != null) {
                            inputPath = videoDir.resolve(inputPath).normalize();
                        }
                    }
                    if (!Files.exists(inputPath)) {
                        throw new RuntimeException("Subtitle file not found: " + track.fullPath);
                    }
                    translateInputPath = inputPath.toAbsolutePath().toString();
                }

                LOG.info("Starting Parakeet subtitle translation for: {} (track: {}, language: {})", track.video.filename, track.filename, languageName);

                Services.Platform.PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
                String pythonExec = platformOps.getParakeetPythonExecutable();

                // Resolve script path (filesystem or extract from JAR)
                Path scriptPath;
                if (SCRIPT_PATH.toFile().exists()) {
                    scriptPath = SCRIPT_PATH;
                } else if (Paths.get("resources", "scripts", "run_parakeet.py").toFile().exists()) {
                    scriptPath = Paths.get("resources", "scripts", "run_parakeet.py");
                } else if (Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "scripts", "run_parakeet.py").toFile().exists()) {
                    scriptPath = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "scripts", "run_parakeet.py");
                } else {
                    // Extract from JAR to persistent location once
                    Path jmediaDir = Paths.get(System.getProperty("user.home"), ".jmedia", "scripts");
                    Files.createDirectories(jmediaDir);
                    scriptPath = jmediaDir.resolve("run_parakeet.py");
                    if (!scriptPath.toFile().exists()) {
                        try (InputStream is = getClass().getClassLoader().getResourceAsStream("scripts/run_parakeet.py")) {
                            if (is == null) {
                                throw new RuntimeException("Script 'scripts/run_parakeet.py' not found in classpath");
                            }
                            Files.copy(is, scriptPath);
                            LOG.info("Extracted Parakeet script from JAR to: {}", scriptPath);
                        }
                    }
                }

                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(pythonExec);
                command.add(scriptPath.toString());
                command.add("--translate");
                command.add(translateInputPath);
                command.add("--output");
                command.add(outputDir.toString());
                command.add("--language");
                command.add(lang);
                if (track.languageCode != null && !track.languageCode.isBlank()) {
                    command.add("--source-language");
                    command.add(track.languageCode);
                }

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
                                if (state != null) {
                                    state.progress = (int) Math.round(progress);
                                    state.stage = "translating";
                                }
                            } catch (NumberFormatException e) {
                                LOG.debug("Failed to parse progress from: {}", line);
                            }
                        } else if (line.startsWith("STAGE:")) {
                            if (state != null) {
                                state.stage = line.substring(6);
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

                try {
                    process.onExit().get(60, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    process.destroyForcibly();
                    LOG.error("Parakeet process timed out after 60 minutes for: {}", track.video.filename);
                    throw new RuntimeException("Parakeet process timed out after 60 minutes");
                }
                currentProcess.set(null);
                if (state != null) {
                    state.stage = "finalizing";
                }
                if (cancelled.get()) {
                    throw new InterruptedException("Generation cancelled");
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    LOG.info("Parakeet subtitle translation completed for: {}", track.video.filename);

                    // Fix orphan stem for embedded translation: rename temp-stem output to video basename (collision-safe)
                    try {
                        String inputStem;
                        if (tempExtractedFile != null) {
                            String tmpName = tempExtractedFile.getFileName().toString();
                            int dotIdx = tmpName.lastIndexOf('.');
                            inputStem = dotIdx > 0 ? tmpName.substring(0, dotIdx) : tmpName;
                        } else {
                            Path inpPath = Paths.get(translateInputPath);
                            String inpName = inpPath.getFileName().toString();
                            int dotIdx = inpName.lastIndexOf('.');
                            inputStem = dotIdx > 0 ? inpName.substring(0, dotIdx) : inpName;
                        }
                        Path producedPath = outputDir.resolve(inputStem + "." + lang.toLowerCase() + ".srt");
                        boolean isTempStem = inputStem != null && inputStem.startsWith("jmedia-extract-");
                        if (isTempStem && Files.exists(producedPath)) {
                            String videoFileNameForBase = (track.video.filename != null && !track.video.filename.isBlank())
                                    ? track.video.filename
                                    : resolvedVideoPath.getFileName().toString();
                            int vDot = videoFileNameForBase.lastIndexOf('.');
                            String videoBaseName = vDot > 0 ? videoFileNameForBase.substring(0, vDot) : videoFileNameForBase;
                            String expectedFileName = videoBaseName + "." + lang.toLowerCase() + ".srt";
                            Path expectedPath = outputDir.resolve(expectedFileName);
                            if (!producedPath.getFileName().toString().equalsIgnoreCase(expectedFileName)) {
                                Path targetPath = expectedPath;
                                int counter = 1;
                                while (Files.exists(targetPath)) {
                                    String collisionName = videoBaseName + "." + lang.toLowerCase() + "_" + counter + ".srt";
                                    targetPath = outputDir.resolve(collisionName);
                                    counter++;
                                }
                                Files.move(producedPath, targetPath);
                                LOG.info("Renamed translated subtitle from '{}' to '{}' for video: {}", producedPath.getFileName(), targetPath.getFileName(), track.video.filename);
                            } else {
                                LOG.debug("Produced subtitle name already matches convention, no rename needed: {}", producedPath.getFileName());
                            }
                        } else if (!isTempStem) {
                            LOG.debug("Sidecar translation, keeping produced name as-is: {}", producedPath != null ? producedPath.getFileName() : "unknown");
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to rename translated subtitle to video basename for {}: {}", track.video.filename, e.getMessage(), e);
                    }

                    // Capture the set of track paths that existed before the refresh so the
                    // newly created AI track can be identified afterwards.
                    java.util.Set<String> preRefreshPaths = QuarkusTransaction.requiringNew().call(() -> {
                        java.util.Set<String> paths = new java.util.HashSet<>();
                        List<SubtitleTrack> preRefreshTracks = SubtitleTrack.list("video.id", track.video.id);
                        for (SubtitleTrack t : preRefreshTracks) {
                            if (t.fullPath != null) {
                                paths.add(t.fullPath);
                            }
                        }
                        return paths;
                    });

                    // Refresh tracks so the new .srt file is detected
                    subtitleTrackService.refreshSubtitleTracks(track.video);

                    // Mark ONLY the newly generated track as AI-generated (needs transaction for background thread)
                    QuarkusTransaction.requiringNew().run(() -> {
                        List<SubtitleTrack> tracks = SubtitleTrack.list("video.id", track.video.id);
                        SubtitleTrack target = null;
                        if (lang != null && !lang.isBlank()) {
                            String langSuffix = "." + lang.toLowerCase() + ".srt";
                            for (SubtitleTrack t : tracks) {
                                if ((t.fullPath != null && t.fullPath.toLowerCase().endsWith(langSuffix))
                                        || (t.filename != null && t.filename.toLowerCase().endsWith(langSuffix))) {
                                    target = t;
                                    break;
                                }
                            }
                        }
                        if (target == null) {
                            // Fallback: most recently added track (highest id) with a non-null
                            // fullPath that did not exist before the refresh.
                            target = tracks.stream()
                                .filter(t -> t.fullPath != null && !preRefreshPaths.contains(t.fullPath))
                                .max(java.util.Comparator.comparing(t -> t.id))
                                .orElse(null);
                        }
                        if (target != null) {
                            target.isAiGenerated = true;
                            target.persist();
                            LOG.info("Marked subtitle track '{}' (id={}) as AI-generated for video: {}", target.filename, target.id, track.video.filename);
                        } else {
                            LOG.warn("No new subtitle track found to mark as AI-generated for video: {}", track.video.filename);
                        }
                    });

                    completeGenerationState(state);
                    return "Success";
                } else {
                    String details = String.join("\n", outputLines.subList(Math.max(0, outputLines.size() - 10), outputLines.size()));
                    LOG.error("Parakeet failed with exit code: {}. Last output:\n{}", exitCode, details);
                    throw new RuntimeException("Parakeet process failed (exit " + exitCode + "). Last output: " + details);
                }

            } catch (InterruptedException e) {
                LOG.info("Parakeet subtitle translation cancelled for: {}", track.video.filename);
                failGenerationState(state, "Generation cancelled");
                throw new RuntimeException("Generation cancelled");
            } catch (IOException e) {
                if (cancelled.get()) {
                    LOG.info("Parakeet subtitle translation cancelled for: {}", track.video.filename);
                    failGenerationState(state, "Generation cancelled");
                    throw new RuntimeException("Generation cancelled");
                }
                LOG.error("Error translating subtitles with Parakeet", e);
                failGenerationState(state, "Error translating subtitles with Parakeet: " + e.getMessage());
                throw new RuntimeException("Error translating subtitles with Parakeet: " + e.getMessage());
            } catch (Exception e) {
                LOG.error("Error translating subtitles with Parakeet", e);
                failGenerationState(state, "Error translating subtitles with Parakeet: " + e.getMessage());
                throw new RuntimeException("Error translating subtitles with Parakeet: " + e.getMessage());
            } finally {
                if (tempExtractedFile != null) {
                    try {
                        Files.deleteIfExists(tempExtractedFile);
                        LOG.debug("Cleaned up temp extracted subtitle file: {}", tempExtractedFile);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete temp extracted subtitle file: {}", tempExtractedFile, e);
                    }
                }
            }
        }, PARKEET_EXECUTOR);
    }

    private Path resolveVideoAbsolutePath(String videoPath) {
        Path vPath = Paths.get(videoPath);
        if (vPath.isAbsolute()) {
            return vPath;
        }
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isBlank()) {
                return Paths.get(libraryPath, videoPath);
            }
        } catch (Exception e) {
            LOG.warn("Could not resolve video library path for {}: {}", videoPath, e.getMessage(), e);
        }
        return vPath;
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    private boolean isImageBasedSubtitleCodec(String codec) {
        if (codec == null) return false;
        String c = codec.toLowerCase(java.util.Locale.ROOT);
        return c.contains("pgs") || c.contains("pgssub") || c.contains("hdmv") || c.contains("dvd") || c.contains("dvb");
    }

    private Path extractEmbeddedSubtitleToTempFile(SubtitleTrack track, Path resolvedVideoPath) throws IOException, InterruptedException {
        String ffmpegPath = ffmpegDiscoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found — cannot extract embedded subtitle track");
        }
        String suffix = ".srt";
        if (track.format != null) {
            String fmt = track.format.toLowerCase(java.util.Locale.ROOT);
            if (fmt.equals("vtt") || fmt.equals("webvtt")) {
                suffix = ".vtt";
            } else if (fmt.equals("ass") || fmt.equals("ssa")) {
                suffix = ".ass";
            }
        }
        Path tempFile = Files.createTempFile("jmedia-extract-", suffix);
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-v");
        cmd.add("quiet");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(resolvedVideoPath.toAbsolutePath().toString());
        cmd.add("-map");
        cmd.add("0:" + track.trackIndex);
        cmd.add(tempFile.toAbsolutePath().toString());
        LOG.debug("Extracting embedded subtitle: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(tempFile);
            throw new IOException("FFmpeg subtitle extraction timed out for track " + track.trackIndex);
        }
        int exit = process.exitValue();
        if (exit != 0) {
            Files.deleteIfExists(tempFile);
            throw new IOException("FFmpeg failed to extract subtitle track " + track.trackIndex + " (exit " + exit + "): " + output);
        }
        return tempFile;
    }

    private GenerationState startGenerationState(long videoId) {
        GenerationState state = generationState.get();
        state.videoId = videoId;
        state.progress = 0;
        state.stage = "initializing";
        state.running = true;
        state.error = null;
        return state;
    }

    private static void completeGenerationState(GenerationState state) {
        if (state != null) {
            state.progress = 100;
            state.stage = "completed";
            state.running = false;
        }
    }

    private static void failGenerationState(GenerationState state, String error) {
        if (state != null) {
            state.running = false;
            state.error = error;
        }
    }

    private static final class GenerationState {
        volatile long videoId;
        volatile int progress;
        volatile String stage;
        volatile boolean running;
        volatile String error;
    }
}
