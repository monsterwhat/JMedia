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

    // Rate limit information class
    private static class RateLimitInfo {
        private final RateLimitSource source;
        private final int waitTime;
        private final boolean isLongWait;
        private final String pattern;
        
        public RateLimitInfo(RateLimitSource source, int waitTime, boolean isLongWait) {
            this.source = source;
            this.waitTime = waitTime;
            this.isLongWait = isLongWait;
            this.pattern = source == RateLimitSource.SPOTDL ? "spotdl-rate-limit" : "youtube-rate-limit";
        }
        
        public RateLimitSource getSource() { return source; }
        public int getWaitTime() { return waitTime; }
        public boolean isLongWait() { return isLongWait; }
        public String getPattern() { return pattern; }
    }
    
    public enum RateLimitSource {
        YOUTUBE("YouTube Rate Limit"),
        SPOTDL("SpotDL Rate Limit");
        
        private final String description;
        RateLimitSource(String description) { this.description = description; }
        public String getDescription() { return description; }
    }
    
    // Download strategy class
    private static class DownloadStrategy {
        private final Settings.DownloadSource source;
        private final boolean hasFallback;
        
        public DownloadStrategy(Settings.DownloadSource source, boolean hasFallback) {
            this.source = source;
            this.hasFallback = hasFallback;
        }
        
        public Settings.DownloadSource getSource() { return source; }
        public boolean hasFallback() { return hasFallback; }
    }

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
    
    // Enhanced rate limit patterns
    private static final Pattern YOUTUBE_RATE_LIMIT_PATTERN = Pattern.compile(
            "too many 429 error responses|rate.*limit|429|Your application has reached a rate/request limit\\. Retry will occur after: (\\d+) s"
    );
    private static final Pattern SPOTDL_RATE_LIMIT_PATTERN = Pattern.compile(
            "Your application has reached a rate/request limit\\. Retry will occur after: (\\d+)"
    );
    private static final Pattern SPOTDL_MAX_RETRIES_PATTERN = Pattern.compile(
            "Max Retries reached|too many 429 error responses"
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
            // Get current settings for smart configuration
            Settings settings = settingsService.getSettingsOrNull();
            if (settings == null) {
                settings = new Settings(); // Use defaults if no settings exist
            }

            installationService.validateInstallation();

            // Use new smart routing logic
            return executeDownloadWithRetry(url, format, downloadThreads, searchThreads, 
                                       Paths.get(downloadPath), profileId, settings);
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
     * Analyzes rate limit information from output.
     */
    private RateLimitInfo analyzeRateLimit(String output) {
        Matcher spotdlMatcher = SPOTDL_RATE_LIMIT_PATTERN.matcher(output);
        if (spotdlMatcher.find()) {
            int waitTime = Integer.parseInt(spotdlMatcher.group(1));
            boolean isLongWait = waitTime > 3600; // > 1 hour
            return new RateLimitInfo(RateLimitSource.SPOTDL, waitTime, isLongWait);
        }
        
        Matcher youtubeMatcher = YOUTUBE_RATE_LIMIT_PATTERN.matcher(output);
        if (youtubeMatcher.find()) {
            String waitGroup = youtubeMatcher.group(1);
            if (waitGroup != null) {
                int waitTime = Integer.parseInt(waitGroup);
                boolean isLongWait = waitTime > 3600;
                return new RateLimitInfo(RateLimitSource.YOUTUBE, waitTime, isLongWait);
            }
        }
        
        return null;
    }
    
    /**
     * Determines download strategy based on URL and user settings.
     */
    private DownloadStrategy determineDownloadStrategy(String url, Settings settings) {
        boolean isSpotifyUrl = url.contains("spotify.com") || url.contains("open.spotify.com");
        boolean isYouTubeUrl = url.contains("youtube.com") || url.contains("youtu.be");
        boolean isSongSearch = isSongSearchQuery(url);

        // URL-based forced routing
        if (isSpotifyUrl && settings.getSpotdlEnabled()) {
            return new DownloadStrategy(Settings.DownloadSource.SPOTDL, false);
        }
        if (isYouTubeUrl && settings.getYoutubeEnabled()) {
            return new DownloadStrategy(Settings.DownloadSource.YOUTUBE, false);
        }

        // User preference-based routing for song searches
        if (isSongSearch) {
            if (settings.getYoutubeEnabled() && settings.getSpotdlEnabled()) {
                return new DownloadStrategy(settings.getPrimarySource(), true);
            } else if (settings.getYoutubeEnabled()) {
                return new DownloadStrategy(Settings.DownloadSource.YOUTUBE, false);
            } else if (settings.getSpotdlEnabled()) {
                return new DownloadStrategy(Settings.DownloadSource.SPOTDL, false);
            }
        }

        throw new IllegalStateException("No valid download source available");
    }
    
    /**
     * Handles rate limit decisions based on settings.
     */
    private boolean shouldFallbackOnRateLimit(RateLimitInfo rateLimitInfo, Settings settings, Long profileId) {
        if (!settings.getEnableSmartRateLimitHandling()) {
            return false;
        }
        
        // Always fallback on extremely long waits (>6 hours)
        if (rateLimitInfo.getWaitTime() > 21600) {
            broadcast("⚠️ Extreme rate limit detected (" + rateLimitInfo.getWaitTime() + 
                     " seconds). Automatically switching sources.\n", profileId);
            return true;
        }
        
        // Fallback on long waits if enabled
        if (settings.getFallbackOnLongWait() && 
            rateLimitInfo.getWaitTime() > settings.getMaxAcceptableWaitTimeMs() / 1000) {
            broadcast("⚠️ Rate limit wait time (" + rateLimitInfo.getWaitTime() + 
                     "s) exceeds acceptable threshold. Switching sources.\n", profileId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets the secondary source based on current source and settings.
     */
    private Settings.DownloadSource getSecondarySource(Settings settings, Settings.DownloadSource currentSource) {
        if (currentSource == Settings.DownloadSource.YOUTUBE) {
            return Settings.DownloadSource.SPOTDL;
        } else {
            return Settings.DownloadSource.YOUTUBE;
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
     * Executes download with intelligent retry and source switching.
     */
    private DownloadResult executeDownloadWithRetry(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId, Settings settings) throws Exception {
        
        DownloadStrategy strategy = determineDownloadStrategy(url, settings);
        DownloadResult result = new DownloadResult();
        int retryCount = 0;
        boolean shouldRetry = true;
        Settings.DownloadSource currentSource = strategy.getSource();
        boolean hasSecondary = strategy.hasFallback();

        while (shouldRetry && retryCount <= settings.getMaxRetryAttempts()) {
            // Execute download with current source
            result = executeSingleDownload(url, format, downloadThreads, searchThreads, 
                                         downloadPath, profileId, currentSource);

            // Analyze result for rate limiting
            RateLimitInfo rateLimitInfo = analyzeRateLimit(result.getOutputCache().toString());
            
            if (rateLimitInfo != null) {
                // Handle rate limit intelligently
                if (shouldFallbackOnRateLimit(rateLimitInfo, settings, profileId)) {
                    // If we decide to fallback
                    if (hasSecondary && retryCount < settings.getMaxRetryAttempts()) {
                        currentSource = getSecondarySource(settings, currentSource);
                        hasSecondary = false; // Don't fallback again
                        broadcast("🔄 Switching to " + currentSource.getDisplayName() + " due to rate limit...\n", profileId);
                    } else {
                        // Exit if we can't fallback or have exhausted retries
                        shouldRetry = false;
                        break;
                    }
                } else {
                    // Wait for retry
                    executeRateLimitWait(rateLimitInfo, profileId);
                    retryCount++;
                }
            } else if (hasProcessedSongs(result)) {
                // Success!
                broadcast("✅ Successfully downloaded from " + currentSource.getDisplayName() + "!\n", profileId);
                return result;
            } else {
                // Generic failure, apply retry strategy
                if (shouldRetryBasedOnStrategy(strategy, retryCount, settings)) {
                    if (hasSecondary && shouldSwitchToSecondary(strategy, retryCount, settings)) {
                        currentSource = getSecondarySource(settings, currentSource);
                        hasSecondary = false;
                        broadcast("🔄 Switching to " + currentSource.getDisplayName() + "...\n", profileId);
                    } else {
                        retryCount++;
                        executeGenericRetryWait(retryCount, profileId);
                    }
                } else {
                    shouldRetry = false;
                }
            }
        }

        return result;
    }

    /**
     * Executes a single download with the specified source.
     */
    private DownloadResult executeSingleDownload(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId, 
            Settings.DownloadSource source) throws Exception {
        
        DownloadResult result = new DownloadResult();
        result.setDownloadSource(source == Settings.DownloadSource.YOUTUBE ? 
                             DownloadSource.YOUTUBE : DownloadSource.SPOTDL);

        List<String> command = buildDownloadCommand(url, format, downloadThreads, 
                                                searchThreads, downloadPath, profileId);
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

        return result;
    }

    /**
     * Builds download command for specific source.
     */
    private List<String> buildDownloadCommandWithSource(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId, Settings.DownloadSource source) throws Exception {
        
        if (source == Settings.DownloadSource.YOUTUBE) {
            return buildYouTubeCommand(url, format, downloadThreads, searchThreads, downloadPath, profileId);
        } else {
            return buildSpotDLCommand(url, format, downloadThreads, searchThreads, downloadPath);
        }
    }

    /**
     * Builds YouTube-specific command.
     */
    private List<String> buildYouTubeCommand(String url, String format, Integer downloadThreads,
            Integer searchThreads, Path downloadPath, Long profileId) throws Exception {
        
        List<String> command = new ArrayList<>();
        String pythonExecutable = installationService.findPythonExecutable();
        if (pythonExecutable != null) {
            Collections.addAll(command, pythonExecutable.split(" "));
            command.add("-m");
            command.add("yt_dlp");
        } else {
            throw new IOException("Python executable not found. Please install Python to use yt-dlp.");
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

        return command;
    }

    /**
     * Determines if retry should happen based on strategy.
     */
    private boolean shouldRetryBasedOnStrategy(DownloadStrategy strategy, int retryCount, Settings settings) {
        switch (settings.getSwitchStrategy()) {
            case IMMEDIATELY:
                return false; // Switch immediately, no retry
            case AFTER_FAILURES:
                return retryCount < settings.getSwitchThreshold();
            case ONLY_ON_RATE_LIMIT:
                return false; // Only switch on rate limit
            case SMART_ADAPTIVE:
                return retryCount < 2; // Smart retry logic
            default:
                return retryCount < settings.getMaxRetryAttempts();
        }
    }

    /**
     * Determines if should switch to secondary source.
     */
    private boolean shouldSwitchToSecondary(DownloadStrategy strategy, int retryCount, Settings settings) {
        if (!strategy.hasFallback()) {
            return false;
        }
        
        switch (settings.getSwitchStrategy()) {
            case IMMEDIATELY:
                return retryCount >= 1;
            case AFTER_FAILURES:
                return retryCount >= settings.getSwitchThreshold();
            case ONLY_ON_RATE_LIMIT:
                return false; // Only switch on explicit rate limit
            case SMART_ADAPTIVE:
                return retryCount >= 1; // Smart adaptive switching
            default:
                return false;
        }
    }

    /**
     * Executes rate limit wait.
     */
    private void executeRateLimitWait(RateLimitInfo rateLimitInfo, Long profileId) throws Exception {
        long waitTimeMs = rateLimitInfo.getWaitTime() * 1000L;
        long waitTimeSeconds = rateLimitInfo.getWaitTime();
        
        broadcast("⏱️ " + rateLimitInfo.getSource().getDescription() + 
                 " detected. Waiting " + waitTimeSeconds + " seconds before retry...\n", profileId);
        
        Thread.sleep(waitTimeMs);
    }

    /**
     * Executes generic retry wait.
     */
    private void executeGenericRetryWait(int retryCount, Long profileId) throws Exception {
        long waitTimeMs = 2000 * retryCount; // Exponential backoff
        broadcast("🔄 Retrying in " + (waitTimeMs / 1000) + " seconds...\n", profileId);
        Thread.sleep(waitTimeMs);
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
        return YOUTUBE_RATE_LIMIT_PATTERN.matcher(output.toLowerCase()).find() ||
               SPOTDL_RATE_LIMIT_PATTERN.matcher(output.toLowerCase()).find();
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
