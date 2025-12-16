package Services;

import API.WS.ImportStatusSocket;
import Services.Platform.PlatformOperations;
import Services.Platform.PlatformOperationsFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class DownloadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);

    @Inject
    ImportStatusSocket importStatusSocket;

    @Inject
    PlatformOperationsFactory platformOperationsFactory;

    @Inject
    InstallationService installationService;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Patterns for parsing download output
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT1 = Pattern.compile("Skipping \"([^\"]+)\" as it's already downloaded");
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT2 = Pattern.compile("Skipping '([^']+)' by '([^']+)' as it's already downloaded");
    private static final Pattern SPOTDL_DOWNLOAD_SUCCESS_PATTERN = Pattern.compile("Downloaded \"(.+?)\":");
    private static final Pattern YTDLP_MERGING_PATTERN = Pattern.compile("\\[ffmpeg\\] Merging formats into \"([^\"]+)\"");
    private static final Pattern RATE_LIMIT_PATTERN = Pattern.compile("too many 429 error responses|rate.*limit|429");

    /**
     * Downloads media from URL using appropriate tool.
     *
     * @param url The URL to download from
     * @param format The audio format to use
     * @param downloadThreads Number of download threads
     * @param searchThreads Number of search threads
     * @param downloadPath The download directory
     * @param profileId The profile ID for status updates
     * @return DownloadResult containing downloaded files and skipped songs
     * @throws Exception If download fails
     */
    public DownloadResult download(String url, String format, Integer downloadThreads, Integer searchThreads,
            String downloadPath, Long profileId) throws Exception {

        AtomicBoolean isDownloading = new AtomicBoolean(false);
        DownloadResult result = new DownloadResult();

        if (!isDownloading.compareAndSet(false, true)) {
            throw new Exception("A download is already in progress.");
        }

        try {
            installationService.validateInstallation();

            Path normalizedDownloadPath = Paths.get(downloadPath);
            File downloadDir = normalizedDownloadPath.toFile();
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            List<String> command = buildDownloadCommand(url, format, downloadThreads, searchThreads, normalizedDownloadPath);
            broadcast("Executing command: " + String.join(" ", command) + "\n", profileId);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().put("PYTHONUNBUFFERED", "1");
            processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseDownloadLine(line, result, profileId);
                }
            }

            int exitCode = process.waitFor();
            broadcast("Process finished with exit code: " + exitCode + "\n", profileId);

            // Handle rate limits with retry
            if (exitCode != 0 && isRateLimitHit(result.getOutputCache().toString()) && hasProcessedSongs(result)) {
                return retryWithReducedThreads(url, format, normalizedDownloadPath, profileId, result);
            } else if (exitCode != 0 && !hasProcessedSongs(result)) {
                throw new Exception("Download process exited with error code " + exitCode + " and no songs were processed.\nExternal tool output:\n" + result.getOutputCache().toString());
            } else if (exitCode != 0) {
                broadcast("WARNING: Download process exited with error code " + exitCode + " but some songs were processed. Continuing...\n", profileId);
            }

            return result;

        } finally {
            isDownloading.set(false);
        }
    }

    /**
     * Builds the appropriate download command based on URL type.
     */
    private List<String> buildDownloadCommand(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath) {
        try {
            List<String> command = new ArrayList<>();
            boolean isYouTubeUrl = url.contains("youtube.com") || url.contains("youtu.be");
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();

            if (isYouTubeUrl) {
                command.add("yt-dlp");
                command.add("-x");
                command.add("--audio-format");
                command.add(format != null && !format.isEmpty() ? format : "mp3");
                command.add("--output");
                command.add(downloadPath.resolve("%(title)s.%(ext)s").toString());
                command.add(url);
            } else {
                // Use SpotDL with detected execution method
                String pythonExecutable = installationService.findPythonExecutable();
                if (pythonExecutable != null) {
                    Collections.addAll(command, pythonExecutable.split(" "));
                    command.add("-m");
                    command.add("spotdl");
                } else {
                    command.add(platformOps.getSpotdlCommand());
                }

                command.add(url);
                command.add("--output");
                command.add(downloadPath.toString());

                if (format != null && !format.isEmpty()) {
                    command.add("--output-format");
                    command.add(format);
                }

                Integer combinedThreads = null;
                if (downloadThreads != null && downloadThreads > 0) {
                    combinedThreads = downloadThreads;
                } else if (searchThreads != null && searchThreads > 0) {
                    combinedThreads = searchThreads;
                }

                if (combinedThreads != null) {
                    command.add("--download-threads");
                    command.add(combinedThreads.toString());
                }
            }

            return command;
            
        } catch (Exception e) {
            return null;
        } 
    }

    /**
     * Parses a line of download output and extracts relevant information.
     */
    private void parseDownloadLine(String line, DownloadResult result, Long profileId) {
        result.appendOutput(line + "\n");
        lastOutputCache += line + "\n";
        broadcast(line + "\n", profileId);

        String title = null;
        String artist = null;

        // Try format 1: Skipping "Artist - Title"
        Matcher matcher1 = SKIPPED_SONG_PATTERN_FORMAT1.matcher(line);
        if (matcher1.find()) {
            String fullTitleAndArtist = matcher1.group(1).trim();
            int separatorIndex = fullTitleAndArtist.indexOf(" - ");
            if (separatorIndex != -1) {
                String rawArtist = fullTitleAndArtist.substring(0, separatorIndex).trim();
                title = fullTitleAndArtist.substring(separatorIndex + 3).trim();
                artist = extractPrimaryArtist(rawArtist);
                title = cleanTitle(title);
            }
        } else {
            // Try format 2: Skipping 'Title' by 'Artist'
            Matcher matcher2 = SKIPPED_SONG_PATTERN_FORMAT2.matcher(line);
            if (matcher2.find()) {
                String group1 = matcher2.group(1).trim();
                String group2 = matcher2.group(2).trim();

                int firstSeparatorIndex = group1.indexOf(" - ");
                if (firstSeparatorIndex != -1) {
                    artist = group1.substring(0, firstSeparatorIndex).trim();
                    title = cleanTitle(group1.substring(firstSeparatorIndex + 3).trim());
                    if (!group2.isEmpty()) {
                        title += " (" + group2 + ")";
                    }
                } else {
                    artist = group1;
                    title = cleanTitle(group2);
                }
            }
        }

        if (title != null && artist != null) {
            result.addSkippedSong(new String[]{artist, title});
        } else {
            // Check for successful downloads
            Matcher spotdlMatcher = SPOTDL_DOWNLOAD_SUCCESS_PATTERN.matcher(line);
            if (spotdlMatcher.find()) {
                String filename = spotdlMatcher.group(1);
                result.addDownloadedFile(sanitizeFilename(filename));
            } else {
                Matcher ytdlpMatcher = YTDLP_MERGING_PATTERN.matcher(line);
                if (ytdlpMatcher.find()) {
                    String filename = ytdlpMatcher.group(1);
                    result.addDownloadedFile(sanitizeFilename(filename));
                }
            }
        }
    }

    /**
     * Retries download with reduced thread count after rate limit.
     */
    private DownloadResult retryWithReducedThreads(String url, String format, Path downloadPath,
            Long profileId, DownloadResult result) throws Exception {
        broadcast("Rate limit detected but we have processed some songs. Waiting 60 seconds to retry...\n", profileId);

        try {
            Thread.sleep(60000); // Wait 60 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broadcast("Retry wait interrupted.\n", profileId);
            return result;
        }

        broadcast("Retrying download with reduced thread count...\n", profileId);

        List<String> retryCommand = buildDownloadCommand(url, format, 1, 1, downloadPath);
        broadcast("Executing retry command: " + String.join(" ", retryCommand) + "\n", profileId);

        ProcessBuilder retryProcessBuilder = new ProcessBuilder(retryCommand);
        retryProcessBuilder.environment().put("PYTHONUNBUFFERED", "1");
        retryProcessBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        retryProcessBuilder.redirectErrorStream(true);

        Process retryProcess = retryProcessBuilder.start();

        try (BufferedReader retryReader = new BufferedReader(new InputStreamReader(retryProcess.getInputStream()))) {
            String retryLine;
            while ((retryLine = retryReader.readLine()) != null) {
                parseDownloadLine(retryLine, result, profileId);
            }
        }

        int retryExitCode = retryProcess.waitFor();
        broadcast("Retry process finished with exit code: " + retryExitCode + "\n", profileId);

        if (retryExitCode != 0) {
            broadcast("WARNING: Retry also failed, but proceeding with songs processed so far...\n", profileId);
        }

        return result;
    }

    private boolean isRateLimitHit(String output) {
        return RATE_LIMIT_PATTERN.matcher(output.toLowerCase()).find();
    }

    private boolean hasProcessedSongs(DownloadResult result) {
        return !result.getDownloadedFiles().isEmpty() || !result.getSkippedSongs().isEmpty();
    }

    private String extractPrimaryArtist(String rawArtist) {
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            return rawArtist;
        }

        String[] artists = rawArtist.split(",\\s*|\\s+feat\\.\\s+|\\s+&\\s+|\\s+/\\s+");
        String primaryArtist = artists[0].trim();
        primaryArtist = primaryArtist.split("\\s+feat\\.\\s+")[0].trim();

        return primaryArtist;
    }

    private String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return title;
        }

        String cleaned = title.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remaster\\s*$", "");
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remastered\\s*\\d{4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s*-\\s*Mono Version\\s*Remastered\\s*\\d{4}\\s*$", "");
        cleaned = cleaned.trim();

        return cleaned.isEmpty() ? title : cleaned;
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[<>:\"/\\\\|?*]", "");
        sanitized = sanitized.replaceAll("^\\.+|\\s+\\.|\\s+$", "");
        return sanitized;
    }

    private void broadcast(String message, Long profileId) {
        importStatusSocket.broadcast(message, profileId);
    }

    private String lastOutputCache = "";

    /**
     * Gets the last output cache for backward compatibility.
     */
    public String getLastOutputCache() {
        return lastOutputCache;
    }

    /**
     * Result object containing download information.
     */
    public static class DownloadResult {

        private final List<String> downloadedFiles = Collections.synchronizedList(new ArrayList<>());
        private final List<String[]> skippedSongs = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder outputCache = new StringBuilder();

        public void addDownloadedFile(String filename) {
            downloadedFiles.add(filename);
        }

        public void addSkippedSong(String[] artistTitle) {
            skippedSongs.add(artistTitle);
        }

        public void appendOutput(String output) {
            outputCache.append(output);
        }

        public List<String> getDownloadedFiles() {
            return new ArrayList<>(downloadedFiles);
        }

        public List<String[]> getSkippedSongs() {
            return new ArrayList<>(skippedSongs);
        }

        public StringBuilder getOutputCache() {
            return outputCache;
        }
    }
}
