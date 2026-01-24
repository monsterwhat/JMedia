package Controllers;

import API.WS.LogSocket;
import API.WS.MusicSocket;
import jakarta.annotation.PostConstruct;
import Models.Settings;
import Models.SettingsLog;
import Models.Song;
import Services.ImportService;
import Services.SettingsService;
import Services.SongService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.datatype.Artwork;

@ApplicationScoped
public class SettingsController implements Serializable {

    @Inject
    private SongService songService;

    @Inject
    private ImportService importService;

    @Inject
    private SettingsService settingsService;

    @Inject
    private MusicSocket musicSocket;
    
    @Inject
    private ObjectMapper objectMapper;

    private final List<ScanResult> failedSongs = Collections.synchronizedList(new ArrayList<>());
    
    private record FFprobeMetadata(String title, String artist) {}

    private FFprobeMetadata getMetadataWithFFprobe(File file) {
        // Command: ffprobe -v error -show_entries format_tags=title,artist -of json "input.mp3"
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format_tags=title,artist", "-of", "json", file.getAbsolutePath());
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.length() > 0) {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(output.toString());
                com.fasterxml.jackson.databind.JsonNode tags = root.path("format").path("tags");
                
                String title = tags.has("title") ? tags.get("title").asText(null) : null;
                String artist = tags.has("artist") ? tags.get("artist").asText(null) : null;

                if (title != null || artist != null) {
                    return new FFprobeMetadata(title, artist);
                }
            }
            return null; // No tags found or ffprobe failed

        } catch (IOException | InterruptedException e) {
            addLog("[ffmpeg] ERROR: Exception with ffprobe for metadata tags on " + file.getName() + ": " + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Inject
    private LogSocket logSocket;

    private String musicLibraryPath;

    private static final int THREADS = Math.max(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    @PostConstruct
    public void init() {
        Settings currentSettings = settingsService.getOrCreateSettings();
        this.musicLibraryPath = currentSettings.getLibraryPath();
        addLog("Music folder initialized from settings: " + musicLibraryPath);
    }

    @PreDestroy
    public void shutdownExecutor() {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class ScanResult {

        String filePath;
        String rejectedReason; // null if successful

        ScanResult(String filePath, String rejectedReason) {
            this.filePath = filePath;
            this.rejectedReason = rejectedReason;
        }
    }

    private static class FileDetails {

        File file;
        String source; // "main" or "import"
        Song songMetadata; // Extracted metadata, not necessarily persisted

        FileDetails(File file, String source, Song songMetadata) {
            this.file = file;
            this.source = source;
            this.songMetadata = songMetadata;
        }
    }

    /**
     * Extracts metadata from a single MP3 file without persisting it. Returns a
     * Song object populated with metadata or null on failure.
     */
    private Song extractMetadataFromFile(File file) {
        String relativePath = file.getName(); // Default to file name in case of early error
        try {
            File baseFolder = getMusicFolder();
            relativePath = baseFolder.toURI().relativize(file.toURI()).getPath();

            Song song = new Song();
            song.setPath(relativePath);
            song.setDateAdded(java.time.LocalDateTime.now()); // Placeholder, not used for comparison

            MP3File mp3File = null;
            Tag tag = null;
            try {
                mp3File = (MP3File) AudioFileIO.read(file);
                tag = mp3File.getTag();
            } catch (org.jaudiotagger.audio.exceptions.CannotReadException e) {
                addLog("[org.jau.tag.id3] WARNING: Could not read MP3 metadata for " + file.getName() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                addLog("[org.jau.tag.id3] WARNING: Runtime error while reading tag for " + file.getName() + ": " + e.getMessage(), e);
            }

            if (tag != null) {
                song.setTitle(safeGet(tag, FieldKey.TITLE));
                song.setArtist(safeGet(tag, FieldKey.ARTIST));
                song.setAlbum(safeGet(tag, FieldKey.ALBUM));
                song.setAlbumArtist(safeGet(tag, FieldKey.ALBUM_ARTIST));
                song.setTrackNumber(parseInt(safeGet(tag, FieldKey.TRACK)));
                song.setDiscNumber(parseInt(safeGet(tag, FieldKey.DISC_NO)));
                song.setReleaseDate(safeGet(tag, FieldKey.YEAR));
                song.setGenre(safeGet(tag, FieldKey.GENRE));
                song.setLyrics(safeGet(tag, FieldKey.LYRICS));
                song.setBpm(parseInt(safeGet(tag, FieldKey.BPM)));

                try {
                    Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null) {
                        byte[] imageData = artwork.getBinaryData();
                        song.setArtworkBase64(java.util.Base64.getEncoder().encodeToString(imageData));
                    } else {
                        song.setArtworkBase64(null);
                    }
                } catch (Exception artworkException) {
                    addLog("[org.jau.tag.id3] WARNING: Failed to extract artwork for " + file.getName() + ": " + artworkException.getMessage());
                    song.setArtworkBase64(null);
                }
            }
            
            // If jaudiotagger failed or gave empty tags, try ffprobe
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                addLog("[ffmpeg] INFO: jaudiotagger failed to provide title/artist for " + file.getName() + ". Trying ffprobe.");
                FFprobeMetadata ffprobeData = getMetadataWithFFprobe(file);
                if (ffprobeData != null) {
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(ffprobeData.title());
                    }
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                        song.setArtist(ffprobeData.artist());
                    }
                }
            }

            // If all taggers failed, fallback to filename parsing
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                addLog("INFO: All metadata taggers failed for " + file.getName() + ". Falling back to filename parsing.");
                String fileName = file.getName().replaceFirst("(?i)\\.mp3$", "");
                int separatorIndex = fileName.indexOf(" - ");
                if (separatorIndex != -1) {
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                         song.setArtist(fileName.substring(0, separatorIndex).trim());
                    }
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName.substring(separatorIndex + 3).trim());
                    }
                } else {
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName);
                    }
                }
            }

            // Final fallback to ensure fields are not null
            if (song.getTitle() == null || song.getTitle().isBlank()) {
                song.setTitle("Unknown Title");
            }
            if (song.getArtist() == null || song.getArtist().isBlank()) {
                song.setArtist("Unknown Artist");
            }
            if (song.getAlbum() == null || song.getAlbum().isBlank()) {
                song.setAlbum("Unknown Album");
            }
            song.setArtworkBase64(null); // Explicitly null if not found


            int trackLength = getVerifiedTrackLength(file, mp3File);
            song.setDurationSeconds(trackLength);

            if (("Unknown Artist".equals(song.getArtist()) || song.getArtist() == null || song.getArtist().isBlank())
                    && song.getDurationSeconds() == 0) {
                addLog("Rejected song for metadata extraction: " + relativePath + " (Reason: Potentially corrupt - Unknown Artist and 0:00)");
                return null;
            }

            return song;

        } catch (org.jaudiotagger.audio.exceptions.InvalidAudioFrameException e) {
            addLog("Rejected song for metadata extraction: " + relativePath + " (Reason: Invalid audio frame)");
            return null;
        } catch (IOException | ReadOnlyFileException | TagException e) {
            addLog("Rejected song for metadata extraction: " + relativePath + " (Reason: Read/Tag error)");
            return null;
        }
    }

    public void toggleAsService() {
        Settings currentSettings = settingsService.getOrCreateSettings();
        currentSettings.setRunAsService(!currentSettings.getRunAsService());
        settingsService.save(currentSettings);
        addLog("Run-as-service toggled");
    }

    public void selectMusicLibrary() {
        addLog("Music library set to: " + musicLibraryPath);
    }

    public void scanLibrary() {
        this.failedSongs.clear();
        addLog("Scanning music library: " + musicLibraryPath);
        File folder = getMusicFolder();

        if (!folder.exists() || !folder.isDirectory()) {
            addLog("Music folder does not exist: " + folder.getAbsolutePath());
            return;
        }

        performScan(folder, "full library");
    }

    public List<Song> scanLibraryIncremental() {
        this.failedSongs.clear();
        addLog("Starting incremental music library scan...");
        File folder = getMusicFolder();

        if (!folder.exists() || !folder.isDirectory()) {
            addLog("Music folder does not exist: " + folder.getAbsolutePath());
            return new ArrayList<>();
        }

        return performIncrementalScan(folder, "incremental library scan");
    }

    public List<Song> scanImportFolder() {
        addLog("Scanning import folder for new songs...");
        File importFolder = new File(getMusicFolder(), "import");

        if (!importFolder.exists() || !importFolder.isDirectory()) {
            addLog("Import folder does not exist: " + importFolder.getAbsolutePath());
            return new ArrayList<>();
        }

        return performScan(importFolder, "import folder");
    }

    public List<Song> scanSpecificFiles(List<String> targetFileNames, String downloadPath) {
        if (targetFileNames == null || targetFileNames.isEmpty()) {
            addLog("No specific files to scan.");
            return new ArrayList<>();
        }

        addLog("Scanning " + targetFileNames.size() + " specific downloaded files...");
        File importFolder = new File(downloadPath);

        if (!importFolder.exists() || !importFolder.isDirectory()) {
            addLog("Download folder does not exist: " + importFolder.getAbsolutePath());
            return new ArrayList<>();
        }

        List<File> targetFiles = new ArrayList<>();
        for (String fileName : targetFileNames) {
            File file = new File(importFolder, fileName);
            if (file.exists() && file.isFile() && file.getName().toLowerCase().endsWith(".mp3")) {
                targetFiles.add(file);
            } else {
                addLog("Target file not found or not MP3: " + fileName);
            }
        }

        if (targetFiles.isEmpty()) {
            addLog("No valid target MP3 files found to scan.");
            return new ArrayList<>();
        }

        return performTargetedScan(targetFiles, "specific files");
    }

    private List<Song> performScan(File folderToScan, String scanType) {
        List<File> mp3Files = new ArrayList<>();
        collectMp3Files(folderToScan, mp3Files);
        addLog("Found " + mp3Files.size() + " MP3 files in " + scanType + ". Starting parallel metadata reading...");

        ExecutorCompletionService<Song> completion = new ExecutorCompletionService<>(executor);
        mp3Files.forEach(f -> completion.submit(() -> processFile(f)));

        int totalAdded = 0;
        List<Song> processedSongs = new ArrayList<>();

        for (int i = 0; i < mp3Files.size(); i++) {
            try {
                Future<Song> future = completion.take();
                Song result = future.get();
                if (result != null) {
                    totalAdded++;
                    processedSongs.add(result);
                }

                if ((i + 1) % 50 == 0) {
                    addLog("Processed " + (i + 1) + " / " + mp3Files.size() + " files from " + scanType + "...");
                }
            } catch (Exception e) {
                // For unexpected errors during future.get(), we can't pinpoint the file easily from here.
                // processFile should have already logged and added to failedSongs for expected rejections.
                addLog("Error while processing file in parallel from " + scanType + ": " + e.getMessage(), e);
                // Add a generic failed song entry for this unexpected error.
                failedSongs.add(new ScanResult("Unknown File (Parallel Processing Error)", e.getMessage()));
            }
        }

        addLog("Scan of " + scanType + " completed. Total MP3 files processed successfully: " + totalAdded);
        if (!failedSongs.isEmpty()) {
            addLog("The following " + failedSongs.size() + " songs failed to process:");
            failedSongs.forEach(f -> addLog("- " + f.filePath + " (Reason: " + f.rejectedReason + ")"));
        }
        musicSocket.broadcastLibraryUpdateToAllProfiles();
        return processedSongs;
    }

    private List<Song> performTargetedScan(List<File> targetFiles, String scanType) {
        addLog("Processing " + targetFiles.size() + " specific files from " + scanType + "...");

        ExecutorCompletionService<Song> completion = new ExecutorCompletionService<>(executor);
        targetFiles.forEach(f -> completion.submit(() -> processFile(f)));

        int totalProcessed = 0;
        int totalAdded = 0;
        int totalSkipped = 0;
        List<Song> processedSongs = new ArrayList<>();

        for (int i = 0; i < targetFiles.size(); i++) {
            try {
                Future<Song> future = completion.take();
                Song result = future.get();
                totalProcessed++;
                if (result != null) {
                    totalAdded++;
                    processedSongs.add(result);
                } else {
                    totalSkipped++;
                }

                if ((i + 1) % 10 == 0 || (i + 1) == targetFiles.size()) {
                    addLog("Processed " + (i + 1) + " / " + targetFiles.size() + " files from " + scanType + " (Added: " + totalAdded + ", Skipped: " + totalSkipped + ")...");
                }
            } catch (Exception e) {
                addLog("Error while processing file in parallel from " + scanType + ": " + e.getMessage(), e);
                failedSongs.add(new ScanResult("Unknown File (Parallel Processing Error)", e.getMessage()));
            }
        }

        addLog("Targeted scan of " + scanType + " completed. Total processed: " + totalProcessed + ", Added: " + totalAdded + ", Skipped: " + totalSkipped);
        if (!failedSongs.isEmpty()) {
            addLog("The following " + failedSongs.size() + " songs failed to process:");
            failedSongs.forEach(f -> addLog("- " + f.filePath + " (Reason: " + f.rejectedReason + ")"));
        }
        musicSocket.broadcastLibraryUpdateToAllProfiles();
        return processedSongs;
    }

    private List<Song> performIncrementalScan(File folderToScan, String scanType) {
        List<File> mp3Files = new ArrayList<>();
        collectMp3Files(folderToScan, mp3Files);
        addLog("Found " + mp3Files.size() + " MP3 files for " + scanType + ". Starting parallel metadata reading...");

        ExecutorCompletionService<Song> completion = new ExecutorCompletionService<>(executor);
        mp3Files.forEach(f -> completion.submit(() -> processFile(f)));

        int totalProcessed = 0;
        int totalAdded = 0;
        int totalSkipped = 0;
        List<Song> processedSongs = new ArrayList<>();

        for (int i = 0; i < mp3Files.size(); i++) {
            try {
                Future<Song> future = completion.take();
                Song result = future.get();
                totalProcessed++;
                if (result != null) {
                    totalAdded++;
                    processedSongs.add(result);
                } else {
                    totalSkipped++;
                }

                if ((i + 1) % 50 == 0 || (i + 1) == mp3Files.size()) {
                    addLog("Processed " + (i + 1) + " / " + mp3Files.size() + " files from " + scanType + " (Added: " + totalAdded + ", Skipped: " + totalSkipped + ")...");
                }
            } catch (Exception e) {
                addLog("Error while processing file in parallel from " + scanType + ": " + e.getMessage(), e);
                failedSongs.add(new ScanResult("Unknown File (Parallel Processing Error)", e.getMessage()));
            }
        }

        addLog("Incremental scan of " + scanType + " completed. Total processed: " + totalProcessed + ", Added: " + totalAdded + ", Skipped: " + totalSkipped);
        if (!failedSongs.isEmpty()) {
            addLog("The following " + failedSongs.size() + " songs failed to process:");
            failedSongs.forEach(f -> addLog("- " + f.filePath + " (Reason: " + f.rejectedReason + ")"));
        }
        musicSocket.broadcastLibraryUpdateToAllProfiles();
        return processedSongs;
    }

    private void collectMp3Files(File folder, List<File> mp3Files) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectMp3Files(f, mp3Files);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".mp3")) {
                mp3Files.add(f);
            }
        }
    }

    private String safeGet(Tag tag, FieldKey key) {
        if (tag == null) {
            return "";
        }
        try {
            return tag.getFirst(key);
        } catch (NullPointerException e) {
            // This is a workaround for a bug in jaudiotagger where getFirst() can throw an NPE
            // if the frame exists but is empty. Logging this to confirm the catch block is hit.
            return "";
        } catch (Exception e) {
            addLog("Caught unexpected exception in safeGet for key " + key.name() + ": " + e.getMessage());
            return "";
        }
    }

    private int parseInt(String s) {
        if (s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim())) {
            return 0;
        }
        try {
            // Some tags might have "1/12" format, so we take the first part
            String numberPart = s.split("/")[0].trim();
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException e) {
            addLog("Could not parse number: " + s);
            return 0;
        }
    }

    /**
     * Process a single MP3 file. Returns the persisted Song object or null on
     * failure.
     */
    private Song processFile(File file) {
        String relativePath = file.getName(); // Default to file name in case of early error
        boolean isNewSong = false;
        try {
            File baseFolder = getMusicFolder();
            relativePath = baseFolder.toURI().relativize(file.toURI()).getPath();
            
            long size = file.length();
            long lastModified = file.lastModified();

            Song song = songService.findByPathInNewTx(relativePath);
            if (song == null) {
                isNewSong = true;
                song = new Song();
                song.setPath(relativePath);
                song.setDateAdded(java.time.LocalDateTime.now());
            } else {
                // Check if file has changed since last scan (only if both values are set)
                if (song.getSize() != null && song.getLastModified() != null &&
                    song.getSize() == size && song.getLastModified() == lastModified) {
                    // File hasn't changed, but in import context we still want to return it
                    boolean isImportContext = Thread.currentThread().getStackTrace().length > 5;
                    if (isImportContext) {
                        return song; // Return existing song for import playlist inclusion
                    } else {
                        return null; // Skip processing for regular library scan
                    }
                }
            }
            
            // Update size and modification time
            song.setSize(size);
            song.setLastModified(lastModified);

            MP3File mp3File = null;
            Tag tag = null;
            try {
                mp3File = (MP3File) AudioFileIO.read(file);
                tag = mp3File.getTag();
            } catch (org.jaudiotagger.audio.exceptions.CannotReadException e) {
                addLog("[org.jau.tag.id3] WARNING: Could not read MP3 metadata for " + file.getName() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                addLog("[org.jau.tag.id3] WARNING: Runtime error while reading tag for " + file.getName() + ": " + e.getMessage(), e);
            }

            if (tag != null) {
                song.setTitle(safeGet(tag, FieldKey.TITLE));
                song.setArtist(safeGet(tag, FieldKey.ARTIST));
                song.setAlbum(safeGet(tag, FieldKey.ALBUM));
                song.setAlbumArtist(safeGet(tag, FieldKey.ALBUM_ARTIST));
                song.setTrackNumber(parseInt(safeGet(tag, FieldKey.TRACK)));
                song.setDiscNumber(parseInt(safeGet(tag, FieldKey.DISC_NO)));
                song.setReleaseDate(safeGet(tag, FieldKey.YEAR));
                song.setGenre(safeGet(tag, FieldKey.GENRE));
                song.setLyrics(safeGet(tag, FieldKey.LYRICS));
                song.setBpm(parseInt(safeGet(tag, FieldKey.BPM)));

                // Placeholder for BPM verification. If BPM is 0, a re-read could be attempted.
                if (song.getBpm() == 0) {
                    // TODO: Implement BPM re-check if necessary, similar to duration check.
                }

                try {
                    Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null) {
                        byte[] imageData = artwork.getBinaryData();
                        song.setArtworkBase64(java.util.Base64.getEncoder().encodeToString(imageData));
                    } else {
                        song.setArtworkBase64(null);
                    }
                } catch (Exception artworkException) {
                    addLog("[org.jau.tag.id3] WARNING: Failed to extract artwork for " + file.getName() + ": " + artworkException.getMessage());
                    song.setArtworkBase64(null);
                }
            }

            // If jaudiotagger failed or gave empty tags, try ffprobe
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                addLog("[ffmpeg] INFO: jaudiotagger failed to provide title/artist for " + file.getName() + ". Trying ffprobe.");
                FFprobeMetadata ffprobeData = getMetadataWithFFprobe(file);
                if (ffprobeData != null) {
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(ffprobeData.title());
                    }
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                        song.setArtist(ffprobeData.artist());
                    }
                }
            }

            // If all taggers failed, fallback to filename parsing
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                addLog("INFO: All metadata taggers failed for " + file.getName() + ". Falling back to filename parsing.");
                String fileName = file.getName().replaceFirst("(?i)\\.mp3$", "");
                int separatorIndex = fileName.indexOf(" - ");
                if (separatorIndex != -1) {
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                         song.setArtist(fileName.substring(0, separatorIndex).trim());
                    }
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName.substring(separatorIndex + 3).trim());
                    }
                } else {
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName);
                    }
                }
            }

            // Final fallback to ensure fields are not null
            if (song.getTitle() == null || song.getTitle().isBlank()) {
                song.setTitle("Unknown Title");
            }
            if (song.getArtist() == null || song.getArtist().isBlank()) {
                song.setArtist("Unknown Artist");
            }
            if (song.getAlbum() == null || song.getAlbum().isBlank()) {
                song.setAlbum("Unknown Album");
            }
            if (isNewSong) { // Only nullify artwork for new songs where it's not found
                song.setArtworkBase64(null);
            }


            // If not found by path, try to find by title, artist, and duration (after tags are read)
            int trackLength = getVerifiedTrackLength(file, mp3File); // Call once and store
            
            if (isNewSong && song.getTitle() != null && !song.getTitle().isBlank() && song.getArtist() != null && !song.getArtist().isBlank()) {
                // Ensure duration is also available before attempting to find by title/artist/duration
                if (trackLength > 0) {
                    Song existingSongByTitleArtistDuration = songService.findByTitleArtistAndDuration(song.getArtist(), song.getTitle(), trackLength);
                    if (existingSongByTitleArtistDuration != null) {
                        addLog("Found existing song by title/artist/duration: " + song.getTitle() + " by " + song.getArtist() + ". Updating path from " + existingSongByTitleArtistDuration.getPath() + " to " + relativePath);
                        song = existingSongByTitleArtistDuration; // Use the existing song object
                        song.setPath(relativePath); // Update its path
                        isNewSong = false; // It's not a new song, it's an update
                    }
                }
            }

            song.setDurationSeconds(trackLength);

            if (("Unknown Artist".equals(song.getArtist()) || song.getArtist() == null || song.getArtist().isBlank())
                    && song.getDurationSeconds() == 0) {
                addLog("Rejected song: " + relativePath + " (Reason: Potentially corrupt - Unknown Artist and 0:00)");
                failedSongs.add(new ScanResult(relativePath, "Potentially corrupt - Unknown Artist and 0:00"));
                return null;
            }

            Song persistedSong = songService.persistSongInNewTx(song);
            // Only return the song if it was newly created in this run
            return isNewSong ? persistedSong : null;

        } catch (org.jaudiotagger.audio.exceptions.InvalidAudioFrameException e) {
            addLog("Rejected song: " + relativePath + " (Reason: Invalid audio frame)");
            failedSongs.add(new ScanResult(relativePath, "Invalid audio frame"));
            return null;
        } catch (IOException | ReadOnlyFileException | TagException e) {
            addLog("Rejected song: " + relativePath + " (Reason: Read/Tag error)");
            failedSongs.add(new ScanResult(relativePath, "Read/Tag error: " + e.getMessage()));
            return null;
        }
    }

    private int getDurationWithFFprobe(File file) {
        // ffprobe is generally more efficient and provides cleaner output for metadata.
        // Command: ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "input.mp3"
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.getAbsolutePath());
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.length() > 0) {
                try {
                    double durationSeconds = Double.parseDouble(output.toString().trim());
                    return (int) Math.round(durationSeconds);
                } catch (NumberFormatException e) {
                    addLog("[ffmpeg] WARNING: Failed to parse ffprobe duration for " + file.getName() + ": " + output.toString().trim());
                    return getDurationWithFFmpegLegacy(file); // Fallback to legacy ffmpeg
                }
            } else {
                 addLog("[ffmpeg] INFO: ffprobe failed for " + file.getName() + " (exit code: " + exitCode + "). Falling back to ffmpeg -i.");
                 return getDurationWithFFmpegLegacy(file); // Fallback to legacy ffmpeg
            }
        } catch (IOException | InterruptedException e) {
             addLog("[ffmpeg] ERROR: Exception with ffprobe for " + file.getName() + ": " + e.getMessage() + ". Falling back to ffmpeg -i.");
             return getDurationWithFFmpegLegacy(file); // Fallback to legacy ffmpeg
        }
    }
    
    private int getDurationWithFFmpegLegacy(File file) {
        // Fallback to parsing ffmpeg's more verbose output.
        // Command: ffmpeg -i "input.mp3"
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-i", file.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Use a pattern to find the duration line efficiently.
            Pattern pattern = Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d{2})\\.\\d+");
            String durationLine = null;
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Duration:")) {
                        durationLine = line;
                        break; 
                    }
                }
            }
            
            // Wait for the process to avoid resource leaks, with a timeout.
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
            }

            if (durationLine != null) {
                Matcher matcher = pattern.matcher(durationLine);
                if (matcher.find()) {
                    int hours = Integer.parseInt(matcher.group(1));
                    int minutes = Integer.parseInt(matcher.group(2));
                    int seconds = Integer.parseInt(matcher.group(3));
                    return hours * 3600 + minutes * 60 + seconds;
                }
            }
            
            addLog("[ffmpeg] WARNING: Could not find Duration in ffmpeg output for " + file.getName());
            return -1;

        } catch (IOException | InterruptedException e) {
            addLog("[ffmpeg] ERROR: Exception with ffmpeg -i for " + file.getName() + ": " + e.getMessage());
            Thread.currentThread().interrupt(); // Preserve interrupted status
            return -1;
        }
    }

    private int getVerifiedTrackLength(File file, MP3File initialMp3File) {
        int duration = 0;
        try {
            if (initialMp3File != null && initialMp3File.getAudioHeader() != null) {
                duration = initialMp3File.getAudioHeader().getTrackLength();
            } else {
                addLog("[org.jau.tag.id3] WARNING: Could not read initial duration for " + file.getName() + ".");
            }
        } catch (Exception e) {
            addLog("[org.jau.tag.id3] WARNING: Error reading initial duration for " + file.getName() + ": " + e.getMessage(), e);
        }

        // Suspicious duration check (e.g., < 60 seconds, or > 7 minutes)
        final int MIN_REASONABLE_DURATION_SECONDS = 60;
        final int MAX_REASONABLE_DURATION_SECONDS = 420; // 7 minutes
        if (duration < MIN_REASONABLE_DURATION_SECONDS || duration > MAX_REASONABLE_DURATION_SECONDS) {
            addLog("[org.jau.tag.id3] INFO: Suspicious duration (" + duration + "s) for " + file.getName() + ". Re-checking with jaudiotagger and ffmpeg.");
            
            // Second attempt with jaudiotagger
            try {
                MP3File mp3FileSecondRead = (MP3File) AudioFileIO.read(file);
                int secondDuration = 0;
                if (mp3FileSecondRead != null && mp3FileSecondRead.getAudioHeader() != null) {
                    secondDuration = mp3FileSecondRead.getAudioHeader().getTrackLength();
                }

                if (duration != secondDuration) {
                    addLog("[org.jau.tag.id3] INFO: Duration changed on second read. Old: " + duration + "s, New: " + secondDuration + "s for " + file.getName());
                    duration = secondDuration; // Update duration with the new value
                } else {
                    addLog("[org.jau.tag.id3] INFO: Duration (" + duration + "s) remained the same on second read for " + file.getName());
                }
            } catch (Exception e) {
                addLog("[org.jau.tag.id3] WARNING: Error during second duration read for " + file.getName() + ": " + e.getMessage(), e);
            }

            // If duration is still suspicious, try ffmpeg as a final fallback
            if (duration < MIN_REASONABLE_DURATION_SECONDS || duration > MAX_REASONABLE_DURATION_SECONDS) {
                addLog("[ffmpeg] INFO: jaudiotagger duration is still suspicious. Attempting fallback with ffmpeg for " + file.getName());
                int ffmpegDuration = getDurationWithFFprobe(file);
                if (ffmpegDuration != -1) {
                    addLog("[ffmpeg] SUCCESS: ffmpeg successfully extracted duration: " + ffmpegDuration + "s for " + file.getName());
                    return ffmpegDuration;
                } else {
                    addLog("[ffmpeg] FAILURE: All methods failed to get a valid duration for " + file.getName() + ". Returning last known value: " + duration + "s.");
                }
            }
        }
        return duration;
    } 

    // -------------------------------
    // Parallel reloadAllSongsMetadata
    // -------------------------------
    public void reloadAllSongsMetadata() {
        addLog("[org.jau.tag.id3] Reloading metadata for all songs...");
        List<Song> allSongs = songService.findAll();
        addLog("[org.jau.tag.id3] Found " + (allSongs == null ? 0 : allSongs.size()) + " songs to reload.");

        if (allSongs == null || allSongs.isEmpty()) {
            addLog("[org.jau.tag.id3] No songs to reload.");
            return;
        }

        ExecutorCompletionService<List<String>> completion = new ExecutorCompletionService<>(executor);
        allSongs.forEach(song -> completion.submit(() -> reloadMetadataForSong(song)));

        int updatedCount = 0;
        List<String> batchLogs = new ArrayList<>();
        for (int i = 0; i < allSongs.size(); i++) {
            try {
                Future<List<String>> future = completion.take();
                List<String> logs = future.get();
                if (logs != null && !logs.isEmpty()) {
                    batchLogs.addAll(logs);
                    // A non-empty log list from this method implies success.
                    updatedCount++;
                }
            } catch (InterruptedException | ExecutionException e) {
                batchLogs.add("[org.jau.tag.id3] ERROR: reload task failed: " + e.getMessage());
            }
        }

        // Add all collected logs in a single batch
        addLogs(batchLogs);

        addLog(String.format("[org.jau.tag.id3] Metadata reload completed. %d songs updated.", updatedCount));
        musicSocket.broadcastLibraryUpdateToAllProfiles();
    }

    private List<String> reloadMetadataForSong(Song song) {
        List<String> localLogs = new ArrayList<>();
        try {
            File songFile = new File(getMusicFolder(), song.getPath());
            if (!(songFile.exists() && songFile.isFile())) {
                localLogs.add("[org.jau.tag.id3] Skipping metadata reload for missing file: " + song.getPath());
                return localLogs;
            }

            localLogs.add("[org.jau.tag.id3] Reading MP3 file: " + songFile.getName());
            MP3File mp3File = (MP3File) AudioFileIO.read(songFile);
            Tag tag = mp3File.getTag();

            // Reset fields before reloading to ensure fresh data
            song.setTitle(null);
            song.setArtist(null);
            song.setAlbum(null);
            
            if (tag != null) {
                song.setTitle(safeGet(tag, FieldKey.TITLE));
                song.setArtist(safeGet(tag, FieldKey.ARTIST));
                song.setAlbum(safeGet(tag, FieldKey.ALBUM));
                song.setAlbumArtist(safeGet(tag, FieldKey.ALBUM_ARTIST));
                song.setTrackNumber(parseInt(safeGet(tag, FieldKey.TRACK)));
                song.setDiscNumber(parseInt(safeGet(tag, FieldKey.DISC_NO)));
                song.setReleaseDate(safeGet(tag, FieldKey.YEAR));
                song.setGenre(safeGet(tag, FieldKey.GENRE));
                song.setLyrics(safeGet(tag, FieldKey.LYRICS));
                song.setBpm(parseInt(safeGet(tag, FieldKey.BPM)));

                try {
                    Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null) {
                        byte[] data = artwork.getBinaryData();
                        song.setArtworkBase64(java.util.Base64.getEncoder().encodeToString(data));
                    } else {
                        song.setArtworkBase64(null);
                    }
                } catch (Exception artEx) {
                    localLogs.add("[org.jau.tag.id3] WARNING: Failed to extract artwork for " + songFile.getName() + ": " + artEx.getMessage());
                    song.setArtworkBase64(null);
                }
            }
            
            // If jaudiotagger failed or gave empty tags, try ffprobe
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                localLogs.add("[ffmpeg] INFO: jaudiotagger failed to provide title/artist for " + songFile.getName() + ". Trying ffprobe.");
                FFprobeMetadata ffprobeData = getMetadataWithFFprobe(songFile);
                if (ffprobeData != null) {
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(ffprobeData.title());
                    }
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                        song.setArtist(ffprobeData.artist());
                    }
                }
            }

            // If all taggers failed, fallback to filename parsing
            if (song.getTitle() == null || song.getTitle().isBlank() || song.getArtist() == null || song.getArtist().isBlank()) {
                localLogs.add("INFO: All metadata taggers failed for " + songFile.getName() + ". Falling back to filename parsing.");
                String fileName = songFile.getName().replaceFirst("(?i)\\.mp3$", "");
                int separatorIndex = fileName.indexOf(" - ");
                if (separatorIndex != -1) {
                    if (song.getArtist() == null || song.getArtist().isBlank()) {
                         song.setArtist(fileName.substring(0, separatorIndex).trim());
                    }
                    if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName.substring(separatorIndex + 3).trim());
                    }
                } else {
                     if (song.getTitle() == null || song.getTitle().isBlank()) {
                        song.setTitle(fileName);
                    }
                }
            }

            // Final fallback to ensure fields are not null
            if (song.getTitle() == null || song.getTitle().isBlank()) {
                song.setTitle("Unknown Title");
            }
            if (song.getArtist() == null || song.getArtist().isBlank()) {
                song.setArtist("Unknown Artist");
            }
            if (song.getAlbum() == null || song.getAlbum().isBlank()) {
                song.setAlbum("Unknown Album");
            }

            int duration = getVerifiedTrackLength(songFile, mp3File);
            localLogs.add(String.format("[org.jau.tag.id3] Verified Duration = %d seconds for %s", duration, song.getPath()));
            song.setDurationSeconds(duration);

            if (("Unknown Artist".equals(song.getArtist()) || song.getArtist() == null || song.getArtist().isBlank())
                    && song.getDurationSeconds() == 0) {
                localLogs.add("[org.jau.tag.id3] WARNING: Skipping potentially corrupt song (Unknown Artist + 0:00): " + song.getPath());
                return localLogs;
            }

            // Persist changes per-song
            songService.persistSongInNewTx(song);
            localLogs.add("[org.jau.tag.id3] Successfully reloaded metadata for: " + song.getPath());
            return localLogs;
        } catch (org.jaudiotagger.audio.exceptions.InvalidAudioFrameException e) {
            localLogs.add("[org.jau.tag.id3] WARNING: Skipping " + song.getPath() + " — invalid audio frame: " + e.getMessage());
            return localLogs;
        } catch (IOException | CannotReadException | ReadOnlyFileException | TagException e) {
            localLogs.add("[org.jau.tag.id3] ERROR: Failed to reload metadata for " + song.getPath() + ": " + e.getMessage());
            return localLogs;
        }
    }

    // -------------------------------
    // Parallel deleteDuplicateSongs
    // -------------------------------
    public void deleteDuplicateSongs() {
        addLog("Deleting duplicate songs...");

        // Step 1: Collect all MP3 files from both main and import folders
        List<FileDetails> allFileDetails = new ArrayList<>();
        List<File> unidentifiableFilesToDelete = new ArrayList<>();

        // Main music folder
        File mainMusicFolder = getMusicFolder();
        if (mainMusicFolder.exists() && mainMusicFolder.isDirectory()) {
            List<File> mainFiles = new ArrayList<>();
            collectMp3Files(mainMusicFolder, mainFiles);
            for (File file : mainFiles) {
                Song metadata = extractMetadataFromFile(file);
                if (metadata != null) {
                    allFileDetails.add(new FileDetails(file, "main", metadata));
                } else {
                    unidentifiableFilesToDelete.add(file);
                }
            }
        } else {
            addLog("Main music folder does not exist: " + mainMusicFolder.getAbsolutePath());
        }

        // Import folder
        File importFolder = new File(mainMusicFolder, "import");
        if (importFolder.exists() && importFolder.isDirectory()) {
            List<File> importFiles = new ArrayList<>();
            collectMp3Files(importFolder, importFiles);
            for (File file : importFiles) {
                Song metadata = extractMetadataFromFile(file);
                if (metadata != null) {
                    allFileDetails.add(new FileDetails(file, "import", metadata));
                } else {
                    unidentifiableFilesToDelete.add(file);
                }
            }
        } else {
            addLog("Import folder does not exist: " + importFolder.getAbsolutePath());
        }

        if (allFileDetails.isEmpty() && unidentifiableFilesToDelete.isEmpty()) {
            addLog("No songs found in main or import folders to check for duplicates or unidentifiable files.");
            return;
        }

        // Step 2: Identify duplicates based on metadata
        java.util.Map<String, List<FileDetails>> potentialDuplicates = new java.util.HashMap<>();
        for (FileDetails fd : allFileDetails) {
            Song song = fd.songMetadata;
            String songIdentifier = (song.getTitle() == null ? "" : song.getTitle())
                    + "-" + (song.getArtist() == null ? "" : song.getArtist())
                    + "-" + (song.getAlbum() == null ? "" : song.getAlbum())
                    + "-" + song.getDurationSeconds();
            potentialDuplicates.computeIfAbsent(songIdentifier, k -> new ArrayList<>()).add(fd);
        }

        List<FileDetails> filesToDelete = new ArrayList<>();
        for (java.util.Map.Entry<String, List<FileDetails>> entry : potentialDuplicates.entrySet()) {
            List<FileDetails> duplicates = entry.getValue();
            if (duplicates.size() > 1) {
                // Sort duplicates to prioritize keeping main library files, then by modification date
                duplicates.sort((fd1, fd2) -> {
                    // Prioritize keeping "main" over "import"
                    if (!fd1.source.equals(fd2.source)) {
                        return fd1.source.equals("main") ? -1 : 1;
                    }
                    // If same source, prioritize older files (less likely to be the "downloaded/imported one" if it's a re-download)
                    return Long.compare(fd1.file.lastModified(), fd2.file.lastModified());
                });

                // Keep the first one (highest priority), mark the rest for deletion
                for (int i = 1; i < duplicates.size(); i++) {
                    filesToDelete.add(duplicates.get(i));
                }
            }
        }

        addLog("Found " + filesToDelete.size() + " duplicate files to delete. Deleting in parallel...");

        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
        filesToDelete.forEach(fd -> completion.submit(() -> {
            try {
                // Delete physical file
                if (fd.file.delete()) {
                    // Delete corresponding database entry with playlist preservation
                    String relativePath = getMusicFolder().toURI().relativize(fd.file.toURI()).getPath();
                    Song songInDb = songService.findByPathInNewTx(relativePath);
                    if (songInDb != null) {
                        songService.deleteWithPlaylistPreservation(songInDb);
                        return "Deleted duplicate file and preserved playlists: " + fd.file.getAbsolutePath();
                    } else {
                        return "Deleted duplicate file (no DB entry found): " + fd.file.getAbsolutePath();
                    }
                } else {
                    return "Failed to delete physical file: " + fd.file.getAbsolutePath();
                }
            } catch (Exception e) {
                return "Error deleting duplicate file " + fd.file.getAbsolutePath() + ": " + e.getMessage();
            }
        }));
        
        // Also add unidentifiable files to the deletion process
        unidentifiableFilesToDelete.forEach(file -> completion.submit(() -> {
            try {
                if (file.delete()) {
                    // For unidentifiable files, we don't have metadata to find a DB entry easily.
                    // Log it as deleted.
                    return "Deleted unidentifiable file (metadata extraction failed): " + file.getAbsolutePath();
                } else {
                    return "Failed to delete unidentifiable file: " + file.getAbsolutePath();
                }
            } catch (Exception e) {
                return "Error deleting unidentifiable file " + file.getAbsolutePath() + ": " + e.getMessage();
            }
        }));

        List<String> batchLogs = new ArrayList<>();
        int deletedCount = 0;
        int unidentifiableDeletedCount = 0;
        
        int totalFilesToProcess = filesToDelete.size() + unidentifiableFilesToDelete.size();
        for (int i = 0; i < totalFilesToProcess; i++) {
            try {
                Future<String> future = completion.take();
                String logMessage = future.get();
                batchLogs.add(logMessage);
                if (logMessage.startsWith("Deleted duplicate file")) {
                    deletedCount++;
                } else if (logMessage.startsWith("Deleted unidentifiable file")) {
                    unidentifiableDeletedCount++;
                }
            } catch (Exception e) {
                batchLogs.add("Error in deletion task: " + e.getMessage());
            }
        }

        addLogs(batchLogs);
        addLog("Duplicate file deletion completed. " + deletedCount + " files deleted.");
        addLog("Unidentifiable file deletion completed. " + unidentifiableDeletedCount + " files deleted.");
        musicSocket.broadcastLibraryUpdateToAllProfiles();
    }

    public synchronized void clearLogs() {
        Settings settings = settingsService.getOrCreateSettings();
        settings.getLogs().clear();
        addLog("Logs cleared.");
        settingsService.save(settings);
    }

    private String getDefaultMusicFolder() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        File musicFolder;

        if (os.contains("win")) {
            String winProfile = System.getenv("USERPROFILE");
            if (winProfile != null && !winProfile.isBlank()) {
                userHome = winProfile;
            }
        }

        musicFolder = new File(userHome, "Music");

        if (!musicFolder.exists()) {
            boolean created = musicFolder.mkdirs();
            if (created) {
                addLog("Created default Music folder at: " + musicFolder.getAbsolutePath());
            } else {
                addLog("Failed to create default Music folder, using home directory instead.");
                return userHome;
            }
        }

        return musicFolder.getAbsolutePath();
    }

    public File getMusicFolder() {
        Settings currentSettings = settingsService.getOrCreateSettings();
        return new File(currentSettings.getLibraryPath());
    }

    public void resetMusicLibrary() {
        musicLibraryPath = getDefaultMusicFolder();
        addLog("Music library reset to default: " + musicLibraryPath);
    }

    public synchronized List<String> getLogs() {
        return settingsService.getOrCreateSettings().getLogs().stream()
                .map(SettingsLog::getMessage)
                .collect(Collectors.toList());
    }

    public synchronized void addLogs(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Settings settings = settingsService.getOrCreateSettings();
        for (String message : messages) {
            SettingsLog log = new SettingsLog();
            log.setMessage(message);
            settings.getLogs().add(log);
            logSocket.broadcast(log.getMessage());
        }
        settingsService.save(settings);
    }

    public synchronized void addLog(String message, Throwable t) {
        Settings settings = settingsService.getOrCreateSettings();
        SettingsLog log = new SettingsLog();
        if (t != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            log.setMessage(message + "\n" + sw.toString());
        } else {
            log.setMessage(message);
        }
        settings.getLogs().add(log);
        settingsService.save(settings);
        logSocket.broadcast(log.getMessage());
    }

    public synchronized void addLog(String message) {
        addLog(message, null);
    }

    public String getMusicLibraryPath() {
        return musicLibraryPath;
    }

    public void setMusicLibraryPath(String path) {
        Settings currentSettings = settingsService.getOrCreateSettings();
        settingsService.setLibraryPath(currentSettings, path);
        this.musicLibraryPath = currentSettings.getLibraryPath(); // Update from persisted value
        addLog("Music library path updated to: " + path);
    }

    public Settings getOrCreateSettings() {
        return settingsService.getOrCreateSettings();
    }

    public ImportService getImportService() {
        return importService;
    }

    public List<ScanResult> getFailedSongs() {
        return failedSongs;
    }

}