package Controllers;

import API.WS.LogSocket;
import API.WS.MusicSocket;
import jakarta.annotation.PostConstruct;
import Models.Settings;
import Models.SettingsLog;
import Models.Song;
import Services.PlaybackHistoryService;
import Services.SettingsService;
import Services.SongService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.datatype.Artwork;

@ApplicationScoped
public class SettingsController implements Serializable {

    @Inject
    private SongService songService;

    @Inject
    private SettingsService settingsService;

    @Inject
    private MusicSocket musicSocket;

    @Inject
    private PlaybackHistoryService playbackHistoryService;

    @Inject
    private LogSocket logSocket;

    private String musicLibraryPath;

    private static final int THREADS = Math.max(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    public void toggleAsService() {
        Settings currentSettings = settingsService.getOrCreateSettings();
        currentSettings.setRunAsService(!currentSettings.getRunAsService());
        settingsService.save(currentSettings);
        addLog("Run-as-service toggled");
    }

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

    public void selectMusicLibrary() {
        addLog("Music library set to: " + musicLibraryPath);
    }

    public void scanLibrary() {
        addLog("Scanning music library: " + musicLibraryPath);
        File folder = getMusicFolder();

        if (!folder.exists() || !folder.isDirectory()) {
            addLog("Music folder does not exist: " + folder.getAbsolutePath());
            return;
        }

        List<File> mp3Files = new ArrayList<>();
        collectMp3Files(folder, mp3Files);
        addLog("Found " + mp3Files.size() + " MP3 files. Starting parallel metadata reading...");

        ExecutorCompletionService<Integer> completion = new ExecutorCompletionService<>(executor);
        mp3Files.forEach(f -> completion.submit(() -> processFile(f)));

        int totalAdded = 0;
        for (int i = 0; i < mp3Files.size(); i++) {
            try {
                totalAdded += completion.take().get();
                if ((i + 1) % 50 == 0) {
                    addLog("Processed " + (i + 1) + " / " + mp3Files.size() + " files...");
                }
            } catch (Exception e) {
                addLog("Error while processing file in parallel: " + e.getMessage(), e);
            }
        }

        addLog("Scan completed. Total MP3 files added: " + totalAdded);
        musicSocket.broadcastAll();
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

    /**
     * Process a single MP3 file. Returns 1 if a song was added/updated, 0
     * otherwise.
     */
    private int processFile(File file) {
        try {
            File baseFolder = getMusicFolder();
            String relativePath = baseFolder.toURI().relativize(file.toURI()).getPath();

            Song song = songService.findByPathInNewTx(relativePath);
            if (song == null) {
                song = new Song();
                song.setPath(relativePath);
                song.setDateAdded(java.time.LocalDateTime.now());
            }

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
                song.setTitle(tag.getFirst(FieldKey.TITLE));
                song.setArtist(tag.getFirst(FieldKey.ARTIST));
                song.setAlbum(tag.getFirst(FieldKey.ALBUM));

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
            } else {
                String fileName = file.getName().replaceFirst("(?i)\\.mp3$", "");
                song.setTitle(fileName);
                song.setArtist("Unknown Artist");
                song.setAlbum("Unknown Album");
                song.setArtworkBase64(null);
            }

            try {
                if (mp3File != null && mp3File.getAudioHeader() != null) {
                    int trackLength = mp3File.getAudioHeader().getTrackLength();
                    song.setDurationSeconds(trackLength);
                 } else {
                    song.setDurationSeconds(0);
                    addLog("[org.jau.tag.id3] WARNING: Could not read duration for " + file.getName() + ".");
                }
            } catch (Exception e) {
                addLog("[org.jau.tag.id3] WARNING: Error reading duration for " + file.getName() + ": " + e.getMessage());
                song.setDurationSeconds(0);
            }

            if (("Unknown Artist".equals(song.getArtist()) || song.getArtist() == null || song.getArtist().isBlank())
                    && song.getDurationSeconds() == 0) {
                addLog("[org.jau.tag.id3] WARNING: Skipping potentially corrupt song (Unknown Artist and 0:00): " + file.getName());
                return 0;
            }

            // Persist using REQUIRES_NEW transaction per-file
            songService.persistSongInNewTx(song);
              return 1;
        } catch (org.jaudiotagger.audio.exceptions.InvalidAudioFrameException e) {
            addLog("[org.jau.tag.id3] WARNING: Skipping song " + file.getName() + " due to invalid audio frame: " + e.getMessage());
            return 0;
        } catch (Exception e) {
            addLog("[org.jau.tag.id3] ERROR: Failed to add/update song " + file.getName() + ": " + e.getMessage(), e);
            return 0;
        }
    }

    // -------------------------------
    // Parallel reloadAllSongsMetadata
    // -------------------------------
    public void reloadAllSongsMetadata() {
        addLog("[org.jau.tag.id3] Reloading metadata for all songs...");
        // Grab list of songs up-front
        List<Song> allSongs = songService.findAll();
        addLog("[org.jau.tag.id3] Found " + (allSongs == null ? 0 : allSongs.size()) + " songs to reload.");

        if (allSongs == null || allSongs.isEmpty()) {
            addLog("[org.jau.tag.id3] No songs to reload.");
            return;
        }

        List<Future<Boolean>> futures = new ArrayList<>(allSongs.size());
        for (Song song : allSongs) {
            // Submit a task per-song
            futures.add(executor.submit(() -> reloadMetadataForSong(song)));
        }

        int updatedCount = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get()) {
                    updatedCount++;
                }
            } catch (Exception e) {
                addLog("[org.jau.tag.id3] ERROR: reload task failed: " + e.getMessage(), e);
            }
        }

        addLog(String.format("[org.jau.tag.id3] Metadata reload completed. %d songs updated.", updatedCount));
        musicSocket.broadcastAll();
    }

    private Boolean reloadMetadataForSong(Song song) {
        try {
            File songFile = new File(getMusicFolder(), song.getPath());
            if (!(songFile.exists() && songFile.isFile())) {
                addLog("[org.jau.tag.id3] Skipping metadata reload for missing file: " + song.getPath());
                return false;
            }

            addLog("[org.jau.tag.id3] Reading MP3 file: " + songFile.getName());
            MP3File mp3File = (MP3File) AudioFileIO.read(songFile);
            Tag tag = mp3File.getTag();

            if (tag != null) {
                String title = tag.getFirst(FieldKey.TITLE);
                String artist = tag.getFirst(FieldKey.ARTIST);
                String album = tag.getFirst(FieldKey.ALBUM);

                addLog(String.format("[org.jau.tag.id3] Frame TIT2 (Title): %s", title != null ? title : "(none)"));
                addLog(String.format("[org.jau.tag.id3] Frame TPE1 (Artist): %s", artist != null ? artist : "(none)"));
                addLog(String.format("[org.jau.tag.id3] Frame TALB (Album): %s", album != null ? album : "(none)"));

                song.setTitle(title);
                song.setArtist(artist);
                song.setAlbum(album);

                try {
                    Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null) {
                        byte[] data = artwork.getBinaryData();
                        String mime = artwork.getMimeType();
                        addLog(String.format("[org.jau.tag.id3] Found APIC (Artwork): %d bytes (%s) for %s", data.length, mime, song.getPath()));
                        song.setArtworkBase64(java.util.Base64.getEncoder().encodeToString(data));
                    } else {
                        addLog("[org.jau.tag.id3] No APIC frame found (no artwork) for " + song.getPath());
                        song.setArtworkBase64(null);
                    }
                } catch (Exception artEx) {
                    addLog("[org.jau.tag.id3] WARNING: Failed to extract artwork for " + songFile.getName() + ": " + artEx.getMessage());
                    song.setArtworkBase64(null);
                }
            } else {
                addLog("[org.jau.tag.id3] No tag found — using filename as title for " + songFile.getName());
                String baseName = songFile.getName().replaceFirst("(?i)\\.mp3$", "");
                song.setTitle(baseName);
                song.setArtist("Unknown Artist");
                song.setAlbum(null);
                song.setArtworkBase64(null);
            }

            int duration = mp3File.getAudioHeader().getTrackLength();
            addLog(String.format("[org.jau.tag.id3] AudioHeader: Duration = %d seconds for %s", duration, song.getPath()));
            song.setDurationSeconds(duration);

            if ((song.getArtist() == null || song.getArtist().isBlank() || "Unknown Artist".equals(song.getArtist()))
                    && song.getDurationSeconds() == 0) {
                addLog("[org.jau.tag.id3] WARNING: Skipping potentially corrupt song (Unknown Artist + 0:00): " + song.getPath());
                return false;
            }

            // Persist changes per-song
            songService.persistSongInNewTx(song);
            addLog("[org.jau.tag.id3] Successfully reloaded metadata for: " + song.getPath());
            return true;
        } catch (org.jaudiotagger.audio.exceptions.InvalidAudioFrameException e) {
            addLog("[org.jau.tag.id3] WARNING: Skipping " + song.getPath() + " — invalid audio frame: " + e.getMessage());
            return false;
        } catch (Exception e) {
            addLog("[org.jau.tag.id3] ERROR: Failed to reload metadata for " + song.getPath() + ": " + e.getMessage(), e);
            return false;
        }
    }

    // -------------------------------
    // Parallel deleteDuplicateSongs
    // -------------------------------
    public void deleteDuplicateSongs() {
        addLog("Deleting duplicate songs...");
        List<Song> allSongs = songService.findAll();
        if (allSongs == null || allSongs.isEmpty()) {
            addLog("No songs to check for duplicates.");
            return;
        }

        // Identify duplicates (single-threaded pass)
        List<Song> songsToDelete = new ArrayList<>();
        java.util.Set<String> uniqueSongs = new java.util.HashSet<>();

        for (Song song : allSongs) {
            String songIdentifier = (song.getTitle() == null ? "" : song.getTitle())
                    + "-" + (song.getArtist() == null ? "" : song.getArtist())
                    + "-" + (song.getAlbum() == null ? "" : song.getAlbum())
                    + "-" + song.getDurationSeconds();
            if (uniqueSongs.contains(songIdentifier)) {
                songsToDelete.add(song);
            } else {
                uniqueSongs.add(songIdentifier);
            }
        }

        addLog("Found " + songsToDelete.size() + " duplicates to delete. Deleting in parallel...");

        List<Future<Boolean>> futures = new ArrayList<>(songsToDelete.size());
        for (Song s : songsToDelete) {
            futures.add(executor.submit(() -> {
                try {
                    songService.delete(s);
                    addLog("Deleted duplicate song: " + s.getTitle() + " by " + s.getArtist());
                    return true;
                } catch (Exception e) {
                    addLog("Failed to delete duplicate song " + s.getTitle() + ": " + e.getMessage(), e);
                    return false;
                }
            }));
        }

        int deleted = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get()) {
                    deleted++;
                }
            } catch (Exception e) {
                addLog("Error in duplicate deletion task: " + e.getMessage(), e);
            }
        }

        addLog("Duplicate deletion completed. " + deleted + " songs deleted.");
        musicSocket.broadcastAll();
    }

    public void clearLogs() {
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

    public List<String> getLogs() {
        return settingsService.getOrCreateSettings().getLogs().stream()
                .map(SettingsLog::getMessage)
                .collect(Collectors.toList());
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

        // After setting a new path, clear old songs and scan the new library
        addLog("Clearing existing songs and scanning new library path: " + path);
        playbackHistoryService.clearHistory(); // Clear history first
        songService.clearAllSongs();
        scanLibrary();
    }

    public Settings getOrCreateSettings() {
        return settingsService.getOrCreateSettings();
    }

    public void toggleTorrentBrowsing(boolean enabled) {
        Settings currentSettings = settingsService.getOrCreateSettings();
        settingsService.toggleTorrentBrowsing(currentSettings, enabled);
        addLog("Torrent browsing feature toggled to: " + enabled);
    }

    public void toggleTorrentPeerDiscovery(boolean enabled) {
        Settings currentSettings = settingsService.getOrCreateSettings();
        settingsService.toggleTorrentPeerDiscovery(currentSettings, enabled);
        addLog("Torrent peer discovery/sharing toggled to: " + enabled);
    }

    public void toggleTorrentDiscovery(boolean enabled) {
        Settings currentSettings = settingsService.getOrCreateSettings();
        settingsService.toggleTorrentDiscovery(currentSettings, enabled);
        addLog("Torrent/Peer discovery toggled to: " + enabled);
    }

}
