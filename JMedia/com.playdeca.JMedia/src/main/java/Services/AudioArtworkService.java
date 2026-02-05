package Services;

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
import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Service for embedding album artwork directly into audio files.
 * Supports MP3, FLAC, M4A, OGG, and WAV formats using JAudioTagger.
 */
@ApplicationScoped
public class AudioArtworkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioArtworkService.class);

    @Inject
    Executor executor;

    /**
     * Embeds artwork into an audio file asynchronously with backup protection.
     * 
     * @param songPath Path to the audio file
     * @param base64Artwork Base64 encoded image data
     * @return CompletableFuture with success status
     */
    public CompletableFuture<Boolean> embedArtworkInFile(String songPath, String base64Artwork) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Starting artwork embedding for: {}", songPath);
                
                // Validate inputs
                if (!isSupportedFormat(new File(songPath))) {
                    LOGGER.warn("Unsupported file format for artwork embedding: {}", songPath);
                    return false;
                }
                
                if (base64Artwork == null || base64Artwork.trim().isEmpty()) {
                    LOGGER.warn("No artwork data provided for: {}", songPath);
                    return false;
                }
                
                // Create backup of original file
                File backup = createBackup(songPath);
                if (backup == null) {
                    LOGGER.error("Failed to create backup for: {}", songPath);
                    return false;
                }
                
                // Embed artwork
                boolean success = embedArtwork(songPath, base64Artwork);
                
                if (!success) {
                    LOGGER.warn("Artwork embedding failed, restoring backup for: {}", songPath);
                    restoreFromBackup(songPath, backup);
                    return false;
                }
                
                // Clean up backup after successful operation
                if (backup.exists()) {
                    backup.delete();
                }
                
                LOGGER.info("Successfully embedded artwork in: {}", songPath);
                return true;
                
            } catch (Exception e) {
                LOGGER.error("Failed to embed artwork in {}: {}", songPath, e.getMessage(), e);
                return false;
            }
        }, executor);
    }

    /**
     * Checks if a file has existing artwork.
     * 
     * @param songPath Path to the audio file
     * @return true if artwork exists, false otherwise
     */
    public boolean hasExistingArtwork(String songPath) {
        try {
            File file = new File(songPath);
            if (!file.exists() || !isSupportedFormat(file)) {
                return false;
            }
            
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            return tag != null && tag.getFirstArtwork() != null;
            
        } catch (Exception e) {
            LOGGER.debug("Could not check existing artwork for {}: {}", songPath, e.getMessage());
            return false;
        }
    }

    /**
     * Gets the dimensions of existing artwork in a file.
     * 
     * @param songPath Path to the audio file
     * @return array with [width, height] or [0, 0] if no artwork
     */
    public int[] getExistingArtworkDimensions(String songPath) {
        try {
            File file = new File(songPath);
            if (!file.exists()) {
                return new int[]{0, 0};
            }
            
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            if (tag == null) {
                return new int[]{0, 0};
            }
            
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null) {
                return new int[]{0, 0};
            }
            
            return new int[]{artwork.getWidth(), artwork.getHeight()};
            
        } catch (Exception e) {
            LOGGER.debug("Could not get artwork dimensions for {}: {}", songPath, e.getMessage());
            return new int[]{0, 0};
        }
    }

    /**
     * Checks if a file format is supported for artwork embedding.
     * 
     * @param file Audio file to check
     * @return true if supported, false otherwise
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
     * Core artwork embedding logic.
     */
    private boolean embedArtwork(String filePath, String base64Artwork) {
        try {
            File file = new File(filePath);
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateDefault();
            
            // Decode base64 to image bytes
            byte[] imageData = Base64.getDecoder().decode(base64Artwork);
            
            // Create artwork object from image data
            Artwork artwork = ArtworkFactory.getNew();
            artwork.setBinaryData(imageData);
            artwork.setDescription("Album Cover");
            
            // Clear existing artwork and set new artwork
            tag.deleteArtworkField();
            tag.setField(artwork);
            
            // Write changes to file
            audioFile.commit();
            
            LOGGER.debug("Successfully wrote artwork to file: {}", filePath);
            return true;
            
        } catch (Exception e) {
            LOGGER.error("Artwork embedding failed for {}: {}", filePath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Creates a backup of the original file before modification.
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
     * Restores file from backup if embedding failed.
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
     * Gets the tag format type for a given file.
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