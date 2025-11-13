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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private final AtomicBoolean isImporting = new AtomicBoolean(false);
    private final StringBuilder outputCache = new StringBuilder();
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Pattern to extract song title and artist from "Skipping" messages (Format 1: "Title - Artist")
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT1 = Pattern.compile("Skipping \"([^\"]+)\" as it's already downloaded");
    // Pattern to extract song title and artist from "Skipping" messages (Format 2: 'Title' by 'Artist')
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT2 = Pattern.compile("Skipping '([^']+)' by '([^']+)' as it's already downloaded");

    // Pattern to extract filename from spotdl successful download message
    private static final Pattern SPOTDL_DOWNLOAD_SUCCESS_PATTERN = Pattern.compile("\\[SUCCESS\\] Downloaded \"([^\"]+)\"");

    // Pattern to extract filename from yt-dlp merging message (final file name)
    private static final Pattern YTDLP_MERGING_PATTERN = Pattern.compile("\\[ffmpeg\\] Merging formats into \"([^\"]+)\"");

    // List to store Song objects that were skipped by spotdl but found in our DB
    private List<Song> skippedButExistingSongs;
    // List to store the filenames of songs successfully downloaded during the current import
    private List<String> downloadedFileNames;
    // List to store the filenames of downloaded songs that were not found in the database
    private List<String> unprocessedSongs;


    public synchronized void startDownload(String url, String format, Integer downloadThreads, Integer searchThreads, String downloadPath, String playlistName, boolean queueAfterDownload) {
        if (isImporting.get()) {
            LOGGER.warn("An import is already in progress. Ignoring new request.");
            importStatusSocket.broadcast("An import is already in progress. Please wait for it to complete.\n");
            return;
        }

        importExecutor.submit(() -> {
            if (!isImporting.compareAndSet(false, true)) {
                return;
            }
            
            outputCache.setLength(0);
            skippedButExistingSongs = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            downloadedFileNames = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            unprocessedSongs = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            broadcast("Starting new import process...\n");

            try {
                checkImportInstallation();

                if (downloadPath == null || downloadPath.isEmpty()) {
                    throw new Exception("Download path cannot be empty.");
                }

                Path normalizedDownloadPath = Paths.get(downloadPath);
                File downloadDir = normalizedDownloadPath.toFile();
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }

                List<String> command = buildCommand(url, format, downloadThreads, searchThreads, normalizedDownloadPath);
                broadcast("Executing command: " + String.join(" ", command) + "\n");

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.environment().put("PYTHONUNBUFFERED", "1");
                processBuilder.redirectErrorStream(true);
                
                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        broadcast(line + "\n"); // This will now parse skipped songs
                    }
                }

                int exitCode = process.waitFor();
                broadcast("Process finished with exit code: " + exitCode + "\n");

                if (exitCode != 0) {
                    throw new Exception("Import process exited with error code " + exitCode);
                }

                broadcast("Download completed successfully. Identifying newly added songs...\n");
                
                // Construct relative paths for downloaded files
                Path baseMusicFolder = settings.getMusicFolder().toPath();
                Set<String> relativeDownloadedPaths = new HashSet<>();
                for (String filename : downloadedFileNames) {
                    Path fullPath = normalizedDownloadPath.resolve(filename);
                    String relativePath = baseMusicFolder.relativize(fullPath).toString();
                    relativeDownloadedPaths.add(relativePath);
                }

                // Find these songs in the database
                List<Song> newlyAddedSongs = songService.findByRelativePaths(relativeDownloadedPaths);

                // Determine which downloaded files were not found in the database
                Set<String> foundSongPaths = newlyAddedSongs.stream()
                                                            .map(Song::getPath)
                                                            .collect(Collectors.toSet());
                
                for (String downloadedPath : relativeDownloadedPaths) {
                    if (!foundSongPaths.contains(downloadedPath)) {
                        // Extract just the filename for display
                        Path path = Paths.get(downloadedPath);
                        unprocessedSongs.add("Downloaded but not found in DB: " + path.getFileName().toString());
                    }
                }
                
                // Combine newly added songs with existing ones identified as skipped
                List<Song> finalSongsForPlaylist = new ArrayList<>();
                Set<Long> songIdsInFinalList = new HashSet<>();

                // Add newly added songs first
                for (Song song : newlyAddedSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                // Add skipped but existing songs, if not already in the list
                for (Song song : skippedButExistingSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                // Add skipped but not existing songs to unprocessedSongs
                Set<Long> finalSongIds = finalSongsForPlaylist.stream().map(s -> s.id).collect(Collectors.toSet());
                for (Song skippedSong : skippedButExistingSongs) {
                    if (!finalSongIds.contains(skippedSong.id)) {
                        unprocessedSongs.add("Skipped by external tool and not found in DB: " + skippedSong.getTitle() + " by " + skippedSong.getArtist());
                    }
                }

                if (playlistName != null && !playlistName.trim().isEmpty()) {
                    addSongsToPlaylist(playlistName, finalSongsForPlaylist);
                }

                if (queueAfterDownload) {
                    addSongToQueue(finalSongsForPlaylist);
                }

                if (!unprocessedSongs.isEmpty()) {
                    broadcast("WARNING: The following songs were not fully processed during import:\n");
                    for (String unprocessed : unprocessedSongs) {
                        broadcast("- " + unprocessed + "\n");
                    }
                }

                broadcast("Import process fully completed.\n");

            } catch (Exception e) {
                LOGGER.error("An error occurred during the import process.", e);
                broadcast("ERROR: " + e.getMessage() + "\n");
            } finally {
                            isImporting.set(false);
                                        skippedButExistingSongs = null; // Clear for next import
                                                    downloadedFileNames = null; // Clear for next import
                                                    unprocessedSongs = null; // Clear for next import
                                                    broadcast("[IMPORT_FINISHED]");            }
        });
    }

    private void addSongToQueue(List<Song> songsToQueue) {
        if (songsToQueue == null || songsToQueue.isEmpty()) {
            broadcast("No songs found to queue.\n");
            return;
        }
        // For "Find and Play", we assume only one song is downloaded/identified.
        // We take the first one from the list.
        Song songToQueue = songsToQueue.get(0);
        try {
            // Get the singleton playback state
            PlaybackState currentState = playbackStateService.getOrCreateState();
            
            // Add the new song to the queue
            List<Long> songIds = Collections.singletonList(songToQueue.id);
            playbackQueueController.addToQueue(currentState, songIds, false); // false = add to end

            // Save the modified state
            playbackStateService.saveState(currentState);

            broadcast("{\"type\": \"song-queued\", \"songTitle\": \"" + songToQueue.getTitle() + "\"}\n");
        } catch (Exception e) {
            LOGGER.error("Failed to queue song: {}", songToQueue.getTitle(), e);
            broadcast("ERROR: Failed to queue song '" + songToQueue.getTitle() + "': " + e.getMessage() + "\n");
        }
    }

    private List<String> buildCommand(String url, String format, Integer downloadThreads, Integer searchThreads, Path downloadPath) {
        List<String> command = new ArrayList<>();
        boolean isYouTubeUrl = url.contains("youtube.com") || url.contains("youtu.be");

        if (isYouTubeUrl) {
            command.add("yt-dlp");
            command.add("-x");
            command.add("--audio-format");
            command.add(format != null && !format.isEmpty() ? format : "mp3");
            command.add("--output");
            command.add(downloadPath.resolve("%(title)s.%(ext)s").toString());
            command.add(url);
        } else {
            command.add("spotdl");
            command.add(url);
            command.add("--output");
            command.add(downloadPath.toString());
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
        }
        return command;
    }

    private void addSongsToPlaylist(String playlistName, List<Song> songsToAdd) {
        if (songsToAdd == null || songsToAdd.isEmpty()) {
            broadcast("No songs found to add to playlist '" + playlistName + "'.\n");
            return;
        }
        try {
            String trimmedPlaylistName = playlistName.trim();
            Playlist targetPlaylist = playlistService.findOrCreatePlaylistInNewTx(trimmedPlaylistName);
            if (targetPlaylist == null) {
                broadcast("Could not find or create playlist '" + trimmedPlaylistName + "'. Skipping.\n");
                return;
            }
            playlistService.addSongsToPlaylist(targetPlaylist, songsToAdd);
            broadcast("Added " + songsToAdd.size() + " songs to playlist '" + trimmedPlaylistName + "'.\n");
        } catch (Exception e) {
            LOGGER.error("Failed to add songs to playlist: {}", playlistName, e);
            broadcast("ERROR: Failed to add songs to playlist '" + playlistName + "': " + e.getMessage() + "\n");
        }
    }

    private void broadcast(String message) {
        outputCache.append(message);
        importStatusSocket.broadcast(message);

        LOGGER.debug("Processing message: {}", message.trim());

        String title = null;
        String artist = null;

        // Try format 1: Skipping "Title - Artist"
        Matcher matcher1 = SKIPPED_SONG_PATTERN_FORMAT1.matcher(message);
        if (matcher1.find()) {
            String fullTitleAndArtist = matcher1.group(1).trim();
            int separatorIndex = fullTitleAndArtist.lastIndexOf(" - ");
            if (separatorIndex != -1) {
                title = fullTitleAndArtist.substring(0, separatorIndex).trim();
                artist = fullTitleAndArtist.substring(separatorIndex + 3).trim();
            } else {
                title = fullTitleAndArtist; // Fallback if no " - "
                artist = ""; // No artist found in this format
            }
            LOGGER.debug("Matched Format 1. Extracted Title: '{}', Artist: '{}'", title, artist);
        } else {
            // Try format 2: Skipping 'Title' by 'Artist'
            Matcher matcher2 = SKIPPED_SONG_PATTERN_FORMAT2.matcher(message);
            if (matcher2.find()) {
                String group1 = matcher2.group(1).trim(); // e.g., "The Rolling Stones - Start Me Up" or "Blue ?yster Cult"
                String group2 = matcher2.group(2).trim(); // e.g., "Remastered 2009" or "(Don't Fear) The Reaper"

                String potentialArtist = null;
                String potentialTitle = null;

                // Attempt to split group1 by " - "
                int firstSeparatorIndex = group1.indexOf(" - ");
                
                if (firstSeparatorIndex != -1) {
                    // Case: group1 is "Artist - Title" or "Artist - Album - Title"
                    potentialArtist = group1.substring(0, firstSeparatorIndex).trim();
                    potentialTitle = group1.substring(firstSeparatorIndex + 3).trim();
                    // Append group2 (version info) to title if it exists
                    if (!group2.isEmpty()) {
                        potentialTitle += " (" + group2 + ")";
                    }
                } else {
                    // Case: No " - " in group1, assume group1 is Artist and group2 is Title
                    potentialArtist = group1;
                    potentialTitle = group2;
                }

                // Now try to find the song with the derived artist and title
                Song foundSong = null;
                if (potentialArtist != null && potentialTitle != null) {
                    LOGGER.debug("Searching for Title: '{}', Artist: '{}'", potentialTitle, potentialArtist);
                    foundSong = songService.findByTitleAndArtist(potentialTitle, potentialArtist);
                }

                if (foundSong != null) {
                    artist = foundSong.getArtist(); // Use the artist from the found song
                    title = foundSong.getTitle();   // Use the title from the found song
                    skippedButExistingSongs.add(foundSong);
                    LOGGER.debug("Identified skipped song '{}' by '{}' with path '{}'", title, artist, foundSong.getPath());
                } else {
                    LOGGER.warn("Skipped song '{}' by '{}' not found in database.", group1, group2); // Log original groups for debugging
                }
            }
        }

        if (title != null) {
            LOGGER.debug("Attempting to find skipped song in DB - Title: '{}', Artist: '{}'", title, artist);
            try {
                Song existingSong = songService.findByTitleAndArtist(artist, title); // Reverted to original argument order
                if (existingSong != null) {
                    skippedButExistingSongs.add(existingSong);
                    LOGGER.debug("Identified skipped song '{}' by '{}' with path '{}'", title, artist, existingSong.getPath());
                } else {
                    LOGGER.warn("Skipped song '{}' by '{}' not found in database.", title, artist);
                }
            } catch (Exception e) {
                LOGGER.warn("Error finding skipped song by title/artist: '{}' by '{}'", title, artist, e);
            }
        } else {
            // Check for successful spotdl download
            Matcher spotdlMatcher = SPOTDL_DOWNLOAD_SUCCESS_PATTERN.matcher(message);
            if (spotdlMatcher.find()) {
                String filename = spotdlMatcher.group(1);
                downloadedFileNames.add(filename);
                LOGGER.debug("Identified spotdl downloaded file: '{}'", filename);
            } else {
                // Check for successful yt-dlp merge (final file)
                Matcher ytdlpMatcher = YTDLP_MERGING_PATTERN.matcher(message);
                if (ytdlpMatcher.find()) {
                    String filename = ytdlpMatcher.group(1);
                    downloadedFileNames.add(filename);
                    LOGGER.debug("Identified yt-dlp merged file: '{}'", filename);
                } else {
                    LOGGER.debug("No title/artist or downloaded file extracted from message: {}", message.trim());
                }
            }
        }
    }

    public boolean isImporting() {
        return isImporting.get();
    }

    public String getOutputCache() {
        return outputCache.toString();
    }

    public ImportInstallationStatus getInstallationStatus() {
        // ... (rest of the method is unchanged)
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

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append(System.lineSeparator());
                }
            }
            pythonVersionOutput = outputBuilder.toString();

            try {
                pythonExitCode = pythonProcess.waitFor();
            } catch (InterruptedException ex) {
                LOGGER.error("Interrupted while waiting for python process", ex);
                Thread.currentThread().interrupt();
            }

            if (pythonExitCode == 0) {
                pythonExecutable = "python";
                pythonInstalled = true;
                pythonMessage = "Python found: " + pythonVersionOutput.trim();
            } else {
                if (pythonVersionOutput.contains("Microsoft Store") || pythonVersionOutput.contains("App execution aliases")) {
                    microsoftStoreAliasDetected = true;
                }
                LOGGER.warn("Attempted 'python --version' failed. Output: {}", pythonVersionOutput.trim());
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to execute 'python --version'.", e);
        }

        // Attempt 2: If 'python' failed, try 'py -3 --version' (common on Windows)
        if (pythonExecutable == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("py", "-3", "--version");
                pb.redirectErrorStream(true);
                Process pythonProcess = pb.start();

                StringBuilder outputBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuilder.append(line).append(System.lineSeparator());
                    }
                }
                pythonVersionOutput = outputBuilder.toString();

                try {
                    pythonExitCode = pythonProcess.waitFor();
                } catch (InterruptedException ex) {
                    LOGGER.error("Interrupted while waiting for python process", ex);
                    Thread.currentThread().interrupt();
                }

                if (pythonExitCode == 0) {
                    pythonExecutable = "py -3";
                    pythonInstalled = true;
                    pythonMessage = "Python found: " + pythonVersionOutput.trim();
                } else {
                    LOGGER.warn("Attempted 'py -3 --version' failed. Output: {}", pythonVersionOutput.trim());
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to execute 'py -3 --version'.", e);
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
            LOGGER.info("Python check passed using '{}'.", pythonExecutable);
        }

        // Check for spotdl
        try {
            Process importProcess = new ProcessBuilder("spotdl", "--version").redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(importProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = importProcess.waitFor();

            if (exitCode == 0) {
                importInstalled = true;
                importMessage = "SpotDL found: " + output.toString().trim();
            } else {
                importMessage = "SpotDL is not installed or not found in PATH. Please install SpotDL (pip install spotdl). Output: " + output.toString();
            }
            LOGGER.info("SpotDL check passed: {}", output.toString().trim());
        } catch (IOException e) {
            importMessage = "Failed to execute 'spotdl --version'. SpotDL might not be installed or configured correctly. Error: " + e.getMessage();
            LOGGER.warn(importMessage, e);
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for spotdl process", ex);
            Thread.currentThread().interrupt();
        }

        // Check for ffmpeg
        try {
            Process ffmpegProcess = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = ffmpegProcess.waitFor();

            if (exitCode == 0) {
                ffmpegInstalled = true;
                ffmpegMessage = "FFmpeg found: " + output.toString().trim().split("\n")[0];
            } else {
                ffmpegMessage = "FFmpeg is not installed or not found in PATH. Please install FFmpeg. Output: " + output.toString();
            }
            LOGGER.info("FFmpeg check passed: {}", output.toString().trim().split("\n")[0]);
        } catch (IOException e) {
            ffmpegMessage = "Failed to execute 'ffmpeg -version'. FFmpeg might not be installed or configured correctly. Error: " + e.getMessage();
            LOGGER.warn(ffmpegMessage, e);
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for ffmpeg process", ex);
            Thread.currentThread().interrupt();
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
