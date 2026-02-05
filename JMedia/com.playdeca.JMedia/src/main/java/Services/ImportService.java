package Services;

import API.WS.ImportStatusSocket;
import Controllers.PlaybackQueueController;
import Controllers.SettingsController;
import Models.DTOs.ImportInstallationStatus;
import Models.PlaybackState;
import Models.Playlist;
import Models.Song;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportService.class);

    @Inject
    ImportStatusSocket importStatusSocket;

    @Inject
    SettingsController settings;

    @Inject
    PlaylistService playlistService;

    @Inject
    SongService songService;

    @Inject
    PlaybackQueueController playbackQueueController;

    @Inject
    PlaybackStateService playbackStateService;

    @Inject
    InstallationService installationService;

    @Inject
    MetadataService metadataService;

    @Inject
    DownloadService downloadService;

    private final AtomicBoolean isImporting = new AtomicBoolean(false);
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Store the detected execution method for SpotDL
    private volatile String spotdlExecutionMethod = "DIRECT_COMMAND";
    private volatile String detectedPythonExecutable = "python";

    public synchronized void startDownload(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload, Long profileId) {
        if (isImporting.get()) {
            LOGGER.warn("An import is already in progress. Ignoring new request.");
            importStatusSocket.broadcast("An import is already in progress. Please wait for it to complete.\n", profileId);
            return;
        }

        importExecutor.submit(() -> {
            if (!isImporting.compareAndSet(false, true)) {
                return;
            }

            broadcast("Starting new import process...\n", profileId);

            try {
                // Step 1: Download media
                DownloadService.DownloadResult downloadResult = downloadService.download(
                        url, format, downloadThreads, searchThreads, downloadPath, profileId);

                // Step 2: Process downloaded files and create songs
                List<Song> finalSongsForPlaylist = processDownloadResult(downloadResult, downloadPath, format, profileId);

                // Step 3: Create playlist if requested
                if (playlistName != null && !playlistName.trim().isEmpty()) {
                    broadcast("Creating playlist '" + playlistName + "' with " + finalSongsForPlaylist.size() + " songs...\n", profileId);
                    addSongsToPlaylist(playlistName, finalSongsForPlaylist, profileId, url);
                } else {
                    broadcast("No playlist name provided, skipping playlist creation.\n", profileId);
                }

                // Step 4: Add to queue if requested
                if (queueAfterDownload) {
                    addSongToQueue(finalSongsForPlaylist, profileId);
                }

                broadcast("Import process fully completed.\n", profileId);

            } catch (Exception e) {
                LOGGER.error("An error occurred during the import process.", e);
                broadcast("ERROR: " + e.getMessage() + "\n", profileId);
            } finally {
                isImporting.set(false);
                broadcast("[IMPORT_FINISHED]", profileId);
            }
        });
    }

    /**
     * Starts background import process for multiple songs/URLs.
     */
    public synchronized void startDownloads(List<String> urls, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload, Long profileId) {
        if (isImporting.get()) {
            LOGGER.warn("An import is already in progress. Ignoring new request.");
            importStatusSocket.broadcast("An import is already in progress. Please wait for it to complete.\n", profileId);
            return;
        }

        importExecutor.submit(() -> {
            if (!isImporting.compareAndSet(false, true)) {
                return;
            }

            broadcast("Starting new import process for " + urls.size() + " items...\n", profileId);

            try {
                List<Song> allSongsForPlaylist = new ArrayList<>();

                // Process each URL/song
                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    broadcast("\n--- Processing item " + (i + 1) + " of " + urls.size() + ": " + url + " ---\n", profileId);

                    // Check if song already exists (only for song lists, not URLs)
                    if (urls.size() > 1 && isSongSearchQuery(url)) {
                        Song existingSong = findExistingSong(url);
                        if (existingSong != null) {
                            broadcast("⏭️ Song already exists in library: '" + existingSong.getTitle() + "' by '" + existingSong.getArtist() + "' - skipping download\n", profileId);
                            allSongsForPlaylist.add(existingSong); // Add existing song to playlist
                            continue;
                        }
                    }

                    try {
                        // Step 1: Download media
                        DownloadService.DownloadResult downloadResult = downloadService.download(
                                url, format, downloadThreads, searchThreads, downloadPath, profileId);

                        // Step 2: Process downloaded files and create songs
                        List<Song> songsForThisUrl = processDownloadResult(downloadResult, downloadPath, format, profileId);

                        // Add to overall list
                        allSongsForPlaylist.addAll(songsForThisUrl);

                        broadcast("Completed item " + (i + 1) + " of " + urls.size() + ". Found " + songsForThisUrl.size() + " songs.\n", profileId);

                    } catch (Exception e) {
                        LOGGER.error("Error processing item: " + url, e);
                        broadcast("ERROR processing '" + url + "': " + e.getMessage() + "\n", profileId);

                        // Check if this is a song search query that might have hit YouTube retry limits
                        if (urls.size() > 1 && isSongSearchQuery(url)) {
                            boolean isLikelyYouTubeExhausted = e.getMessage() != null
                                    && (e.getMessage().contains("All 3 YouTube retries failed")
                                    || e.getMessage().contains("YouTube retry wait interrupted")
                                    || e.getMessage().contains("download process exited with error code") && e.getMessage().contains("YouTube"));

                            if (isLikelyYouTubeExhausted) {
                                broadcast("⚠️ YouTube retries exhausted for this song. Skipping and continuing with next item...\n", profileId);
                                continue; // Skip to next item, YouTube already tried SpotDL fallback
                            } else {
                                // Non-YouTube error or unrecognized error, skip
                                broadcast("⚠️ Skipping this song due to error: " + e.getMessage() + "\n", profileId);
                                continue;
                            }
                        } else {
                            // Single URL or non-song query, continue normally
                            broadcast("Continuing with next item...\n", profileId);
                        }
                    }
                }

                broadcast("\n=== Summary ===\n", profileId);
                broadcast("Processed " + urls.size() + " items. Found " + allSongsForPlaylist.size() + " total songs.\n", profileId);

                // Step 3: Create playlist if requested
                if (playlistName != null && !playlistName.trim().isEmpty()) {
                    broadcast("Creating playlist '" + playlistName + "' with " + allSongsForPlaylist.size() + " songs...\n", profileId);
                    addSongsToPlaylist(playlistName, allSongsForPlaylist, profileId, "Song List: " + String.join(", ", urls));
                } else {
                    broadcast("No playlist name provided, skipping playlist creation.\n", profileId);
                }

                // Step 4: Add to queue if requested
                if (queueAfterDownload && !allSongsForPlaylist.isEmpty()) {
                    addSongToQueue(allSongsForPlaylist, profileId);
                }

                broadcast("Import process fully completed.\n", profileId);

            } catch (Exception e) {
                LOGGER.error("An error occurred during the import process.", e);
                broadcast("ERROR: " + e.getMessage() + "\n", profileId);
            } finally {
                isImporting.set(false);
                broadcast("[IMPORT_FINISHED]", profileId);
            }
        });
    }

    /**
     * Processes the download result to create Song objects.
     */
    private List<Song> processDownloadResult(DownloadService.DownloadResult downloadResult, String downloadPath,
            String format, Long profileId) {
        List<String> downloadedFileNames = downloadResult.getDownloadedFiles();
        List<String[]> skippedSongs = downloadResult.getSkippedSongs();

        Path normalizedDownloadPath = Paths.get(downloadPath);
        Path baseMusicFolder = settings.getMusicFolder().toPath();

        // Separate actually downloaded files from skipped files
        List<String> actuallyDownloadedFiles = new ArrayList<>();
        List<String> actuallySkippedFiles = new ArrayList<>();

        String fileExtension = "." + (format != null && !format.isEmpty() ? format : "mp3");
        for (String filename : downloadedFileNames) {
            File file = normalizedDownloadPath.resolve(filename + fileExtension).toFile();
            if (file.exists() && file.isFile()) {
                actuallyDownloadedFiles.add(filename);
            } else {
                actuallySkippedFiles.add(filename);
            }
        }

        if (!actuallyDownloadedFiles.isEmpty()) {
            broadcast("Download completed successfully. Found " + actuallyDownloadedFiles.size() + " new files downloaded, " + actuallySkippedFiles.size() + " files were already available.\n", profileId);
        } else {
            broadcast("Download completed successfully. All " + downloadedFileNames.size() + " files were already available locally.\n", profileId);
        }

        // Process downloaded files
        List<Song> newlyAddedSongs = new ArrayList<>();
        if (!actuallyDownloadedFiles.isEmpty()) {
            broadcast("Download completed successfully. Triggering targeted scan for downloaded files...\n", profileId);
            try {
                newlyAddedSongs = settings.scanSpecificFiles(actuallyDownloadedFiles, normalizedDownloadPath.toString());
                broadcast("Targeted scan completed. Found " + newlyAddedSongs.size() + " newly added songs.\n", profileId);
            } catch (Exception e) {
                LOGGER.error("An error occurred during targeted file scan.", e);
                broadcast("WARNING: The targeted file scan failed. Falling back to incremental library scan. Error: " + e.getMessage() + "\n", profileId);

                try {
                    newlyAddedSongs = settings.scanLibraryIncremental();
                    broadcast("Incremental library scan completed. Found " + newlyAddedSongs.size() + " newly added songs.\n", profileId);
                } catch (Exception fallbackException) {
                    LOGGER.error("Incremental scan also failed", fallbackException);
                    broadcast("WARNING: Incremental scan failed. Falling back to full import folder scan. Error: " + fallbackException.getMessage() + "\n", profileId);

                    try {
                        settings.scanImportFolder();
                        Set<String> relativeDownloadedPaths = new HashSet<>();
                        for (String filename : downloadedFileNames) {
                            Path fullPath = normalizedDownloadPath.resolve(filename + fileExtension);
                            String relativePath = baseMusicFolder.relativize(fullPath).toString().replace('\\', '/');
                            relativeDownloadedPaths.add(relativePath);
                        }
                        newlyAddedSongs = songService.findByRelativePaths(relativeDownloadedPaths);
                    } catch (Exception finalFallbackException) {
                        LOGGER.error("Final fallback scan also failed", finalFallbackException);
                        broadcast("ERROR: All scan methods failed. Newly downloaded songs may not be available immediately. Error: " + finalFallbackException.getMessage() + "\n", profileId);
                        newlyAddedSongs = new ArrayList<>();
                    }
                }
            }
        }

        // Process skipped songs with fuzzy matching
        List<Song> skippedButExistingSongs = new ArrayList<>();
        if (!skippedSongs.isEmpty()) {
            List<Song> allSongs = songService.findAll();
            broadcast("Processing " + skippedSongs.size() + " skipped songs with fuzzy matching against " + allSongs.size() + " DB songs\n", profileId);

            for (String[] artistTitle : skippedSongs) {
                String parsedArtist = artistTitle[0];
                String parsedTitle = artistTitle[1];

                Song bestMatch = metadataService.findBestMatch(parsedArtist, parsedTitle, allSongs);

                if (bestMatch != null) {
                    skippedButExistingSongs.add(bestMatch);
                    broadcast("Found fuzzy match: '" + parsedTitle + "' by '" + parsedArtist + "' -> DB: '" + bestMatch.getTitle() + "' by '" + bestMatch.getArtist() + "'\n", profileId);
                } else {
                    broadcast("No fuzzy match found for: '" + parsedTitle + "' by '" + parsedArtist + "'\n", profileId);
                }
            }
        }

        // Combine all songs
        List<Song> finalSongsForPlaylist = new ArrayList<>();
        Set<Long> songIdsInFinalList = new HashSet<>();

        // Add newly added songs first
        for (Song song : newlyAddedSongs) {
            if (song.id != null && songIdsInFinalList.add(song.id)) {
                finalSongsForPlaylist.add(song);
            }
        }

        // Add skipped songs that were found in DB
        for (Song song : skippedButExistingSongs) {
            if (song.id != null && songIdsInFinalList.add(song.id)) {
                finalSongsForPlaylist.add(song);
            }
        }

        broadcast("Final song list contains " + finalSongsForPlaylist.size() + " unique songs for playlist creation.\n", profileId);
        return finalSongsForPlaylist;
    }

    private void addSongToQueue(List<Song> songsToQueue, Long profileId) {
        if (songsToQueue == null || songsToQueue.isEmpty()) {
            broadcast("No songs found to queue.\n", profileId);
            return;
        }

            Song songToQueue = songsToQueue.get(0);
            try {
                PlaybackState currentState = playbackStateService.getOrCreateState(profileId);
                List<Long> songIds = Collections.singletonList(songToQueue.id);
                playbackQueueController.addToQueue(currentState, songIds, false, profileId);
                playbackStateService.saveState(profileId, currentState);
                broadcast("{\"type\": \"song-queued\", \"songTitle\": \"" + songToQueue.getTitle() + "\"}\n", profileId);
            } catch (Exception e) {
                LOGGER.error("Failed to queue song: {}", songToQueue.getTitle(), e);
                broadcast("ERROR: Failed to queue song '" + songToQueue.getTitle() + "': " + e.getMessage() + "\n", profileId);
        }
    }

    private void addSongsToPlaylist(String playlistName, List<Song> songsToAdd, Long profileId, String originalUrl) {
        if (songsToAdd == null || songsToAdd.isEmpty()) {
            broadcast("No songs found to add to playlist '" + playlistName + "'.\n", profileId);
            LOGGER.warn("No songs provided for playlist creation: {}", playlistName);
            return;
        }

        LOGGER.info("Creating/adding to playlist '{}' with {} songs", playlistName, songsToAdd.size());

        try {
            String trimmedPlaylistName = playlistName.trim();
            Playlist targetPlaylist = playlistService.findOrCreatePlaylistInNewTx(trimmedPlaylistName);
            if (targetPlaylist == null) {
                broadcast("Could not find or create playlist '" + trimmedPlaylistName + "'. Skipping.\n", profileId);
                LOGGER.error("Failed to find or create playlist: {}", trimmedPlaylistName);
                return;
            }

            // Set original link if this is a new playlist and URL is provided
            if (targetPlaylist.id == null && originalUrl != null && !originalUrl.trim().isEmpty()) {
                targetPlaylist.originalLink = originalUrl.trim();
                targetPlaylist.isGlobal = true;
            }

            playlistService.addSongsToPlaylist(targetPlaylist, songsToAdd);
            broadcast("Added " + songsToAdd.size() + " songs to playlist '" + trimmedPlaylistName + "'.\n", profileId);
            LOGGER.info("Successfully added {} songs to playlist '{}'", songsToAdd.size(), trimmedPlaylistName);
        } catch (Exception e) {
            LOGGER.error("Failed to add songs to playlist: {}", playlistName, e);
            broadcast("ERROR: Failed to add songs to playlist '" + playlistName + "': " + e.getMessage() + "\n", profileId);
        }
    }

    public boolean isImporting() {
        return isImporting.get();
    }

    public String getOutputCache() {
        return downloadService.getLastOutputCache();
    }

    // Delegate installation methods to InstallationService
    public ImportInstallationStatus getInstallationStatus() {
        return installationService.getInstallationStatus();
    }

    public void installRequirements(Long profileId) throws Exception {
        installationService.installAllRequirements(profileId);
    }

    public void installPackageManger(Long profileId) throws Exception {
        installationService.installPackageManger(profileId);
    }

    public void installPython(Long profileId) throws Exception {
        installationService.installPython(profileId);
    }

    public void installFFmpeg(Long profileId) throws Exception {
        installationService.installFFmpeg(profileId);
    }

    public void installSpotdl(Long profileId) throws Exception {
        String pythonExecutable = installationService.installSpotdl(profileId);
        if (pythonExecutable != null) {
            detectedPythonExecutable = pythonExecutable;
            spotdlExecutionMethod = "PYTHON_MODULE";
        }
    }

    public void installWhisper(Long profileId) throws Exception {
        installationService.installWhisper(profileId);
    }

    public void uninstallPython(Long profileId) throws Exception {
        installationService.uninstallPython(profileId);
    }

    public void uninstallFFmpeg(Long profileId) throws Exception {
        installationService.uninstallFFmpeg(profileId);
    }

    public void uninstallSpotdl(Long profileId) throws Exception {
        installationService.uninstallSpotdl(profileId);
    }

    public void uninstallWhisper(Long profileId) throws Exception {
        installationService.uninstallWhisper(profileId);
    }

    // Delegate metadata methods to MetadataService
    public Song extractMetadata(File audioFile, String relativePath) {
        return metadataService.extractMetadata(audioFile, relativePath);
    }

    private void broadcast(String message, Long profileId) {
        importStatusSocket.broadcast(message, profileId);
    }

    /**
     * Parses a song query into artist and title components. Handles formats
     * like "Artist - Title", "Artist1,Artist2 - Title", etc.
     */
    private String[] parseSongQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        String trimmed = query.trim();

        // Look for " - " separator
        int separatorIndex = trimmed.indexOf(" - ");
        if (separatorIndex == -1) {
            // No separator, treat entire string as title
            return new String[]{"Unknown Artist", trimmed};
        }

        String artistPart = trimmed.substring(0, separatorIndex).trim();
        String titlePart = trimmed.substring(separatorIndex + 3).trim(); // Skip " - "

        return new String[]{artistPart, titlePart};
    }

    /**
     * Checks if input is a song search query (not a URL).
     * Simple implementation based on DownloadService logic.
     */
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
 




    /**
     * Helper method to safely get song count from DownloadResult.
     */
    private int getSongCount(DownloadService.DownloadResult result) {
        if (result == null) {
            return 0;
        }
        return (result.getDownloadedFiles() != null ? result.getDownloadedFiles().size() : 0)
                + (result.getSkippedSongs() != null ? result.getSkippedSongs().size() : 0);
    }

    /**
     * Checks if a song already exists in the library using exact matching
     * first, then falls back to fuzzy matching if needed.
     */
    private Song findExistingSong(String query) {
        String[] artistTitle = parseSongQuery(query);
        if (artistTitle == null) {
            return null;
        }

        String searchArtist = artistTitle[0];
        String searchTitle = artistTitle[1];

        // First try exact match
        Song exactMatch = songService.findByTitleAndArtist(searchTitle, searchArtist);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Fallback to fuzzy matching
        List<Song> allSongs = songService.findAll();
        return metadataService.findBestMatch(searchArtist, searchTitle, allSongs);
    }
    
    @PreDestroy
    public void shutdown() {
        if (importExecutor != null && !importExecutor.isShutdown()) {
            LOGGER.info("Shutting down ImportService executor");
            importExecutor.shutdown();
            try {
                if (!importExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("ImportService executor did not terminate gracefully, forcing shutdown");
                    importExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted while waiting for ImportService executor to terminate");
                importExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
