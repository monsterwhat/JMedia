package Services;

import Models.Song;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
// import org.jaudiotagger.tag.datatype.Artwork; // Commented out - class not available in jaudiotagger 3.0.1

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import org.jaudiotagger.tag.TagException;

@ApplicationScoped
public class MetadataService {
    
    @Inject
    LoggingService loggingService;
    
    /**
     * Extracts metadata from an audio file and creates a Song object.
     *
     * @param audioFile The audio file to extract metadata from.
     * @param relativePath The relative path of the audio file within the music library.
     * @return A new Song object populated with extracted metadata, or null if metadata extraction fails.
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
                song.setLyrics(tag.getFirst(FieldKey.LYRICS));

                // Duration in seconds
                song.setDurationSeconds(f.getAudioHeader().getTrackLength());

                 // Skip artwork due to jaudiotagger compatibility issues
                song.setArtworkBase64("");
                song.setTitle(audioFile.getName().substring(0, audioFile.getName().lastIndexOf('.')));
                song.setArtist("Unknown Artist");
            }

            // Ensure no nulls for critical fields
            song = sanitizeSongData(song, audioFile);

            return song;

        } catch (IOException | ReadOnlyFileException | InvalidAudioFrameException | TagException | CannotReadException e ) {
            loggingService.addLog("Failed to extract metadata from file: " + audioFile.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * Sanitizes and ensures song data has no null values for critical fields.
     *
     * @param song The song object to sanitize
     * @param audioFile The original audio file for fallback information
     * @return The sanitized song object
     */    
    /**
     * Sanitizes and ensures song data has no null values for critical fields.
     *
     * @param song The song object to sanitize
     * @param audioFile The original audio file for fallback information
     * @return The sanitized song object
     */
    private Song sanitizeSongData(Song song, File audioFile) {
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
    }
    
    /**
     * Normalizes text for better matching by removing common patterns and punctuation.
     *
     * @param text The text to normalize
     * @return Normalized text
     */
    public String normalizeForMatching(String text) {
        if (text == null) {
            return "";
        }

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
    
    /**
     * Calculates similarity between two strings using a simple character-based approach.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    public double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }

        // Simple Levenshtein-like similarity for better matching
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) {
            return 1.0;
        }

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
    
    /**
     * Finds the best matching song from a list of candidates based on artist and title.
     *
     * @param searchArtist The artist to search for
     * @param searchTitle The title to search for
     * @param candidates List of candidate songs
     * @return Best matching song or null if no good match found
     */
    public Song findBestMatch(String searchArtist, String searchTitle, java.util.List<Song> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

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
    
    /**
     * Extracts the primary artist from a potentially multi-artist string.
     *
     * @param rawArtist The raw artist string
     * @return Primary artist name
     */
    public String extractPrimaryArtist(String rawArtist) {
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            return rawArtist;
        }

        // Handle multiple artists separated by commas or "feat."
        String[] artists = rawArtist.split(",\\s*|\\s+feat\\.\\s+|\\s+&\\s+|\\s+/\\s+");

        // Return the first (primary) artist
        String primaryArtist = artists[0].trim();

        // Remove any remaining "feat." or similar from primary artist
        primaryArtist = primaryArtist.split("\\s+feat\\.\\s+")[0].trim();

        return primaryArtist;
    }
    
    /**
     * Cleans title by removing version information in parentheses.
     *
     * @param title The title to clean
     * @return Cleaned title
     */
    public String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return title;
        }

        // Remove version info in parentheses at the end
        String cleaned = title.replaceAll("\\s*\\([^)]*\\)\\s*$", ""); // Remove (anything) at end
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remaster\\s*$", ""); // Remove - 2003 Remaster at end
        cleaned = cleaned.replaceAll("\\s*-\\s*\\d{4}\\s+Remastered\\s*\\d{4}\\s*$", ""); // Remove - 2003 Remastered 2009 at end
        cleaned = cleaned.replaceAll("\\s*-\\s*Mono Version\\s*Remastered\\s*\\d{4}\\s*$", ""); // Remove - Mono Version Remastered 2002 at end
        cleaned = cleaned.trim();

        return cleaned.isEmpty() ? title : cleaned; // Return original if cleaning removes everything
    }
    
    /**
     * Processes multiple audio files with progress reporting (like music scanning pattern)
     */
    public void processBatchMetadata(java.util.List<File> audioFiles) {
        loggingService.addLog("Starting metadata extraction for " + audioFiles.size() + " audio files...");
        
        int totalProcessed = 0;
        int successCount = 0;
        int failedCount = 0;
        
        for (int i = 0; i < audioFiles.size(); i++) {
            File audioFile = audioFiles.get(i);
            try {
                String relativePath = audioFile.getName(); // Use filename as relative path for logging
                Song result = extractMetadata(audioFile, relativePath);
                totalProcessed++;
                
                if (result != null) {
                    successCount++;
                    loggingService.addLog("Successfully extracted metadata for: " + audioFile.getName() + 
                                       " - " + result.getTitle() + " by " + result.getArtist());
                } else {
                    failedCount++;
                    loggingService.addLog("Failed to extract metadata for: " + audioFile.getName() + " (no metadata found)");
                }
                
                // Progress reporting every 50 files (like music scanning)
                if ((i + 1) % 50 == 0) {
                    loggingService.addLog("Processed " + (i + 1) + " / " + audioFiles.size() + 
                                       " metadata files (Success: " + successCount + ", Failed: " + failedCount + ")...");
                }
                
            } catch (Exception e) {
                failedCount++;
                totalProcessed++;
                loggingService.addLog("Error processing metadata for " + audioFile.getName() + ": " + e.getMessage(), e);
            }
        }
        
        loggingService.addLog("Metadata extraction completed. Total processed: " + totalProcessed + 
                           ", Success: " + successCount + ", Failed: " + failedCount);
    }
}