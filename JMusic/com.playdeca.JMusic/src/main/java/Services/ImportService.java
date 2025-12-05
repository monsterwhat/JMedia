package Services;

import API.WS.ImportStatusSocket;
import Controllers.PlaybackQueueController;
import Controllers.SettingsController;
import Models.DTOs.ImportInstallationStatus;
import Models.PlaybackState;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import java.time.LocalDateTime;
import java.util.Base64;
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
import org.jaudiotagger.tag.datatype.Artwork;

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

    // Pattern to extract song title and artist from "Skipping" messages (Format 1: "Skipping <Song Name> (file already exists) (duplicate)")
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT1 = Pattern.compile("Skipping (.+) \\(file already exists\\) \\(duplicate\\)");
    // Pattern to extract song title and artist from "Skipping" messages (Format 2: 'Title' by 'Artist')
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT2 = Pattern.compile("Skipping '([^']+)' by '([^']+)' as it's already downloaded");

    // Pattern to extract filename from spotdl successful download message
    // Pattern to extract filename from spotdl successful download message
    // This new pattern handles titles with quotes and matches the colon at the end of the line.
    private static final Pattern SPOTDL_DOWNLOAD_SUCCESS_PATTERN = Pattern.compile("Downloaded \"(.+?)\":");

    // Pattern to extract filename from yt-dlp merging message (final file name)
    private static final Pattern YTDLP_MERGING_PATTERN = Pattern.compile("\\[ffmpeg\\] Merging formats into \"([^\"]+)\"");

    // List to store Song objects that were skipped by spotdl but found in our DB
    private List<Song> skippedButExistingSongs;
    // List to store the filenames of songs successfully downloaded during the current import
    private List<String> downloadedFileNames;
    // List to store the filenames of downloaded songs that were not found in the database
    private List<String> unprocessedSongs;

    // Temporarily stores artist/title for skipped songs, for post-scan DB lookup
    private List<String[]> tempSkippedSongInfo;

    // Combine newly added songs with existing ones identified as skipped
    List<Song> finalSongsForPlaylist = new ArrayList<>();
    Set<Long> songIdsInFinalList = new HashSet<>();

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

            outputCache.setLength(0);
            skippedButExistingSongs = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            downloadedFileNames = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            unprocessedSongs = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            tempSkippedSongInfo = Collections.synchronizedList(new ArrayList<>()); // Initialize for each new import
            broadcast("Starting new import process...\n", profileId);

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
                broadcast("Executing command: " + String.join(" ", command) + "\n", profileId);

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.environment().put("PYTHONUNBUFFERED", "1");
                processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        broadcast(line + "\n", profileId); // This will now parse skipped songs
                    }
                }

                int exitCode = process.waitFor();
                broadcast("Process finished with exit code: " + exitCode + "\n", profileId);

                if (exitCode != 0) {
                    throw new Exception("Import process exited with error code " + exitCode + ".\nExternal tool output:\n" + outputCache.toString());
                }

                broadcast("Download completed successfully. Triggering import folder scan to discover new files...\n", profileId);
                try {
                    settings.scanImportFolder();
                    broadcast("Import folder scan completed. Identifying newly added songs...\n", profileId);
                } catch (Exception e) {
                    LOGGER.error("An error occurred during the automatic import folder scan.", e);
                    broadcast("WARNING: The automatic import folder scan failed. Newly downloaded songs may not be available immediately. Error: " + e.getMessage() + "\n", profileId);
                }

                // Construct relative paths for downloaded files
                Path baseMusicFolder = settings.getMusicFolder().toPath();
                Set<String> relativeDownloadedPaths = new HashSet<>();
                String fileExtension = "." + (format != null && !format.isEmpty() ? format : "mp3");
                for (String filename : downloadedFileNames) {
                    Path fullPath = normalizedDownloadPath.resolve(filename + fileExtension);
                    // Use forward slashes for cross-platform compatibility in the database.
                    String relativePath = baseMusicFolder.relativize(fullPath).toString().replace('\\', '/');
                    relativeDownloadedPaths.add(relativePath);
                }

                // Find these songs in the database
                List<Song> newlyAddedSongs = songService.findByRelativePaths(relativeDownloadedPaths);

                // Determine which downloaded files were not found in the database after the scan
                Set<String> foundSongPaths = newlyAddedSongs.stream()
                        .map(Song::getPath)
                        .collect(Collectors.toSet());

                for (String downloadedPath : relativeDownloadedPaths) {
                    if (!foundSongPaths.contains(downloadedPath)) {
                        // Extract just the filename for display
                        Path path = Paths.get(downloadedPath);
                        unprocessedSongs.add("Downloaded but not found in DB after scan: " + path.getFileName().toString());
                    }
                }

                // Now, process the deferred skipped songs from tempSkippedSongInfo with flexible artist matching
                if (tempSkippedSongInfo != null && !tempSkippedSongInfo.isEmpty()) {
                    // Fetch all songs into a map for efficient lookup
                    List<Song> allSongs = songService.findAll();
                    java.util.Map<String, List<Song>> songsByTitle = allSongs.stream()
                            .filter(s -> s.getTitle() != null)
                            .collect(Collectors.groupingBy(Song::getTitle));

                    for (String[] artistTitle : tempSkippedSongInfo) {
                        String parsedArtist = artistTitle[0];
                        String parsedTitle = artistTitle[1];

                        List<Song> titleMatches = songsByTitle.get(parsedTitle);
                        Song bestMatch = null;

                        if (titleMatches != null) {
                            // Find the best match: one where the DB artist contains the parsed artist
                            for (Song candidate : titleMatches) {
                                if (candidate.getArtist() != null && candidate.getArtist().toLowerCase().contains(parsedArtist.toLowerCase())) {
                                    bestMatch = candidate;
                                    break; // Found a good match
                                }
                            }
                        }

                        if (bestMatch != null) {
                            skippedButExistingSongs.add(bestMatch);
                        } else {
                            unprocessedSongs.add("Skipped and not found in DB post-scan (flexible match): " + parsedTitle + " by " + parsedArtist);
                        }
                    }
                }

                // Add newly added songs first
                for (Song song : newlyAddedSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                // Add deferred skipped songs that were found in DB
                for (Song song : skippedButExistingSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                // Add newly added songs first                                                                           
                for (Song song : newlyAddedSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                // Add deferred skipped songs that were found in DB                                                       
                for (Song song : skippedButExistingSongs) {
                    if (songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    }
                }

                if (playlistName != null && !playlistName.trim().isEmpty()) {
                    addSongsToPlaylist(playlistName, finalSongsForPlaylist, profileId, url);
                }

                if (queueAfterDownload) {
                    addSongToQueue(finalSongsForPlaylist, profileId);
                }

                if (!unprocessedSongs.isEmpty()) {
                    broadcast("WARNING: The following songs were not fully processed during import:\n", profileId);
                    for (String unprocessed : unprocessedSongs) {
                        broadcast("- " + unprocessed + "\n", profileId);
                    }
                }

                broadcast("Import process fully completed.\n", profileId);

            } catch (Exception e) {
                LOGGER.error("An error occurred during the import process.", e);
                broadcast("ERROR: " + e.getMessage() + "\n", profileId);
            } finally {
                isImporting.set(false);
                skippedButExistingSongs = null; // Clear for next import
                downloadedFileNames = null; // Clear for next import
                unprocessedSongs = null; // Clear for next import
                tempSkippedSongInfo = null; // Clear for next import
                broadcast("[IMPORT_FINISHED]", profileId);
            }
        });
    }

    private void addSongToQueue(List<Song> songsToQueue, Long profileId) {
        if (songsToQueue == null || songsToQueue.isEmpty()) {
            broadcast("No songs found to queue.\n", profileId);
            return;
        }
        // For "Find and Play", we assume only one song is downloaded/identified.
        // We take the first one from the list.
        Song songToQueue = songsToQueue.get(0);
        try {
            // Get the singleton playback state
            PlaybackState currentState = playbackStateService.getOrCreateState(profileId);

            // Add the new song to the queue
            List<Long> songIds = Collections.singletonList(songToQueue.id);
            playbackQueueController.addToQueue(currentState, songIds, false, profileId); // false = add to end

            // Save the modified state
            playbackStateService.saveState(profileId, currentState);

            broadcast("{\"type\": \"song-queued\", \"songTitle\": \"" + songToQueue.getTitle() + "\"}\n", profileId);
        } catch (Exception e) {
            LOGGER.error("Failed to queue song: {}", songToQueue.getTitle(), e);
            broadcast("ERROR: Failed to queue song '" + songToQueue.getTitle() + "': " + e.getMessage() + "\n", profileId);
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
            command.add("--restrict");
            command.add("ascii");
            command.add("--output");
            command.add(downloadPath.toString());
            if (format != null && !format.isEmpty()) {
                command.add("--format");
                command.add(format);
            }

            Integer combinedThreads = null;
            if (downloadThreads != null && downloadThreads > 0) {
                combinedThreads = downloadThreads;
            } else if (searchThreads != null && searchThreads > 0) {
                combinedThreads = searchThreads;
            }

            if (combinedThreads != null) {
                command.add("--threads");
                command.add(combinedThreads.toString());
            }
        }
        return command;
    }

    private void addSongsToPlaylist(String playlistName, List<Song> songsToAdd, Long profileId, String originalUrl) {
        if (songsToAdd == null || songsToAdd.isEmpty()) {
            broadcast("No songs found to add to playlist '" + playlistName + "'.\n", profileId);
            return;
        }
        try {
            String trimmedPlaylistName = playlistName.trim();
            Playlist targetPlaylist = playlistService.findOrCreatePlaylistInNewTx(trimmedPlaylistName);
            if (targetPlaylist == null) {
                broadcast("Could not find or create playlist '" + trimmedPlaylistName + "'. Skipping.\n", profileId);
                return;
            }
            
            // Set original link if this is a new playlist and URL is provided
            if (targetPlaylist.id == null && originalUrl != null && !originalUrl.trim().isEmpty()) {
                targetPlaylist.setOriginalLink(originalUrl.trim());
                // Make imported playlists global by default
                targetPlaylist.setIsGlobal(true);
            }
            
            playlistService.addSongsToPlaylist(targetPlaylist, songsToAdd);
            broadcast("Added " + songsToAdd.size() + " songs to playlist '" + trimmedPlaylistName + "'.\n", profileId);
        } catch (Exception e) {
            LOGGER.error("Failed to add songs to playlist: {}", playlistName, e);
            broadcast("ERROR: Failed to add songs to playlist '" + playlistName + "': " + e.getMessage() + "\n", profileId);
        }
    }

    private String sanitizeFilename(String filename) {
        // Remove characters that are illegal in Windows filenames
        // < > : " / \ | ? *
        // Also remove leading/trailing spaces and dots
        String sanitized = filename.replaceAll("[<>:\"/\\\\|?*]", "");
        sanitized = sanitized.replaceAll("^\\.+|\\s+\\.|\\s+$", ""); // Remove leading dots, trailing dots and spaces
        return sanitized;
    }

    private void broadcast(String message, Long profileId) {
        outputCache.append(message);
        importStatusSocket.broadcast(message, profileId);

        LOGGER.debug("Processing message: {}", message.trim());

        String title = null;
        String artist = null;

        // Try format 1: Skipping "Artist - Title"
        Matcher matcher1 = SKIPPED_SONG_PATTERN_FORMAT1.matcher(message);
        if (matcher1.find()) {
            String fullTitleAndArtist = matcher1.group(1).trim();
            int separatorIndex = fullTitleAndArtist.indexOf(" - ");
            if (separatorIndex != -1) {
                artist = fullTitleAndArtist.substring(0, separatorIndex).trim();
                title = fullTitleAndArtist.substring(separatorIndex + 3).trim();
            } else {
                title = fullTitleAndArtist; // Fallback if no " - "
                artist = ""; // No artist found in this format
            }
            LOGGER.debug("Matched Format 1. Extracted Artist: '{}', Title: '{}'", artist, title);
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
            tempSkippedSongInfo.add(new String[]{artist, title});
            LOGGER.debug("Queued skipped song for post-scan check - Artist: '{}', Title: '{}'", artist, title);
        } else {
            // Check for successful spotdl download
            Matcher spotdlMatcher = SPOTDL_DOWNLOAD_SUCCESS_PATTERN.matcher(message);
            if (spotdlMatcher.find()) {
                String filename = spotdlMatcher.group(1);
                downloadedFileNames.add(sanitizeFilename(filename));
                LOGGER.debug("Identified spotdl downloaded file: '{}'", filename);
            } else {
                // Check for successful yt-dlp merge (final file)
                Matcher ytdlpMatcher = YTDLP_MERGING_PATTERN.matcher(message);
                if (ytdlpMatcher.find()) {
                    String filename = ytdlpMatcher.group(1);
                    downloadedFileNames.add(sanitizeFilename(filename));
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

    /**
     * Extracts metadata from an audio file and creates a Song object.
     *
     * @param audioFile The audio file to extract metadata from.
     * @param relativePath The relative path of the audio file within the music
     * library.
     * @return A new Song object populated with extracted metadata, or null if
     * metadata extraction fails.
     */
    public Song extractMetadata(File audioFile, String relativePath) {
        try {
            AudioFile f = AudioFileIO.read(audioFile);
            Tag tag = f.getTag();

            Song song = new Song();
            song.setPath(relativePath);
            song.setDateAdded(LocalDateTime.now());

            if (tag != null) {
                song.setTitle(tag.getFirst(FieldKey.TITLE));
                song.setArtist(tag.getFirst(FieldKey.ARTIST));
                song.setAlbum(tag.getFirst(FieldKey.ALBUM));
                song.setAlbumArtist(tag.getFirst(FieldKey.ALBUM_ARTIST));
                song.setGenre(tag.getFirst(FieldKey.GENRE));
                song.setLyrics(tag.getFirst(FieldKey.LYRICS)); // Extract lyrics

                // Duration in seconds
                song.setDurationSeconds(f.getAudioHeader().getTrackLength());

                // Artwork
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    song.setArtworkBase64(Base64.getEncoder().encodeToString(artwork.getBinaryData()));
                }
            } else {
                LOGGER.warn("No ID3 tag found for file: {}", audioFile.getAbsolutePath());
                // Fallback to filename if no tag
                song.setTitle(audioFile.getName().substring(0, audioFile.getName().lastIndexOf('.')));
                song.setArtist("Unknown Artist");
            }

            // Ensure no nulls for critical fields
            if (song.getTitle() == null || song.getTitle().isBlank()) {
                song.setTitle(audioFile.getName().substring(0, audioFile.getName().lastIndexOf('.')));
            }
            if (song.getArtist() == null || song.getArtist().isBlank()) {
                song.setArtist("Unknown Artist");
            }
            if (song.getAlbum() == null || song.getAlbum().isBlank()) {
                song.setAlbum("Unknown Album");
            }
            if (song.getAlbumArtist() == null || song.getAlbumArtist().isBlank()) {
                song.setAlbumArtist(song.getArtist());
            }
            if (song.getGenre() == null || song.getGenre().isBlank()) {
                song.setGenre("Unknown Genre");
            }
            if (song.getDurationSeconds() <= 0) {
                song.setDurationSeconds(0); // Default to 0 if not found
            }

            return song;

        } catch (IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException | CannotReadException e) {
            LOGGER.error("Failed to extract metadata from file: {}", audioFile.getAbsolutePath(), e);
            return null;
        }
    }

    public ImportInstallationStatus getInstallationStatus() {

        boolean pythonInstalled = false;
        String pythonMessage = "";
        boolean importInstalled = false;
        String importMessage = "";
        boolean ffmpegInstalled = false;
        String ffmpegMessage = "";
        boolean whisperInstalled = false;
        String whisperMessage = "";

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

        // Check for whisper
        try {
            ProcessBuilder whisperPb;
            List<String> command = new ArrayList<>();
            if (pythonExecutable != null) {
                Collections.addAll(command, pythonExecutable.split(" "));
                command.add("-m");
                command.add("whisper");
                command.add("-h"); // Using -h for help
            } else {
                // Fallback to checking whisper directly if python executable is not determined
                command.add("whisper");
                command.add("-h");
            }
            whisperPb = new ProcessBuilder(command);
            whisperPb.environment().put("PYTHONIOENCODING", "UTF-8");

            Process whisperProcess = whisperPb.redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(whisperProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = whisperProcess.waitFor();

            String outputString = output.toString();

            // The help message for whisper starts with "usage:". If that's present and exit code is 0, it's installed.
            if (exitCode == 0 && outputString.toLowerCase().contains("usage:")) {
                whisperInstalled = true;
                whisperMessage = "Whisper found.";
                LOGGER.info("Whisper check passed.");
            } else {
                whisperMessage = "Whisper is not installed or not found in PATH. Please install OpenAI's Whisper (pip install openai-whisper).";
                if (!outputString.trim().isEmpty()) {
                    whisperMessage += " Output: " + outputString.trim();
                }
                LOGGER.warn("Whisper check failed. Exit code: {}. Output: {}", exitCode, outputString.trim());
            }
        } catch (IOException e) {
            whisperMessage = "Failed to execute whisper check. Whisper might not be installed or configured correctly. Error: " + e.getMessage();
            LOGGER.warn(whisperMessage, e);
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for whisper process", ex);
            Thread.currentThread().interrupt();
        }

        return new ImportInstallationStatus(pythonInstalled, importInstalled, ffmpegInstalled, whisperInstalled, pythonMessage, importMessage, ffmpegMessage, whisperMessage);
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
            if (!status.whisperInstalled) {
                errorMessage.append("- Whisper: ").append(status.whisperMessage).append("\n");
            }
            throw new Exception(errorMessage.toString());
        }
    }
}
