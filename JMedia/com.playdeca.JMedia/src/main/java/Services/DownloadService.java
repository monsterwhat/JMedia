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
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import Models.Settings;

@ApplicationScoped
public class DownloadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);

    @Inject
    ImportStatusSocket importStatusSocket;

    @Inject
    PlatformOperationsFactory platformOperationsFactory;

    @Inject
    InstallationService installationService;

    @Inject
    SettingsService settingsService;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // YouTube retry constants
    private static final long YOUTUBE_RETRY_WAIT_TIME_MS = 90 * 1000; // 1 minute 30 seconds
    private static final int MAX_YOUTUBE_RETRIES = 3;
    private static final int YOUTUBE_RETRY_DELAY_MS = 2000; // 2 second delay for immediate retries

    // Patterns for parsing download output
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT1 = Pattern.compile("Skipping \"([^\"]+)\" as it's already downloaded");
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT2 = Pattern.compile("Skipping '([^']+)' by '([^']+)' as it's already downloaded");
    private static final Pattern SPOTDL_DOWNLOAD_SUCCESS_PATTERN = Pattern.compile("Downloaded \"(.+?)\":");
    private static final Pattern YTDLP_MERGING_PATTERN = Pattern.compile("\\[ffmpeg\\] Merging formats into \"([^\"]+)\"");
    private static final Pattern YTDLP_AUDIO_EXTRACTION_PATTERN = Pattern.compile("\\[ExtractAudio\\] Destination: \"([^\"]+)\"");
    private static final Pattern RATE_LIMIT_PATTERN = Pattern.compile(
            "too many 429 error responses|rate.*limit|429|Your application has reached a rate/request limit\\. Retry will occur after: (\\d+) s"
    );

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

            // Validate yt-dlp installation for YouTube URLs or song searches
            if (isSongSearchQuery(url) || url.contains("youtube.com") || url.contains("youtu.be")) {
                PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
                if (!platformOps.isYtdlpInstalled()) {
                    throw new Exception("yt-dlp is not installed. Please install yt-dlp first: " + platformOps.getYtdlpInstallMessage());
                }
            }

            Path normalizedDownloadPath = Paths.get(downloadPath);
            File downloadDir = normalizedDownloadPath.toFile();
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            List<String> command = buildDownloadCommand(url, format, downloadThreads, searchThreads, normalizedDownloadPath, profileId);
            result.setDownloadSource(detectDownloadSource(url));
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

            // Handle various failure scenarios
            if (exitCode != 0) {
                boolean isRateLimit = isRateLimitHit(result.getOutputCache().toString());
                
                // Handle YouTube-specific retry logic
                if (isYouTubeQuery(url)) {
                    if (hasProcessedSongs(result)) {
                        // YouTube with some processed songs and rate limit - use existing retry logic
                        return retryWithReducedThreads(url, format, normalizedDownloadPath, profileId, result);
                    } else {
                        // YouTube with no processed songs - use new YouTube retry logic
                        return retryYouTube(url, format, normalizedDownloadPath, profileId, result, isRateLimit, 0);
                    }
                }
                
                // Non-YouTube error handling
                if (isRateLimit && hasProcessedSongs(result)) {
                    return retryWithReducedThreads(url, format, normalizedDownloadPath, profileId, result);
                } else if (!hasProcessedSongs(result)) {
                    // Check if this was a song search (non-YouTube) that can fallback to SpotDL
                    if (isSongSearchQuery(url) && !url.contains("spotify.com")) {
                        DownloadResult fallbackResult = tryFallbackToSpotDL(url, format, downloadThreads, searchThreads, normalizedDownloadPath, profileId);
                        // Combine results if fallback succeeded
                        if (hasProcessedSongs(fallbackResult)) {
                            result.getDownloadedFiles().addAll(fallbackResult.getDownloadedFiles());
                            result.getSkippedSongs().addAll(fallbackResult.getSkippedSongs());
                            broadcast("✅ Combined results: " + result.getDownloadedFiles().size() + " files downloaded, " + result.getSkippedSongs().size() + " skipped.\n", profileId);
                            return result;
                        }
                    }
                    throw new Exception("Download process exited with error code " + exitCode + " and no songs were processed.\nExternal tool output:\n" + result.getOutputCache().toString());
                }
            } else if (exitCode == 0 && hasProcessedSongs(result)) {
                if (isSongSearchQuery(url) || url.contains("youtube.com") || url.contains("youtu.be")) {
                    broadcast("✅ Successfully downloaded from YouTube: " + result.getDownloadedFiles().size() + " files, " + result.getSkippedSongs().size() + " skipped.\n", profileId);
                } else {
                    broadcast("✅ Successfully downloaded from Spotify (SpotDL): " + result.getDownloadedFiles().size() + " files, " + result.getSkippedSongs().size() + " skipped.\n", profileId);
                }
            } else if (exitCode != 0) {
                broadcast("⚠️ WARNING: Download process exited with error code " + exitCode + " but some songs were processed. Continuing...\n", profileId);
            }

            return result;

        } finally {
            isDownloading.set(false);
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
                } else {
                    Matcher ytdlpAudioMatcher = YTDLP_AUDIO_EXTRACTION_PATTERN.matcher(line);
                    if (ytdlpAudioMatcher.find()) {
                        String filename = ytdlpAudioMatcher.group(1);
                        result.addDownloadedFile(sanitizeFilename(filename));
                    }
                }
            }
        }
    }

    /**
     * Builds the appropriate download command based on URL type.
     */
    private List<String> buildDownloadCommand(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId) {
        try {
            List<String> command = new ArrayList<>();
            boolean isYouTubeUrl = url.contains("youtube.com") || url.contains("youtu.be");
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();

            // Use YouTube-first for song searches, SpotDL for Spotify URLs
            if (isSongSearchQuery(url) || isYouTubeUrl) {
                // Use python -m yt_dlp since yt-dlp is typically installed as a Python module
                String pythonExecutable = installationService.findPythonExecutable();
                if (pythonExecutable != null) {
                    Collections.addAll(command, pythonExecutable.split(" "));
                    command.add("-m");
                    command.add("yt_dlp");
                } else {
                    throw new Exception("Python executable not found. Please install Python to use yt-dlp.");
                }
                command.add("-x");
                command.add("--audio-format");
                command.add(format != null && !format.isEmpty() ? format : "mp3");
                command.add("--output");
                command.add(downloadPath.resolve("%(title)s.%(ext)s").toString());

                // Add cookies file for yt-dlp on Linux if configured
                if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                    try {
                        Settings settings = settingsService.getSettingsOrNull();
                        if (settings != null && settings.getCookiesFilePath() != null && 
                            Files.exists(Paths.get(settings.getCookiesFilePath()))) {
                            command.add("--cookies");
                            command.add(settings.getCookiesFilePath());
                            broadcast("🍪 Using cookies file: " + settings.getCookiesFilePath() + "\n", profileId);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Could not check cookies file: " + e.getMessage());
                    }
                }

                if (isSongSearchQuery(url)) {
                    command.add("ytsearch:" + url);
                    broadcast("🔍 Searching YouTube for: " + url + "\n", profileId);
                } else {
                    broadcast("📥 Downloading from YouTube: " + url + "\n", profileId);
                    command.add(url);
                }
            } else {
                // Use SpotDL with platform-specific execution method
                if (platformOps.shouldUseSpotdlDirectCommand()) {
                    // Linux with pipx installation - use direct command
                    String spotdlCommand = platformOps.getSpotdlCommand();
                    command.add(spotdlCommand);
                } else {
                    // Windows/macOS or Linux with pip --user - use python -m spotdl
                    String pythonExecutable = installationService.findPythonExecutable();
                    if (pythonExecutable != null) {
                        Collections.addAll(command, pythonExecutable.split(" "));
                        command.add("-m");
                        command.add("spotdl");
                    } else {
                        command.add(platformOps.getSpotdlCommand());
                    }
                }

                command.add(url);
                command.add("--output");
                command.add(downloadPath.toString());

                // Use different arguments based on platform
                boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");

                if (format != null && !format.isEmpty()) {
                    if (isLinux) {
                        command.add("--format");
                    } else {
                        command.add("--output-format");
                    }
                    command.add(format);
                }

                Integer combinedThreads = null;
                if (downloadThreads != null && downloadThreads > 0) {
                    combinedThreads = downloadThreads;
                } else if (searchThreads != null && searchThreads > 0) {
                    combinedThreads = searchThreads;
                }

                if (combinedThreads != null) {
                    if (isLinux) {
                        command.add("--threads");
                    } else {
                        command.add("--download-threads");
                    }
                    command.add(combinedThreads.toString());
                }
            }

            return command;
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Detects the download source for a given URL or query.
     */
    private DownloadSource detectDownloadSource(String url) {
        if (isSongSearchQuery(url)) {
            return DownloadSource.YOUTUBE;
        } else if (url.contains("spotify.com") || url.contains("open.spotify.com")) {
            return DownloadSource.SPOTDL;
        } else if (url.contains("youtube.com") || url.contains("youtu.be")) {
            return DownloadSource.YOUTUBE;
        } else {
            return DownloadSource.YOUTUBE_FALLBACK;
        }
    }

    /**
     * Attempts to fallback to SpotDL when YouTube download fails.
     */
    private DownloadResult tryFallbackToSpotDL(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId) throws Exception {
        broadcast("❌ YouTube search failed, trying Spotify via SpotDL...\n", profileId);

        DownloadResult fallbackResult = new DownloadResult();

        // Build SpotDL command for the same query
        List<String> spotdlCommand = buildSpotDLCommand(url, format, downloadThreads, searchThreads, downloadPath);
        broadcast("Executing fallback command: " + String.join(" ", spotdlCommand) + "\n", profileId);

        ProcessBuilder processBuilder = new ProcessBuilder(spotdlCommand);
        processBuilder.environment().put("PYTHONUNBUFFERED", "1");
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseDownloadLine(line, fallbackResult, profileId);
            }
        }

        int exitCode = process.waitFor();
        broadcast("Fallback process finished with exit code: " + exitCode + "\n", profileId);

        if (exitCode == 0 && hasProcessedSongs(fallbackResult)) {
            broadcast("✅ Successfully downloaded from Spotify (SpotDL fallback): " + fallbackResult.getDownloadedFiles().size() + " files, " + fallbackResult.getSkippedSongs().size() + " skipped.\n", profileId);
        } else if (exitCode != 0 && !hasProcessedSongs(fallbackResult)) {
            broadcast("❌ Both YouTube and SpotDL failed. Unable to download.\n", profileId);
        }

        return fallbackResult;
    }

    /**
     * Builds SpotDL-specific command for fallback.
     */
    private List<String> buildSpotDLCommand(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath) {
        try {
            List<String> command = new ArrayList<>();
            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();

            if (platformOps.shouldUseSpotdlDirectCommand()) {
                String spotdlCommand = platformOps.getSpotdlCommand();
                command.add(spotdlCommand);
            } else {
                String pythonExecutable = installationService.findPythonExecutable();
                if (pythonExecutable != null) {
                    Collections.addAll(command, pythonExecutable.split(" "));
                    command.add("-m");
                    command.add("spotdl");
                } else {
                    command.add(platformOps.getSpotdlCommand());
                }
            }

            command.add(url);
            command.add("--output");
            command.add(downloadPath.toString());

            boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");

            if (format != null && !format.isEmpty()) {
                if (isLinux) {
                    command.add("--format");
                } else {
                    command.add("--output-format");
                }
                command.add(format);
            }

            Integer combinedThreads = null;
            if (downloadThreads != null && downloadThreads > 0) {
                combinedThreads = downloadThreads;
            } else if (searchThreads != null && searchThreads > 0) {
                combinedThreads = searchThreads;
            }

            if (combinedThreads != null) {
                if (isLinux) {
                    command.add("--threads");
                } else {
                    command.add("--download-threads");
                }
                command.add(combinedThreads.toString());
            }

            return command;
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        } 
    }

    /**
     * Retries download with reduced thread count after rate limit.
     */
    private DownloadResult retryWithReducedThreads(String url, String format, Path downloadPath,
            Long profileId, DownloadResult result) throws Exception {
        long waitTimeMs = extractWaitTime(result.getOutputCache().toString());
        long waitTimeSeconds = waitTimeMs / 1000;
        broadcast("⏱️ Rate limit detected! Waiting " + waitTimeSeconds + " seconds before retry...\n", profileId);

        try {
            Thread.sleep(waitTimeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broadcast("Retry wait interrupted.\n", profileId);
            return result;
        }

        broadcast("🔄 Retrying download with reduced thread count...\n", profileId);

        List<String> retryCommand = buildDownloadCommand(url, format, 1, 1, downloadPath, profileId);
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

    /**
     * Retries YouTube download with up to 3 attempts, handling rate limits and other errors differently.
     *
     * @param url The URL to download
     * @param format The audio format to use
     * @param downloadPath The download directory
     * @param profileId The profile ID for status updates
     * @param result The previous download result
     * @param isRateLimit Whether the failure was due to rate limiting
     * @param retryCount Current retry attempt number
     * @return DownloadResult containing final results
     * @throws Exception If all retries fail
     */
    private DownloadResult retryYouTube(String url, String format, Path downloadPath,
            Long profileId, DownloadResult result, boolean isRateLimit, int retryCount) throws Exception {
        
        if (retryCount >= MAX_YOUTUBE_RETRIES) {
            broadcast("❌ All " + MAX_YOUTUBE_RETRIES + " YouTube retries failed, trying Spotify via SpotDL...\n", profileId);
            return tryFallbackToSpotDL(url, format, 1, 1, downloadPath, profileId);
        }

        executeRetryWait(isRateLimit, retryCount + 1, profileId);

        broadcast("🔄 Retrying YouTube download... (Attempt " + (retryCount + 1) + "/" + MAX_YOUTUBE_RETRIES + ")\n", profileId);

        List<String> retryCommand = buildDownloadCommand(url, format, 1, 1, downloadPath, profileId);
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
        broadcast("YouTube retry attempt " + (retryCount + 1) + " finished with exit code: " + retryExitCode + "\n", profileId);

        if (retryExitCode == 0 && hasProcessedSongs(result)) {
            broadcast("✅ YouTube retry successful on attempt " + (retryCount + 1) + "!\n", profileId);
            return result;
        } else if (retryExitCode != 0) {
            boolean isRateLimitRetry = isRateLimitHit(result.getOutputCache().toString());
            broadcast("❌ YouTube retry " + (retryCount + 1) + "/" + MAX_YOUTUBE_RETRIES + " failed.\n", profileId);
            return retryYouTube(url, format, downloadPath, profileId, result, isRateLimitRetry, retryCount + 1);
        }

        return result;
    }

    /**
     * Executes appropriate wait time based on error type and retry attempt.
     *
     * @param isRateLimit Whether the failure was due to rate limiting
     * @param retryAttempt Current retry attempt number
     * @param profileId The profile ID for status updates
     * @throws Exception If sleep is interrupted
     */
    private void executeRetryWait(boolean isRateLimit, int retryAttempt, Long profileId) throws Exception {
        if (isRateLimit) {
            broadcast("⏱️ YouTube rate limit detected. Waiting 90 seconds before retry... (Attempt " + retryAttempt + "/" + MAX_YOUTUBE_RETRIES + ")\n", profileId);
            try {
                Thread.sleep(YOUTUBE_RETRY_WAIT_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broadcast("YouTube retry wait interrupted by system.\n", profileId);
                throw e;
            }
        } else {
            broadcast("🔄 Retrying YouTube immediately... (Attempt " + retryAttempt + "/" + MAX_YOUTUBE_RETRIES + ")\n", profileId);
            try {
                Thread.sleep(YOUTUBE_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broadcast("YouTube retry delay interrupted by system.\n", profileId);
                throw e;
            }
        }
    }

    /**
     * Checks if a failure is related to YouTube download.
     *
     * @param url The URL that was being processed
     * @return True if this is a YouTube-related query
     */
    private boolean isYouTubeQuery(String url) {
        return isSongSearchQuery(url) || url.contains("youtube.com") || url.contains("youtu.be");
    }

    private boolean isRateLimitHit(String output) {
        return RATE_LIMIT_PATTERN.matcher(output.toLowerCase()).find();
    }

    private long extractWaitTime(String output) {
        Matcher matcher = Pattern.compile("Retry will occur after: (\\d+) s").matcher(output);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)) * 1000; // Convert to milliseconds
        }
        return 60000; // Default to 60 seconds
    }

    private boolean isSongSearchQuery(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        String trimmed = input.trim().toLowerCase();
        // Check if it's not a URL
        return !trimmed.contains("spotify.com")
                && !trimmed.contains("youtube.com")
                && !trimmed.contains("youtu.be")
                && // Basic pattern detection for song/artist searches
                (trimmed.length() > 2); // Minimum length for a meaningful search
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

    public enum DownloadSource {
        YOUTUBE, SPOTDL, YOUTUBE_FALLBACK
    }

    /**
     * Result object containing download information.
     */
    public static class DownloadResult {

        private final List<String> downloadedFiles = Collections.synchronizedList(new ArrayList<>());
        private final List<String[]> skippedSongs = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder outputCache = new StringBuilder();
        private DownloadSource downloadSource;

        public DownloadSource getDownloadSource() {
            return downloadSource;
        }

        public void setDownloadSource(DownloadSource downloadSource) {
            this.downloadSource = downloadSource;
        }

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
    
    @PreDestroy
    public void shutdown() {
        if (downloadExecutor != null && !downloadExecutor.isShutdown()) {
            LOGGER.info("Shutting down DownloadService executor");
            downloadExecutor.shutdown();
            try {
                if (!downloadExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    LOGGER.warn("DownloadService executor did not terminate gracefully, forcing shutdown");
                    downloadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted while waiting for DownloadService executor to terminate");
                downloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
