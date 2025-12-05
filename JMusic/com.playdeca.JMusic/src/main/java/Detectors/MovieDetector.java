package Detectors;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MovieDetector {

    public static MovieInfo detect(String filename) {
        String cleanedFilename = removeFileExtension(filename);
        cleanedFilename = cleanedFilename.replace('.', ' ').replace('_', ' ').trim();

        String title = cleanedFilename;
        Integer releaseYear = null;
        
        // Pattern for year in parentheses, e.g., (2023)
        Pattern yearInParensPattern = Pattern.compile("\\((\\d{4})\\)");
        Matcher parenMatcher = yearInParensPattern.matcher(cleanedFilename);

        if (parenMatcher.find()) {
            int foundYear = Integer.parseInt(parenMatcher.group(1));
            // Basic validation for a reasonable year range
            if (foundYear > 1880 && foundYear < 2100) {
                releaseYear = foundYear;
                title = cleanedFilename.substring(0, parenMatcher.start()).trim();
            }
        } else {
            // Pattern for standalone year, e.g., 2023, not part of something like 1080p
            // Use word boundaries to avoid matching parts of other numbers.
            Pattern standaloneYearPattern = Pattern.compile("\\b(\\d{4})\\b");
            Matcher standaloneMatcher = standaloneYearPattern.matcher(cleanedFilename);
            
            int lastMatchStart = -1;
            int foundYear = -1;

            // Find the last valid year in the string
            while (standaloneMatcher.find()) {
                int potentialYear = Integer.parseInt(standaloneMatcher.group(1));
                if (potentialYear > 1880 && potentialYear < 2100) {
                    foundYear = potentialYear;
                    lastMatchStart = standaloneMatcher.start();
                }
            }
            
            if (foundYear != -1) {
                releaseYear = foundYear;
                title = cleanedFilename.substring(0, lastMatchStart).trim();
            }
        }

        // If the title is empty after stripping the year, it means the filename was just the year.
        if (title.isEmpty() && releaseYear != null) {
            title = String.valueOf(releaseYear);
        } else if (title.isEmpty() && releaseYear == null) {
            // If title is empty and we still have no year, fallback to the cleaned filename.
            title = cleanedFilename;
        }

        return new MovieInfo(title, releaseYear);
    }

    private static String removeFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0) { // Ensure it's not a hidden file or at the start
            return filename.substring(0, lastDotIndex);
        }
        return filename;
    }

    public static class MovieInfo {
        public final String title;
        public final Integer releaseYear; // null if not found

        public MovieInfo(String title, Integer releaseYear) {
            this.title = title;
            this.releaseYear = releaseYear;
        }
    }
}
