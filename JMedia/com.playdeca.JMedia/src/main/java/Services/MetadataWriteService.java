package Services;

import Models.Music.Song;
import Models.Music.SongAnalysis;
import Utils.SongAnalysisTagCodec;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.valuepair.TextEncoding;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.wav.WavTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private static final String APP_VERSION = "1.3.5";

    @Inject
    Executor executor;

    @Inject
    ArtworkService artworkService;

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
            
            if (song.hasArtwork()) {
                byte[] artworkBytes = artworkService.readArtwork(song.getArtworkPath());
                if (artworkBytes != null && artworkBytes.length > 0) {
                    writeArtwork(tag, artworkBytes);
                }
            }

            // Embed audio analysis data (TarsosDSP) into tags - must not fail the metadata write
            try {
                embedAnalysis(song, tag);
            } catch (Exception e) {
                LOGGER.debug("Error embedding analysis data: {}", e.getMessage());
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
     * Embeds the song's audio analysis data into the tag as a gzip+base64 payload.
     * Entities may be detached here (callers use raw queries), so the analysis is
     * re-read via Panache inside the async worker thread.
     */
    private void embedAnalysis(Song song, Tag tag) throws Exception {
        SongAnalysis analysis = SongAnalysis.find(
                "select distinct a from SongAnalysis a left join fetch a.beatTimes where a.song.id = ?1",
                song.id).firstResult();

        String payload = SongAnalysisTagCodec.encode(analysis);
        if (payload == null) {
            LOGGER.debug("No analysis payload to embed for song {}", song.id);
            return;
        }

        if (tag instanceof WavTag wavTag) {
            AbstractID3v2Tag id3 = wavTag.getID3Tag();
            if (id3 == null) {
                LOGGER.debug("WAV file has no ID3 tag, skipping analysis embedding");
                return;
            }
            writeId3Analysis(id3, payload);
        } else if (tag instanceof AbstractID3v2Tag id3) {
            writeId3Analysis(id3, payload);
        } else if (tag instanceof FlacTag flacTag) {
            setVorbisStyleField(flacTag, payload);
        } else if (tag instanceof VorbisCommentTag vorbisTag) {
            setVorbisStyleField(vorbisTag, payload);
        } else if (tag instanceof Mp4Tag mp4Tag) {
            try {
                mp4Tag.deleteField(SongAnalysisTagCodec.MP4_FREEFORM_ID);
            } catch (Exception ignored) {
                // Field not present yet
            }
            Mp4TagReverseDnsField field = new Mp4TagReverseDnsField(
                    SongAnalysisTagCodec.MP4_FREEFORM_ID,
                    SongAnalysisTagCodec.MP4_ISSUER,
                    SongAnalysisTagCodec.MP4_DESCRIPTOR,
                    payload);
            mp4Tag.setField(field);
        } else {
            LOGGER.debug("Analysis embedding unsupported for tag type {}", tag.getClass().getName());
        }

        LOGGER.debug("Embedded analysis payload ({} chars) for song {}", payload.length(), song.id);
    }

    /**
     * Writes/updates our TXXX frame on an ID3v2 tag; setField replaces an existing
     * frame with the same description instead of duplicating it.
     */
    private void writeId3Analysis(AbstractID3v2Tag id3, String payload) throws Exception {
        FrameBodyTXXX body = new FrameBodyTXXX(
                TextEncoding.ISO_8859_1, SongAnalysisTagCodec.ID3_TXXX_DESCRIPTION, payload);
        AbstractID3v2Frame frame = id3 instanceof ID3v24Tag
                ? new ID3v24Frame("TXXX")
                : new ID3v23Frame("TXXX");
        frame.setBody(body);
        id3.setField(frame);
    }

    /**
     * Writes/updates the analysis field on Vorbis-comment based tags (FLAC/OGG).
     */
    private void setVorbisStyleField(Tag vorbisStyleTag, String payload) throws Exception {
        try {
            vorbisStyleTag.deleteField(SongAnalysisTagCodec.FIELD_KEY);
        } catch (Exception ignored) {
            // Field not present yet
        }
        if (vorbisStyleTag instanceof FlacTag flacTag) {
            flacTag.setField(flacTag.createField(SongAnalysisTagCodec.FIELD_KEY, payload));
        } else if (vorbisStyleTag instanceof VorbisCommentTag vorbisTag) {
            vorbisTag.setField(vorbisTag.createField(SongAnalysisTagCodec.FIELD_KEY, payload));
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

    private void writeArtwork(Tag tag, byte[] imageData) {
        try {
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
