package Services;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.List;

public class SmartShowNameDetector {
    
    // Common patterns for TV show detection
    private static final Pattern[] SHOW_PATTERNS = {
        // S01E01, S1E1 patterns
        Pattern.compile("(?i)^(.*?)[\\s._-]*S(\\d{1,2})[\\s._-]*E(\\d{1,3})"),
        // 1x01, 01x01 patterns  
        Pattern.compile("(?i)^(.*?)[\\s._-]*(\\d{1,2})[xX](\\d{1,3})"),
        // Season 1 Episode 1 patterns
        Pattern.compile("(?i)^(.*?)[\\s._-]*Season[\\s._-]*(\\d{1,2})[\\s._-]*Episode[\\s._-]*(\\d{1,3})"),
        // Episode 1 patterns
        Pattern.compile("(?i)^(.*?)[\\s._-]*Episode[\\s._-]*(\\d{1,3})")
    };
    
    // Common words/phrases that indicate TV shows
    private static final List<String> TV_INDICATORS = Arrays.asList(
        "season", "episode", "ep", "s", "e", "complete", "series", "show"
    );
    
    // Common release group tags to strip
    private static final Pattern[] CLEANUP_PATTERNS = {
        Pattern.compile("(?i)\\b(\\d{4})\\b"), // Years
        Pattern.compile("(?i)\\b(\\d{3,4}p|\\d{3,4}i)\\b"), // Resolutions
        Pattern.compile("(?i)\\b(web[-]?rip|hdtv|bluray|x264|x265|h265|hevc|aac|ac3|dts|dts[-]?hd|ma|truehd)\\b"),
        Pattern.compile("(?i)\\b(netflix|nf|amazon|amzn|hulu|hbo|showtime|starz|disney\\+|d\\+|apple\\+|peacock|paramount\\+)\\b"),
        Pattern.compile("(?i)\\b(galaxytv|gtv|internal|proper|repack|extended|uncut|uncensored|director'?s?\\s*cut)\\b"),
        Pattern.compile("(?i)\\b(web|dl|web-dl|bd|brip|dvd|dvdrip|hdcam|ts|tc|cam)\\b"),
        Pattern.compile("(?i)\\b(10bit|8bit|hdr|sdr|dolby|atmos|dts[-]?x|ddp)\\b")
    };
    
    public static class ShowInfo {
        public final String cleanName;
        public final Integer season;
        public final Integer episode;
        public final String titleHint;
        public final double confidence;
        
        public ShowInfo(String cleanName, Integer season, Integer episode, String titleHint, double confidence) {
            this.cleanName = cleanName;
            this.season = season;
            this.episode = episode;
            this.titleHint = titleHint;
            this.confidence = confidence;
        }
    }
    
    public static Optional<ShowInfo> detectFromFilename(String filename) {
        filename = filename.replaceFirst("\\.[^.]+$", ""); // Remove extension
        
        for (Pattern pattern : SHOW_PATTERNS) {
            var matcher = pattern.matcher(filename);
            if (matcher.find()) {
                String showName = matcher.group(1).trim();
                int season = Integer.parseInt(matcher.group(2));
                int episode = Integer.parseInt(matcher.groupCount() > 2 ? matcher.group(3) : "0");
                
                // Extract title hint after episode pattern
                String titleHint = null;
                int endIndex = matcher.end();
                if (endIndex < filename.length()) {
                    titleHint = filename.substring(endIndex).trim();
                    titleHint = cleanTitleHint(titleHint);
                }
                
                String cleanShowName = cleanShowName(showName);
                double confidence = calculateConfidence(showName, season, episode);
                
                return Optional.of(new ShowInfo(cleanShowName, season, episode, titleHint, confidence));
            }
        }
        
        return Optional.empty();
    }
    
    public static Optional<String> detectFromFolder(String folderName) {
        String cleanName = cleanShowName(folderName);
        
        // Check if this looks like a TV show folder
        if (isLikelyTvShow(cleanName)) {
            return Optional.of(cleanName);
        }
        
        return Optional.empty();
    }
    
    private static String cleanShowName(String name) {
        String cleaned = name;
        
        // Apply cleanup patterns
        for (Pattern pattern : CLEANUP_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }
        
        // Remove brackets and contents
        cleaned = cleaned.replaceAll("\\[[^\\]]*\\]", "");
        cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
        
        // Remove common separators
        cleaned = cleaned.replaceAll("[._\\-\\+]+", " ");
        
        // Remove language tags
        cleaned = cleaned.replaceAll("(?i)\\b(english|spanish|french|german|italian|subtitulado|dubbed|vo|vost|sub)\\b", "");
        
        // Clean up whitespace
        cleaned = cleaned.trim().replaceAll("\\s+", " ");
        
        return cleaned.isEmpty() ? "Unknown Show" : cleaned;
    }
    
    private static String cleanTitleHint(String titleHint) {
        return titleHint
            .replaceAll("^[._\\-\\s]+", "") // Remove leading separators
            .replaceAll("\\.[^.]*$", "") // Remove extension if present
            .replaceAll("(?i)\\b(1080p|720p|480p|x264|x265|h264|h265|hevc|webrip|proper|repack)\\b", "")
            .trim();
    }
    
    private static boolean isLikelyTvShow(String name) {
        String lowerName = name.toLowerCase();
        
        // Check for TV indicators
        boolean hasTvIndicator = TV_INDICATORS.stream()
            .anyMatch(indicator -> lowerName.contains(indicator));
        
        // Check for common TV show naming patterns
        boolean hasTvPattern = lowerName.matches(".*\\b(season|series|show|episode|ep|s\\d|\\d+x\\d).*");
        
        // If it has TV indicators, it's likely a TV show
        return hasTvIndicator || hasTvPattern;
    }
    
    private static double calculateConfidence(String showName, int season, int episode) {
        double confidence = 0.5; // Base confidence
        
        // Higher confidence for standard S##E## format
        if (showName.matches("(?i).*S\\d{1,2}E\\d{1,3}.*")) {
            confidence += 0.3;
        }
        
        // Higher confidence if show name doesn't contain junk
        long junkCount = CLEANUP_PATTERNS.length;
        long actualJunk = Arrays.stream(CLEANUP_PATTERNS)
            .mapToInt(pattern -> (int) pattern.matcher(showName).results().count())
            .sum();
        
        if (actualJunk < junkCount / 2) {
            confidence += 0.2;
        }
        
        return Math.min(confidence, 1.0);
    }
    
    /**
     * Static version of cleanShowName for external use
     */
    public static String cleanShowNameStatic(String name) {
        String cleaned = name;
        
        // Apply cleanup patterns
        for (Pattern pattern : CLEANUP_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }
        
        // Remove brackets and contents
        cleaned = cleaned.replaceAll("\\[[^\\]]*\\]", "");
        cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
        
        // Remove common separators
        cleaned = cleaned.replaceAll("[._\\-\\+]+", " ");
        
        // Remove language tags
        cleaned = cleaned.replaceAll("(?i)\\b(english|spanish|french|german|italian|subtitulado|dubbed|vo|vost|sub)\\b", "");
        
        // Clean up whitespace
        cleaned = cleaned.trim().replaceAll("\\s+", " ");
        
        return cleaned.isEmpty() ? "Unknown Show" : cleaned;
    }
    
    /**
     * Static version of cleanTitleHint for external use
     */
    public static String cleanTitleHintStatic(String titleHint) {
        if (titleHint == null || titleHint.trim().isEmpty()) {
            return "";
        }
        
        return titleHint
            .replaceAll("^[._\\-\\s]+", "") // Remove leading separators
            .replaceAll("\\.[^.]*$", "") // Remove extension if present
            .replaceAll("(?i)\\b(1080p|720p|480p|x264|x265|h264|h265|hevc|webrip|proper|repack|hdtv|bluray|dvdrip|web-dl)\\b", "")
            .replaceAll("(?i)\\b(netflix|hulu|amazon|disney\\+|apple\\+|hbo|showtime)\\b", "")
            .trim();
    }
}