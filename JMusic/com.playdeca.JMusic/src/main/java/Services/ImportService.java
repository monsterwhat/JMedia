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
import java.util.Map;
import java.util.HashMap;

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
    
    // Store the detected execution method for SpotDL
    private volatile String spotdlExecutionMethod = "DIRECT_COMMAND"; // "DIRECT_COMMAND" or "PYTHON_MODULE"
    private volatile String detectedPythonExecutable = "python"; // Store the working Python executable

    // Pattern to extract song title and artist from "Skipping" messages (Format 1: "Skipping \"Artist - Title\" as it's already downloaded")
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT1 = Pattern.compile("Skipping \"([^\"]+)\" as it's already downloaded");
    // Pattern to extract song title and artist from "Skipping" messages (Format 2: 'Title' by 'Artist')
    private static final Pattern SKIPPED_SONG_PATTERN_FORMAT2 = Pattern.compile("Skipping '([^']+)' by '([^']+)' as it's already downloaded");

    // Pattern to extract filename from spotdl successful download message
    // Pattern to extract filename from spotdl successful download message
    // This new pattern handles titles with quotes and matches the colon at the end of the line.
    private static final Pattern SPOTDL_DOWNLOAD_SUCCESS_PATTERN = Pattern.compile("Downloaded \"(.+?)\":");

    // Pattern to extract filename from yt-dlp merging message (final file name)
    private static final Pattern YTDLP_MERGING_PATTERN = Pattern.compile("\\[ffmpeg\\] Merging formats into \"([^\"]+)\"");

    // Pattern to detect rate limit errors
    private static final Pattern RATE_LIMIT_PATTERN = Pattern.compile("too many 429 error responses|rate.*limit|429");

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
            finalSongsForPlaylist = new ArrayList<>(); // Initialize for each new import
            songIdsInFinalList = new HashSet<>(); // Initialize for each new import
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

                // Check if we hit rate limits and have some songs processed
                if (exitCode != 0 && isRateLimitHit(outputCache.toString()) && hasProcessedSongs()) {
                    retryWithReducedThreads(url, format, normalizedDownloadPath, profileId);
                }
                // Don't fail the entire import if SpotDL hits rate limits but processed some songs
                // Only fail if we have no songs processed at all
                else if (exitCode != 0 && !hasProcessedSongs()) {
                    throw new Exception("Import process exited with error code " + exitCode + " and no songs were processed.\nExternal tool output:\n" + outputCache.toString());
                } else if (exitCode != 0) {
                    broadcast("WARNING: Import process exited with error code " + exitCode + " but some songs were processed. Continuing with playlist creation...\n", profileId);
                }

                // Construct relative paths for downloaded files (needed for both targeted and fallback scanning)
                Path baseMusicFolder = settings.getMusicFolder().toPath();
                Set<String> relativeDownloadedPaths = new HashSet<>();
                String fileExtension = "." + (format != null && !format.isEmpty() ? format : "mp3");
                for (String filename : downloadedFileNames) {
                    Path fullPath = normalizedDownloadPath.resolve(filename + fileExtension);
                    // Use forward slashes for cross-platform compatibility in the database.
                    String relativePath = baseMusicFolder.relativize(fullPath).toString().replace('\\', '/');
                    relativeDownloadedPaths.add(relativePath);
                }

                // Separate actually downloaded files from skipped files
                List<String> actuallyDownloadedFiles = new ArrayList<>();
                List<String> actuallySkippedFiles = new ArrayList<>();
                
                for (String filename : downloadedFileNames) {
                    File file = normalizedDownloadPath.resolve(filename + "." + (format != null && !format.isEmpty() ? format : "mp3")).toFile();
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

                // Only scan actually downloaded files
                if (actuallyDownloadedFiles.isEmpty()) {
                    broadcast("No new files to scan. Processing skipped songs only...\n", profileId);
                    LOGGER.info("Processing {} skipped songs", tempSkippedSongInfo != null ? tempSkippedSongInfo.size() : 0);
                    // Process only skipped songs that were found in database
                    // Process skipped songs inline since no new files to scan
                    if (tempSkippedSongInfo != null && !tempSkippedSongInfo.isEmpty()) {
                        List<Song> allSongs = songService.findAll();

                        for (String[] artistTitle : tempSkippedSongInfo) {
                            String parsedArtist = artistTitle[0];
                            String parsedTitle = artistTitle[1];

                            // Use fuzzy matching to find best song
                            Song bestMatch = findBestMatch(parsedArtist, parsedTitle, allSongs);

                            if (bestMatch != null) {
                                skippedButExistingSongs.add(bestMatch);
                            } else {
                                unprocessedSongs.add("Skipped and not found in DB: " + parsedTitle + " by " + parsedArtist);
                            }
                        }
                    }
                    // Continue to playlist creation even with no new files
                } else {

                    broadcast("Download completed successfully. Triggering targeted scan for downloaded files...\n", profileId);
                    List<Song> newlyAddedSongs;
                    try {
                        // Use targeted scanning instead of full folder scan
                        newlyAddedSongs = settings.scanSpecificFiles(actuallyDownloadedFiles, normalizedDownloadPath.toString());
                        broadcast("Targeted scan completed. Found " + newlyAddedSongs.size() + " newly added songs.\n", profileId);
                    } catch (Exception e) {
                        LOGGER.error("An error occurred during targeted file scan.", e);
                        broadcast("WARNING: The targeted file scan failed. Falling back to incremental library scan. Error: " + e.getMessage() + "\n", profileId);
                        
                        // Fallback to incremental scan instead of full folder scan
                        try {
                            newlyAddedSongs = settings.scanLibraryIncremental();
                            broadcast("Incremental library scan completed. Found " + newlyAddedSongs.size() + " newly added songs.\n", profileId);
                        } catch (Exception fallbackException) {
                            LOGGER.error("Incremental scan also failed", fallbackException);
                            broadcast("WARNING: Incremental scan failed. Falling back to full import folder scan. Error: " + fallbackException.getMessage() + "\n", profileId);
                            
                            // Final fallback to original method
                            try {
                                settings.scanImportFolder();
                                broadcast("Fallback import folder scan completed. Identifying newly added songs...\n", profileId);
                                newlyAddedSongs = songService.findByRelativePaths(relativeDownloadedPaths);
                            } catch (Exception finalFallbackException) {
                                LOGGER.error("Final fallback scan also failed", finalFallbackException);
                                broadcast("ERROR: All scan methods failed. Newly downloaded songs may not be available immediately. Error: " + finalFallbackException.getMessage() + "\n", profileId);
                                newlyAddedSongs = new ArrayList<>();
                            }
                        }
                    }

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

                // Add newly added songs first
                for (Song song : newlyAddedSongs) {
                    if (song.id != null && songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    } else if (song.id != null) {
                        broadcast("DEBUG: Skipping duplicate song: '" + song.getTitle() + "' by '" + song.getArtist() + "'\n", profileId);
                    }
                }

                // Add deferred skipped songs that were found in DB
                for (Song song : skippedButExistingSongs) {
                    if (song.id != null && songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    } else if (song.id != null) {
                        broadcast("DEBUG: Skipping duplicate skipped song: '" + song.getTitle() + "' by '" + song.getArtist() + "'\n", profileId);
                    }
                }

                broadcast("Final song list contains " + finalSongsForPlaylist.size() + " unique songs for playlist creation.\n", profileId);
                broadcast("DEBUG: Skipped songs found in DB: " + skippedButExistingSongs.size() + "\n", profileId);
                if (finalSongsForPlaylist.isEmpty()) {
                    broadcast("DEBUG: No songs found for playlist creation. tempSkippedSongInfo size: " + 
                        (tempSkippedSongInfo != null ? tempSkippedSongInfo.size() : 0) + ", skippedButExistingSongs size: " + skippedButExistingSongs.size() + "\n", profileId);
                }
                }

                // Now, process the deferred skipped songs from tempSkippedSongInfo with fuzzy matching
                if (tempSkippedSongInfo != null && !tempSkippedSongInfo.isEmpty()) {
                    // Fetch all songs for fuzzy matching
                    List<Song> allSongs = songService.findAll();
                    broadcast("DEBUG: Processing " + tempSkippedSongInfo.size() + " skipped songs with fuzzy matching against " + allSongs.size() + " DB songs\n", profileId);

                    for (String[] artistTitle : tempSkippedSongInfo) {
                        String parsedArtist = artistTitle[0];
                        String parsedTitle = artistTitle[1];

                        // Use fuzzy matching to find best song
                        Song bestMatch = findBestMatch(parsedArtist, parsedTitle, allSongs);

                        if (bestMatch != null) {
                            skippedButExistingSongs.add(bestMatch);
                            broadcast("DEBUG: Found fuzzy match: '" + parsedTitle + "' by '" + parsedArtist + "' -> DB: '" + bestMatch.getTitle() + "' by '" + bestMatch.getArtist() + "'\n", profileId);
                        } else {
                            unprocessedSongs.add("Skipped and not found in DB post-scan (fuzzy match): " + parsedTitle + " by " + parsedArtist);
                            broadcast("DEBUG: No fuzzy match found for: '" + parsedTitle + "' by '" + parsedArtist + "'\n", profileId);
                        }
                    }
                }

                // Add deferred skipped songs that were found in DB
                for (Song song : skippedButExistingSongs) {
                    if (song.id != null && songIdsInFinalList.add(song.id)) {
                        finalSongsForPlaylist.add(song);
                    } else if (song.id != null) {
                        LOGGER.debug("Skipping duplicate skipped song: {} by {}", song.getTitle(), song.getArtist());
                    }
                }



                broadcast("Final song list contains " + finalSongsForPlaylist.size() + " unique songs for playlist creation.\n", profileId);
                LOGGER.info("Final song list contains {} unique songs for playlist creation", finalSongsForPlaylist.size());
                LOGGER.info("Skipped songs found in DB: {}", skippedButExistingSongs.size());
                if (finalSongsForPlaylist.isEmpty()) {
                    LOGGER.warn("No songs found for playlist creation. tempSkippedSongInfo size: {}, skippedButExistingSongs size: {}", 
                        tempSkippedSongInfo != null ? tempSkippedSongInfo.size() : 0, skippedButExistingSongs.size());
                }

                if (playlistName != null && !playlistName.trim().isEmpty()) {
                    broadcast("Creating playlist '" + playlistName + "' with " + finalSongsForPlaylist.size() + " songs...\n", profileId);
                    addSongsToPlaylist(playlistName, finalSongsForPlaylist, profileId, url);
                } else {
                    broadcast("No playlist name provided, skipping playlist creation.\n", profileId);
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
            // Use the detected SpotDL execution method
            if ("PYTHON_MODULE".equals(spotdlExecutionMethod)) {
                Collections.addAll(command, detectedPythonExecutable.split(" "));
                command.add("-m");
                command.add("spotdl");
            } else {
                command.add("spotdl");
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
            
            LOGGER.debug("Found/created playlist '{}' with ID: {}", trimmedPlaylistName, targetPlaylist.id);
            
            // Set original link if this is a new playlist and URL is provided
            if (targetPlaylist.id == null && originalUrl != null && !originalUrl.trim().isEmpty()) {
                targetPlaylist.setOriginalLink(originalUrl.trim());
                // Make imported playlists global by default
                targetPlaylist.setIsGlobal(true);
                LOGGER.debug("Set original link and global flag for new playlist");
            }
            
            // Log song details before adding
            LOGGER.debug("Songs to add to playlist:");
            for (int i = 0; i < Math.min(songsToAdd.size(), 10); i++) {
                Song song = songsToAdd.get(i);
                LOGGER.debug("  {}: '{}' by '{}' (ID: {})", i+1, song.getTitle(), song.getArtist(), song.id);
            }
            if (songsToAdd.size() > 10) {
                LOGGER.debug("  ... and {} more songs", songsToAdd.size() - 10);
            }
            
            playlistService.addSongsToPlaylist(targetPlaylist, songsToAdd);
            broadcast("Added " + songsToAdd.size() + " songs to playlist '" + trimmedPlaylistName + "'.\n", profileId);
            LOGGER.info("Successfully added {} songs to playlist '{}'", songsToAdd.size(), trimmedPlaylistName);
        } catch (Exception e) {
            LOGGER.error("Failed to add songs to playlist: {}", playlistName, e);
            broadcast("ERROR: Failed to add songs to playlist '" + playlistName + "': " + e.getMessage() + "\n", profileId);
        }
    }

    private String normalizeForMatching(String text) {
        if (text == null) return "";
        
        // Convert to lowercase and remove extra whitespace
        String normalized = text.toLowerCase().trim().replaceAll("\\s+", " ");
        
        // Remove common version info and parentheses
        normalized = normalized.replaceAll("\\([^)]*\\)", ""); // Remove anything in parentheses
        normalized = normalized.replaceAll("\\[[^\\]]*\\]", ""); // Remove anything in brackets
        normalized = normalized.replaceAll("\\{[^}]*\\}", ""); // Remove anything in braces
        
        // Remove version suffixes
        normalized = normalized.replaceAll("\\s*-\\s*\\d{4}\\s+remaster\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*\\d{4}\\s+remastered\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*mono\\s+version\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*live\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*acoustic\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*demo\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*extended\\s*$", "");
        normalized = normalized.replaceAll("\\s*-\\s*edit\\s*$", "");
        normalized = normalized.replaceAll("\\s*remaster\\s*$", "");
        normalized = normalized.replaceAll("\\s*remastered\\s*$", "");
        normalized = normalized.replaceAll("\\s*version\\s*$", "");
        
        // Remove punctuation except essential ones
        normalized = normalized.replaceAll("[^a-zA-Z0-9\\s]", "");
        
        // Clean up extra spaces again
        normalized = normalized.replaceAll("\\s+", " ").trim();
        
        return normalized;
    }
    
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        
        // Simple Levenshtein-like similarity for better matching
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0;
        
        // Count matching characters
        int matches = 0;
        int minLength = Math.min(s1.length(), s2.length());
        
        for (int i = 0; i < minLength; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                matches++;
            }
        }
        
        double similarity = (double) matches / maxLength;
        
        // Bonus for containing the other string
        if (s1.contains(s2) || s2.contains(s1)) {
            similarity = Math.max(similarity, 0.8);
        }
        
        return similarity;
    }
    
    private Song findBestMatch(String searchArtist, String searchTitle, List<Song> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        
        String normalizedSearchArtist = normalizeForMatching(searchArtist);
        String normalizedSearchTitle = normalizeForMatching(searchTitle);
        
        Song bestMatch = null;
        double bestScore = 0.0;
        
        for (Song candidate : candidates) {
            String candidateArtist = normalizeForMatching(candidate.getArtist());
            String candidateTitle = normalizeForMatching(candidate.getTitle());
            
            // Calculate artist similarity (weighted more heavily)
            double artistSimilarity = calculateSimilarity(normalizedSearchArtist, candidateArtist);
            
            // Calculate title similarity
            double titleSimilarity = calculateSimilarity(normalizedSearchTitle, candidateTitle);
            
            // Combined score: artist is 60% weight, title is 40% weight
            double combinedScore = (artistSimilarity * 0.6) + (titleSimilarity * 0.4);
            
            // Minimum thresholds
            if (artistSimilarity >= 0.7 && titleSimilarity >= 0.6 && combinedScore > bestScore) {
                bestMatch = candidate;
                bestScore = combinedScore;
            }
        }
        
        return bestMatch;
    }

    private String extractPrimaryArtist(String rawArtist) {
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            return rawArtist;
        }
        
        // Handle multiple artists separated by commas or "feat."
        // Examples: "Motionless In White, Michael Vampire", "America, George Martin"
        String[] artists = rawArtist.split(",\\s*|\\s+feat\\.\\s+|\\s+&\\s+|\\s+/\\s+");
        
        // Return the first (primary) artist
        String primaryArtist = artists[0].trim();
        
        // Remove any remaining "feat." or similar from primary artist
        primaryArtist = primaryArtist.split("\\s+feat\\.\\s+")[0].trim();
        
        return primaryArtist;
    }

    private String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return title;
        }
        
        // Remove version info in parentheses at the end
        // Examples: "Would (2022 Remaster)", "Song Title - 2003 Remaster", "Song (Live Version)"
        String cleaned = title.replaceAll("\\s*\\([^)]*\\)\\s*$", ""); // Remove (anything) at end
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remaster\\s*$", ""); // Remove - 2003 Remaster at end
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remastered\\s*\\d{4}\\s*$", ""); // Remove - 2003 Remastered 2009 at end
        cleaned = cleaned.replaceAll("\\s*-\\s*Mono Version\\s*Remastered\\s*\\d{4}\\s*$", ""); // Remove - Mono Version Remastered 2002 at end
        cleaned = cleaned.trim();
        
        return cleaned.isEmpty() ? title : cleaned; // Return original if cleaning removes everything
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
                String rawArtist = fullTitleAndArtist.substring(0, separatorIndex).trim();
                title = fullTitleAndArtist.substring(separatorIndex + 3).trim();
                
                // Handle multiple artists separated by commas
                // Examples: "Motionless In White, Michael Vampire", "America, George Martin"
                artist = extractPrimaryArtist(rawArtist);
                
                // Clean up title by removing version info in parentheses at end
                // Examples: "Would (2022 Remaster)", "Suite - Judy Blue Eyes - 2003 Remaster"
                title = cleanTitle(title);
            } else {
                title = cleanTitle(fullTitleAndArtist); // Fallback if no " - "
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
                    // Clean up title and append group2 (version info) if it exists
                    potentialTitle = cleanTitle(potentialTitle);
                    if (!group2.isEmpty()) {
                        potentialTitle += " (" + group2 + ")";
                    }
                } else {
                    // Case: No " - " in group1, assume group1 is Artist and group2 is Title
                    potentialArtist = group1;
                    potentialTitle = cleanTitle(group2);
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
            // Check if this is a "Skipping" message that didn't match our patterns
            if (message.trim().startsWith("Skipping")) {
                LOGGER.warn("Skipping message didn't match any pattern: {}", message.trim());
            }
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

    private boolean isRateLimitHit(String output) {
        return RATE_LIMIT_PATTERN.matcher(output.toLowerCase()).find();
    }

    private boolean hasProcessedSongs() {
        return !downloadedFileNames.isEmpty() || (tempSkippedSongInfo != null && !tempSkippedSongInfo.isEmpty());
    }

    private void retryWithReducedThreads(String url, String format, Path downloadPath, Long profileId) throws Exception {
        broadcast("Rate limit detected but we have processed some songs. Waiting 60 seconds to retry...\n", profileId);
        LOGGER.info("Rate limit hit, waiting 60 seconds before retry");
        
        try {
            Thread.sleep(60000); // Wait 60 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broadcast("Retry wait interrupted.\n", profileId);
            return;
        }
        
        broadcast("Retrying import with reduced thread count...\n", profileId);
        
        // Retry with single thread to reduce API pressure
        List<String> retryCommand = buildCommand(url, format, 1, 1, downloadPath);
        broadcast("Executing retry command: " + String.join(" ", retryCommand) + "\n", profileId);
        
        ProcessBuilder retryProcessBuilder = new ProcessBuilder(retryCommand);
        retryProcessBuilder.environment().put("PYTHONUNBUFFERED", "1");
        retryProcessBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        retryProcessBuilder.redirectErrorStream(true);

        Process retryProcess = retryProcessBuilder.start();

        try (BufferedReader retryReader = new BufferedReader(new InputStreamReader(retryProcess.getInputStream()))) {
            String retryLine;
            while ((retryLine = retryReader.readLine()) != null) {
                broadcast(retryLine + "\n", profileId);
            }
        }

        int retryExitCode = retryProcess.waitFor();
        broadcast("Retry process finished with exit code: " + retryExitCode + "\n", profileId);
        
        if (retryExitCode != 0) {
            broadcast("WARNING: Retry also failed, but proceeding with songs processed so far...\n", profileId);
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
        LOGGER.info("Starting library installation status detection...");
        
        boolean pythonInstalled = false;
        String pythonMessage = "Not checked yet";
        boolean spotdlInstalled = false;
        String spotdlMessage = "Not checked yet";
        boolean ffmpegInstalled = false;
        String ffmpegMessage = "Not checked yet";
        boolean whisperInstalled = false;
        String whisperMessage = "Not checked yet";
        
        try {

        // Check for Python
        String pythonExecutable = null;
        String pythonVersionOutput = "";
        int pythonExitCode = -1;
        boolean microsoftStoreAliasDetected = false;

        // Attempt 1: Try 'python --version'
        LOGGER.info("Attempting Python detection with command: python --version");
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
                LOGGER.info("Python detection successful using 'python' command. Exit code: {}, Output: {}", pythonExitCode, pythonVersionOutput.trim());
            } else {
                if (pythonVersionOutput.contains("Microsoft Store") || pythonVersionOutput.contains("App execution aliases")) {
                    microsoftStoreAliasDetected = true;
                    LOGGER.warn("Microsoft Store alias detected during Python detection");
                }
                LOGGER.warn("Python detection failed with 'python' command. Exit code: {}, Output: {}", pythonExitCode, pythonVersionOutput.trim());
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

        // Check for spotdl - try direct command first, then fall back to Python module
        LOGGER.info("Attempting SpotDL detection with command: spotdl --version");
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
                spotdlInstalled = true;
                spotdlExecutionMethod = "DIRECT_COMMAND";
                spotdlMessage = "SpotDL found: " + output.toString().trim();
                LOGGER.info("SpotDL detection successful using direct command. Exit code: {}, Output: {}", exitCode, output.toString().trim());
            } else {
                spotdlMessage = "SpotDL is not installed or not found in PATH. Please install SpotDL (pip install spotdl). Output: " + output.toString();
                LOGGER.warn("SpotDL detection failed with direct command. Exit code: {}, Output: {}", exitCode, output.toString().trim());
            }
        } catch (IOException e) {
            // Fallback to Python module if direct command fails
            if (pythonInstalled) {
                try {
                    Process importProcess = new ProcessBuilder(pythonExecutable, "-m", "spotdl", "--version").redirectErrorStream(true).start();
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(importProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append(System.lineSeparator());
                        }
                    }
                    int exitCode = importProcess.waitFor();

                    if (exitCode == 0) {
                        spotdlInstalled = true;
                        spotdlExecutionMethod = "PYTHON_MODULE";
                        detectedPythonExecutable = pythonExecutable;
                        spotdlMessage = "SpotDL found as Python module: " + output.toString().trim();
                        LOGGER.info("SpotDL check passed using Python module: {}", output.toString().trim());
                    } else {
                        spotdlMessage = "SpotDL is not installed or not found. Please install SpotDL (pip install spotdl). Output: " + output.toString();
                    }
                } catch (IOException | InterruptedException ex) {
                    spotdlMessage = "Failed to execute 'spotdl --version' both as direct command and Python module. SpotDL might not be installed or configured correctly. Error: " + e.getMessage();
                    LOGGER.warn(spotdlMessage, ex);
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                spotdlMessage = "Failed to execute 'spotdl --version'. SpotDL might not be installed or configured correctly. Error: " + e.getMessage();
                LOGGER.warn(spotdlMessage, e);
            }
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for spotdl process", ex);
            Thread.currentThread().interrupt();
        }

        // Check for ffmpeg
        LOGGER.info("Attempting FFmpeg detection with command: ffmpeg -version");
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
                LOGGER.info("FFmpeg detection successful. Exit code: {}, Output: {}", exitCode, output.toString().trim().split("\n")[0]);
            } else {
                ffmpegMessage = "FFmpeg is not installed or not found in PATH. Please install FFmpeg. Output: " + output.toString();
                LOGGER.warn("FFmpeg detection failed. Exit code: {}, Output: {}", exitCode, output.toString().trim());
            }
        } catch (IOException e) {
            ffmpegMessage = "Failed to execute 'ffmpeg -version'. FFmpeg might not be installed or configured correctly. Error: " + e.getMessage();
            LOGGER.warn(ffmpegMessage, e);
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for ffmpeg process", ex);
            Thread.currentThread().interrupt();
        }

        // Check for whisper using Python module
        LOGGER.info("Attempting Whisper detection using Python module");
        try {
            ProcessBuilder whisperPb;
            List<String> command = new ArrayList<>();
            if (pythonExecutable != null) {
                Collections.addAll(command, pythonExecutable.split(" "));
                command.add("-m");
                command.add("whisper");
                command.add("-h"); // Using -h for help
                LOGGER.info("Whisper detection command: {} -m whisper -h", pythonExecutable);
            } else {
                // Fallback to checking whisper directly if python executable is not determined
                command.add("whisper");
                command.add("-h");
                LOGGER.warn("Python executable not found, trying direct whisper command");
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
                LOGGER.info("Whisper detection successful. Exit code: {}, Output contains usage: {}", exitCode, outputString.toLowerCase().contains("usage:"));
            } else {
                whisperMessage = "Whisper is not installed or not found in PATH. Please install OpenAI's Whisper (pip install openai-whisper).";
                if (!outputString.trim().isEmpty()) {
                    whisperMessage += " Output: " + outputString.trim();
                }
                LOGGER.warn("Whisper detection failed. Exit code: {}, Contains usage: {}, Output: {}", exitCode, outputString.toLowerCase().contains("usage:"), outputString.trim());
            }
        } catch (IOException e) {
            whisperMessage = "Failed to execute whisper check. Whisper might not be installed or configured correctly. Error: " + e.getMessage();
            LOGGER.warn(whisperMessage, e);
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted while waiting for whisper process", ex);
            Thread.currentThread().interrupt();
        }

        // Log final detection results
        LOGGER.info("Library installation status detection completed:");
        LOGGER.info("  Python: {} - {}", pythonInstalled ? "INSTALLED" : "NOT INSTALLED", pythonMessage);
        LOGGER.info("  SpotDL: {} - {}", spotdlInstalled ? "INSTALLED" : "NOT INSTALLED", spotdlMessage);
        LOGGER.info("  FFmpeg: {} - {}", ffmpegInstalled ? "INSTALLED" : "NOT INSTALLED", ffmpegMessage);
        LOGGER.info("  Whisper: {} - {}", whisperInstalled ? "INSTALLED" : "NOT INSTALLED", whisperMessage);

        return new ImportInstallationStatus(pythonInstalled, spotdlInstalled, ffmpegInstalled, whisperInstalled, pythonMessage, spotdlMessage, ffmpegMessage, whisperMessage);
        
        } catch (Exception e) {
            LOGGER.error("Critical error during installation status detection", e);
            return new ImportInstallationStatus(
                false, false, false, false,
                "Error checking Python: " + e.getMessage(),
                "Error checking SpotDL: " + e.getMessage(),
                "Error checking FFmpeg: " + e.getMessage(),
                "Error checking Whisper: " + e.getMessage()
            );
        }
    }

    public void installRequirements(Long profileId) throws Exception {
        ImportInstallationStatus status = getInstallationStatus();
        
        if (!status.pythonInstalled) {
            broadcast("Installing Python...\n", profileId);
            installPython(profileId);
        }
        
        // Refresh status after Python installation
        status = getInstallationStatus();
        if (status.pythonInstalled) {
            if (!status.ffmpegInstalled) {
                broadcast("Installing FFmpeg...\n", profileId);
                installFFmpeg(profileId);
            }
            
            if (!status.spotdlInstalled) {
                broadcast("Installing SpotDL...\n", profileId);
                installSpotdl(profileId);
            }
            
            if (!status.whisperInstalled) {
                broadcast("Installing Whisper...\n", profileId);
                installWhisper(profileId);
            }
        }
        
        broadcast("Installation process completed.\n", profileId);
        broadcast("[INSTALLATION_FINISHED]", profileId);
    }

    public void installPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Installing Python via Chocolatey...\n", profileId);
        
        // Try Chocolatey first, fallback to manual installer
        String chocoInstallScript = "choco install python --version=3.13.9 -y";
        try {
            executePowerShellCommand(chocoInstallScript, profileId);
            broadcastInstallationProgress("python", 100, false, profileId);
            broadcast("Python installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey not available, downloading Python installer...\n", profileId);
            broadcastInstallationProgress("python", 25, true, profileId);
            
            // Download Python using PowerShell
            String downloadScript = "Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.13.9/python-3.13.9-amd64.exe' -OutFile '$env:TEMP\\python-installer.exe'";
            executePowerShellCommand(downloadScript, profileId);
            
            broadcastInstallationProgress("python", 50, true, profileId);
            broadcast("Installing Python (this may take a few minutes)...\n", profileId);
            
            // Install Python silently with all users and add to PATH
            String installScript = "Start-Process -FilePath '$env:TEMP\\python-installer.exe' -ArgumentList '/quiet InstallAllUsers=1 PrependPath=1 Include_test=0' -Wait";
            executePowerShellCommand(installScript, profileId);
            
            // Clean up
            executePowerShellCommand("Remove-Item '$env:TEMP\\python-installer.exe' -ErrorAction SilentlyContinue", profileId);
            
            broadcastInstallationProgress("python", 100, false, profileId);
            broadcast("Python installation completed via manual installer\n", profileId);
        }
        
        broadcast("[PYTHON_INSTALLATION_FINISHED]", profileId);
    }

    public void installFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Installing FFmpeg via Chocolatey...\n", profileId);
        
        // Try Chocolatey first, fallback to manual installation
        String chocoInstallScript = "choco install ffmpeg -y";
        try {
            executePowerShellCommand(chocoInstallScript, profileId);
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg installation completed via Chocolatey\n", profileId);
        } catch (Exception e) {
            broadcast("Chocolatey not available, downloading FFmpeg manually...\n", profileId);
            broadcastInstallationProgress("ffmpeg", 25, true, profileId);
            
            // Download FFmpeg using PowerShell
            String downloadScript = "Invoke-WebRequest -Uri 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip' -OutFile '$env:TEMP\\ffmpeg.zip'";
            executePowerShellCommand(downloadScript, profileId);
            
            broadcastInstallationProgress("ffmpeg", 50, true, profileId);
            broadcast("Extracting FFmpeg...\n", profileId);
            
            // Extract FFmpeg
            String extractScript = "Expand-Archive -Path '$env:TEMP\\ffmpeg.zip' -DestinationPath '$env:TEMP\\ffmpeg' -Force";
            executePowerShellCommand(extractScript, profileId);
            
            broadcastInstallationProgress("ffmpeg", 75, true, profileId);
            broadcast("Installing FFmpeg to system...\n", profileId);
            
            // Move FFmpeg to Program Files and add to PATH
            String installScript = "New-Item -Path 'C:\\Program Files\\FFmpeg' -ItemType Directory -Force; " +
                                  "Get-ChildItem '$env:TEMP\\ffmpeg\\ffmpeg-*' | ForEach-Object { Move-Item $_.FullName 'C:\\Program Files\\FFmpeg\\bin' -Force }; " +
                                  "[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';C:\\Program Files\\FFmpeg\\bin', 'Machine')";
            executePowerShellCommand(installScript, profileId);
            
            // Clean up
            executePowerShellCommand("Remove-Item '$env:TEMP\\ffmpeg.zip' -ErrorAction SilentlyContinue; Remove-Item '$env:TEMP\\ffmpeg' -Recurse -ErrorAction SilentlyContinue", profileId);
            
            broadcastInstallationProgress("ffmpeg", 100, false, profileId);
            broadcast("FFmpeg installation completed via manual installer\n", profileId);
        }
        
        broadcast("[FFMPEG_INSTALLATION_FINISHED]", profileId);
    }

    public void installSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Installing SpotDL via pip...\n", profileId);
        
        // Try to find Python executable and use python -m pip
        String pythonExecutable = findPythonExecutable();
        String installScript = pythonExecutable + " -m pip install spotdl";
        executeCommand(installScript, profileId);
        
        // After installation, set the execution method to Python module since we installed via pip
        spotdlExecutionMethod = "PYTHON_MODULE";
        detectedPythonExecutable = pythonExecutable;
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL installation completed\n", profileId);
        broadcast("[SPOTDL_INSTALLATION_FINISHED]", profileId);
    }

    public void installWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Installing Whisper via pip...\n", profileId);
        
        // Try to find Python executable and use python -m pip
        String pythonExecutable = findPythonExecutable();
        String installScript = pythonExecutable + " -m pip install openai-whisper";
        executeCommand(installScript, profileId);
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper installation completed\n", profileId);
        broadcast("[WHISPER_INSTALLATION_FINISHED]", profileId);
    }

    private String findPythonExecutable() throws Exception {
        // Try different Python executables in order of preference
        String[] pythonExecutables = {"python", "py", "python3", "python3.13"};
        
        for (String executable : pythonExecutables) {
            try {
                ProcessBuilder pb = new ProcessBuilder(executable, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    LOGGER.info("Found Python executable: {}", executable);
                    return executable;
                }
            } catch (Exception e) {
                LOGGER.debug("Python executable {} not available: {}", executable, e.getMessage());
            }
        }
        
        throw new Exception("Python executable not found. Please ensure Python is installed and available in PATH.");
    }

    private void broadcastInstallationProgress(String component, int progress, boolean isInstalling, Long profileId) {
        String progressMessage = String.format(
            "{\"type\": \"installation-progress\", \"component\": \"%s\", \"progress\": %d, \"installing\": %s}\n",
            component, progress, isInstalling
        );
        importStatusSocket.broadcast(progressMessage, profileId);
    }
  
    private void executePowerShellCommand(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", command);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    broadcast(line.trim() + "\n", profileId);
                }
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("PowerShell command failed with exit code: " + exitCode);
        }
    }

    private void executeCommand(String command, Long profileId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command.split(" "));
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    broadcast(line.trim() + "\n", profileId);
                }
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("Command failed with exit code: " + exitCode + ": " + command);
        }
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

    public void uninstallPython(Long profileId) throws Exception {
        broadcastInstallationProgress("python", 0, true, profileId);
        broadcast("Uninstalling Python...\n", profileId);
        
        // Uninstall Python using PowerShell
        String uninstallScript = "Get-WmiObject -Class Win32_Product | Where-Object {$_.Name -like '*Python*'} | ForEach-Object { $_.Uninstall() }";
        executePowerShellCommand(uninstallScript, profileId);
        
        // Remove Python from PATH
        String removeFromPathScript = "[Environment]::SetEnvironmentVariable('Path', ([Environment]::GetEnvironmentVariable('Path', 'Machine') -split ';' | Where-Object { $_ -notlike '*Python*' }) -join ';', 'Machine')";
        executePowerShellCommand(removeFromPathScript, profileId);
        
        broadcastInstallationProgress("python", 100, false, profileId);
        broadcast("Python uninstallation completed\n", profileId);
        broadcast("[PYTHON_UNINSTALLATION_FINISHED]", profileId);
    }

    public void uninstallFFmpeg(Long profileId) throws Exception {
        broadcastInstallationProgress("ffmpeg", 0, true, profileId);
        broadcast("Uninstalling FFmpeg...\n", profileId);
        
        // Remove FFmpeg directory
        String removeScript = "Remove-Item 'C:\\Program Files\\FFmpeg' -Recurse -Force -ErrorAction SilentlyContinue";
        executePowerShellCommand(removeScript, profileId);
        
        // Remove FFmpeg from PATH
        String removeFromPathScript = "[Environment]::SetEnvironmentVariable('Path', ([Environment]::GetEnvironmentVariable('Path', 'Machine') -split ';' | Where-Object { $_ -notlike '*FFmpeg*' }) -join ';', 'Machine')";
        executePowerShellCommand(removeFromPathScript, profileId);
        
        broadcastInstallationProgress("ffmpeg", 100, false, profileId);
        broadcast("FFmpeg uninstallation completed\n", profileId);
        broadcast("[FFMPEG_UNINSTALLATION_FINISHED]", profileId);
    }

    public void uninstallSpotdl(Long profileId) throws Exception {
        broadcastInstallationProgress("spotdl", 0, true, profileId);
        broadcast("Uninstalling SpotDL...\n", profileId);
        
        // Try to find Python executable and use python -m pip
        String pythonExecutable = findPythonExecutable();
        String uninstallScript = pythonExecutable + " -m pip uninstall spotdl -y";
        executeCommand(uninstallScript, profileId);
        
        broadcastInstallationProgress("spotdl", 100, false, profileId);
        broadcast("SpotDL uninstallation completed\n", profileId);
        broadcast("[SPOTDL_UNINSTALLATION_FINISHED]", profileId);
    }

    public void uninstallWhisper(Long profileId) throws Exception {
        broadcastInstallationProgress("whisper", 0, true, profileId);
        broadcast("Uninstalling Whisper...\n", profileId);
        
        // Try to find Python executable and use python -m pip
        String pythonExecutable = findPythonExecutable();
        String uninstallScript = pythonExecutable + " -m pip uninstall openai-whisper -y";
        executeCommand(uninstallScript, profileId);
        
        broadcastInstallationProgress("whisper", 100, false, profileId);
        broadcast("Whisper uninstallation completed\n", profileId);
        broadcast("[WHISPER_UNINSTALLATION_FINISHED]", profileId);
    }
}
