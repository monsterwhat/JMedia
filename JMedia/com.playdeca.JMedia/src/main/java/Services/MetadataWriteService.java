package Services;

import Models.Song;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Service for writing metadata from Song objects back to audio files.
 * Supports MP3, FLAC, M4A, OGG, and WAV formats using JAudioTagger.
 */
@ApplicationScoped
public class MetadataWriteService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataWriteService.class);
    
    // Marker for custom app data stored in comments
    private static final String APP_MARKER = "JMedia";
    private static final String APP_VERSION = "1.1.0";

    @Inject
    Executor executor;
    
    @Inject
    LoggingService loggingService;

    /**
     * Writes all metadata from a Song object to its audio file.
     * Creates a backup before modifying the file.
     *
     * @param song The Song object with metadata to write
     * @param absolutePath Absolute path to the audio file
     * @return CompletableFuture with success status
     */
    public CompletableFuture<Boolean> writeMetadataToFile(Song song, String absolutePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Starting metadata write for: {}", absolutePath);
                
                File file = new File(absolutePath);
                if (!file.exists()) {
                    LOGGER.error("File not found: {}", absolutePath);
                    return false;
                }
                
                if (!isSupportedFormat(file)) {
                    LOGGER.warn("Unsupported file format: {}", absolutePath);
                    return false;
                }
                
                // Create backup first
                File backup = createBackup(absolutePath);
                if (backup == null) {
                    LOGGER.error("Failed to create backup for: {}", absolutePath);
                    return false;
                }
                
                // Write metadata
                boolean success = writeMetadata(song, absolutePath);
                
                if (!success) {
                    LOGGER.warn("Metadata write failed, restoring backup for: {}", absolutePath);
                    restoreFromBackup(absolutePath, backup);
                    return false;
                }
                
                // Clean up backup on success
                if (backup.exists()) {
                    backup.delete();
                }
                
                LOGGER.info("Successfully wrote metadata to: {}", absolutePath);
                return true;
                
            } catch (Exception e) {
                LOGGER.error("Failed to write metadata to {}: {}", absolutePath, e.getMessage(), e);
                return false;
            }
        }, executor);
    }

    /**
     * Writes all metadata fields to the audio file.
     */
    private boolean writeMetadata(Song song, String filePath) {
        try {
            File file = new File(filePath);
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateDefault();
            
            // Standard fields
            writeIfPresent(tag, FieldKey.TITLE, song.getTitle());
            writeIfPresent(tag, FieldKey.ARTIST, song.getArtist());
            writeIfPresent(tag, FieldKey.ALBUM, song.getAlbum());
            writeIfPresent(tag, FieldKey.ALBUM_ARTIST, song.getAlbumArtist());
            writeIfPresent(tag, FieldKey.GENRE, song.getGenre());
            writeIfPresent(tag, FieldKey.LYRICS, song.getLyrics());
            
            // Track and disc numbers
            try {
                if (song.getTrackNumber() > 0) {
                    tag.setField(FieldKey.TRACK, String.valueOf(song.getTrackNumber()));
                }
                if (song.getDiscNumber() > 0) {
                    tag.setField(FieldKey.DISC_NO, String.valueOf(song.getDiscNumber()));
                }
                if (song.getBpm() > 0) {
                    tag.setField(FieldKey.BPM, String.valueOf(song.getBpm()));
                }
                writeIfPresent(tag, FieldKey.YEAR, song.getDate());
                
                // Release date as comment
                if (song.getReleaseDate() != null && !song.getReleaseDate().isBlank()) {
                    tag.setField(FieldKey.COMMENT, "ReleaseDate:" + song.getReleaseDate());
                }
                if (song.isExplicit()) {
                    tag.setField(FieldKey.COMMENT, "Explicit");
                }
            } catch (Exception e) {
                LOGGER.debug("Error setting numeric fields: {}", e.getMessage());
            }
            
            // Custom fields stored in comment/JMedia marker
            writeCustomFields(tag, song);
            
            // Artwork
            if (song.getArtworkBase64() != null && !song.getArtworkBase64().isBlank()) {
                writeArtwork(tag, song.getArtworkBase64());
            }
            
            // Commit to file
            audioFile.commit();
            
            LOGGER.debug("Successfully wrote metadata to: {}", filePath);
            return true;
            
        } catch (Exception e) {
            LOGGER.error("Error writing metadata to {}: {}", filePath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Writes a field only if it has meaningful content (not blank).
     */
    private void writeIfPresent(Tag tag, FieldKey key, String value) {
        if (value != null && !value.isBlank() && !isUnknownValue(value)) {
            try {
                tag.setField(key, value);
            } catch (Exception e) {
                LOGGER.debug("Error writing field {}: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Checks if a value is an unknown/default placeholder.
     */
    private boolean isUnknownValue(String value) {
        if (value == null) return true;
        String lower = value.toLowerCase().trim();
        return lower.equals("unknown artist") || 
               lower.equals("unknown album") || 
               lower.equals("unknown genre") ||
               lower.isBlank();
    }

    /**
     * Writes custom app-specific fields to the comment field with marker.
     */
    private void writeCustomFields(Tag tag, Song song) {
        try {
            StringBuilder custom = new StringBuilder();
            
            // MusicBrainz ID
            if (song.getMusicbrainzId() != null && !song.getMusicbrainzId().isBlank()) {
                custom.append("mbz:").append(song.getMusicbrainzId()).append(";");
            }
            
            // App version marker
            custom.append("app:JMedia v").append(APP_VERSION);
            
            if (custom.length() > 0) {
                // Append to any existing comment or create new
                String existing = tag.getFirst(FieldKey.COMMENT);
                if (existing == null || existing.isBlank()) {
                    tag.setField(FieldKey.COMMENT, custom.toString());
                } else if (!existing.contains(APP_MARKER)) {
                    tag.setField(FieldKey.COMMENT, existing + " | " + custom.toString());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error writing custom fields: {}", e.getMessage());
        }
    }

    /**
     * Writes artwork to the tag.
     */
    private void writeArtwork(Tag tag, String base64Artwork) {
        try {
            byte[] imageData = Base64.getDecoder().decode(base64Artwork);
            Artwork artwork = ArtworkFactory.getNew();
            artwork.setBinaryData(imageData);
            artwork.setDescription("Album Cover");
            
            tag.deleteArtworkField();
            tag.setField(artwork);
        } catch (Exception e) {
            LOGGER.warn("Failed to write artwork: {}", e.getMessage());
        }
    }

    /**
     * Checks if a file format is supported.
     */
    public boolean isSupportedFormat(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".mp3") || 
               name.endsWith(".flac") || 
               name.endsWith(".m4a") || 
               name.endsWith(".ogg") || 
               name.endsWith(".wav");
    }

    /**
     * Creates a backup of the original file.
     */
    private File createBackup(String originalPath) {
        File original = new File(originalPath);
        File backup = new File(originalPath + ".jmedia.backup");
        
        try {
            if (backup.exists()) {
                backup.delete();
            }
            Files.copy(original.toPath(), backup.toPath(), 
                      StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Created backup: {}", backup.getPath());
            return backup;
        } catch (IOException e) {
            LOGGER.error("Failed to create backup for {}: {}", originalPath, e.getMessage());
            return null;
        }
    }

    /**
     * Restores file from backup if writing failed.
     */
    private void restoreFromBackup(String originalPath, File backup) {
        if (backup != null && backup.exists()) {
            try {
                File original = new File(originalPath);
                Files.copy(backup.toPath(), original.toPath(),
                          StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Restored backup for: {}", originalPath);
            } catch (IOException e) {
                LOGGER.error("Failed to restore backup for {}: {}", originalPath, e.getMessage());
            }
        }
    }

    /**
     * Gets the tag format type for a file.
     */
    public String getTagFormat(String filePath) {
        String name = new File(filePath).getName().toLowerCase();
        if (name.endsWith(".mp3")) return "ID3v2";
        if (name.endsWith(".flac")) return "Vorbis";
        if (name.endsWith(".m4a")) return "MP4";
        if (name.endsWith(".ogg")) return "Vorbis";
        if (name.endsWith(".wav")) return "ID3v2";
        return "UNKNOWN";
    }
}