package Services;

import Models.MediaFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Arrays;

@ApplicationScoped
public class SmartNamingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmartNamingService.class);
    
    // Patterns for detecting TV show indicators in metadata
    private static final Pattern[] TV_SHOW_PATTERNS = {
        Pattern.compile("(?i)season.*\\d+.*episode.*\\d+"),
        Pattern.compile("(?i)s\\d+.*e\\d+"),
        Pattern.compile("(?i)\\d+x\\d+"),
        Pattern.compile("(?i)episode.*\\d+"),
        Pattern.compile("(?i)part.*\\d+")
    };
    
    // Quality indicators that suggest movies
    private static final List<String> MOVIE_QUALITY_INDICATORS = Arrays.asList(
        "bluray", "bdrip", "dvdrip", "web-dl", "webrip", "hdcam", "ts", "tc", "yts", "yify", "imax"
    );
    
    // TV show indicators in filenames
    private static final List<String> TV_SHOW_INDICATORS = Arrays.asList(
        "season", "episode", "ep", "series", "show", "complete", "hdtv", "webrip"
    );
    
    public static class NamingResult {
        public final String mediaType; // "movie" or "episode"
        public final String showName;
        public final String title;
        public final Integer season;
        public final Integer episode;
        public final Integer year;
        public final double confidence;
        public final String reasoning;
        
        public NamingResult(String mediaType, String showName, String title, 
                          Integer season, Integer episode, Integer year, 
                          double confidence, String reasoning) {
            this.mediaType = mediaType;
            this.showName = showName;
            this.title = title;
            this.season = season;
            this.episode = episode;
            this.year = year;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }
    
    /**
     * Performs smart naming using all available data sources
     */
    public NamingResult detectSmartNames(MediaFile mediaFile, String filename, String relativePath,
                                        String rawMediaType, String rawShowName, String rawTitle,
                                        Integer rawSeason, Integer rawEpisode, Integer rawYear) {
        
        LOGGER.debug("Performing smart naming for: {} (Raw type: {})", filename, rawMediaType);
        
        // Analyze file path structure
        PathAnalysis pathAnalysis = analyzePathStructure(relativePath);
        
        // Analyze technical metadata
        TechnicalAnalysis techAnalysis = analyzeTechnicalMetadata(mediaFile, filename);
        
        // Perform comprehensive episode detection
        EpisodeDetection episodeDetection = detectEpisodeInfo(filename, pathAnalysis);
        
        // Determine media type with confidence scoring
        MediaTypeDecision mediaTypeDecision = determineMediaType(
            rawMediaType, episodeDetection, techAnalysis, pathAnalysis, filename
        );
        
        // Extract show name using all available data
        String finalShowName = extractShowName(rawShowName, filename, pathAnalysis, episodeDetection);
        
        // Extract title using enhanced methods
        String finalTitle = extractTitle(rawTitle, filename, episodeDetection, mediaTypeDecision.type);
        
        // Determine year if available
        Integer finalYear = extractYear(rawYear, filename, pathAnalysis);
        
        // Calculate final confidence score
        double finalConfidence = calculateFinalConfidence(
            mediaTypeDecision, episodeDetection, techAnalysis, pathAnalysis
        );
        
        // Generate reasoning
        String reasoning = generateReasoning(
            mediaTypeDecision, episodeDetection, techAnalysis, pathAnalysis
        );
        
        LOGGER.info("Smart naming completed for {}: {} -> {} (confidence: {})", 
                   filename, mediaTypeDecision.type, finalTitle, String.format("%.2f", finalConfidence));
        
        return new NamingResult(
            mediaTypeDecision.type,
            finalShowName,
            finalTitle,
            episodeDetection.season,
            episodeDetection.episode,
            finalYear,
            finalConfidence,
            reasoning
        );
    }
    
    /**
     * Analyzes the path structure for patterns
     */
    private PathAnalysis analyzePathStructure(String relativePath) {
        PathAnalysis analysis = new PathAnalysis();
        
        Path path = Paths.get(relativePath);
        int pathDepth = path.getNameCount();
        analysis.pathDepth = pathDepth;
        analysis.parentFolder = pathDepth > 1 ? path.getName(pathDepth - 2).toString() : "";
        analysis.grandParentFolder = pathDepth > 2 ? path.getName(pathDepth - 3).toString() : "";
        
        // Scan all path segments for explicit "Movies" or "TV Shows" indicators
        // This takes high precedence as users often organize libraries this way
        for (int i = 0; i < path.getNameCount(); i++) {
            String segment = path.getName(i).toString().toLowerCase();
            if (segment.equals("movies") || segment.equals("movie") || segment.equals("films") || segment.equals("film")) {
                analysis.directoryTypeHint = "movie";
                break;
            }
            if (segment.equals("tv shows") || segment.equals("tv") || segment.equals("shows") || segment.equals("tvshow") || segment.equals("tvseries") || segment.equals("series")) {
                analysis.directoryTypeHint = "episode";
                break;
            }
        }
        
        // Check for season folder patterns
        analysis.hasSeasonFolder = analysis.parentFolder.matches("(?i)season[s]?[-_.]?\\d{1,3}");
        
        // Check for TV show indicators in path
        String fullPathLower = relativePath.toLowerCase();
        for (String indicator : TV_SHOW_INDICATORS) {
            if (fullPathLower.contains(indicator)) {
                analysis.hasTvIndicator = true;
                break;
            }
        }
        
        // Check for movie folder patterns
        analysis.hasMovieFolderPattern = analysis.parentFolder.matches("(?i).*\\(\\d{4}\\).*") ||
                                          analysis.parentFolder.toLowerCase().contains("movie") ||
                                          analysis.parentFolder.toLowerCase().contains("film");
        
        return analysis;
    }
    
    /**
     * Analyzes technical metadata for patterns
     */
    private TechnicalAnalysis analyzeTechnicalMetadata(MediaFile mediaFile, String filename) {
        TechnicalAnalysis analysis = new TechnicalAnalysis();
        
        analysis.duration = mediaFile != null ? mediaFile.durationSeconds : 0;
        analysis.resolution = mediaFile != null ? mediaFile.getResolutionString() : "Unknown";
        analysis.quality = mediaFile != null ? mediaFile.getQualityIndicator() : "Unknown";
        analysis.isHighQuality = mediaFile != null && mediaFile.isHighQuality();
        analysis.isTypicalMovieDuration = mediaFile != null && mediaFile.isTypicalMovieDuration();
        analysis.isTypicalEpisodeDuration = mediaFile != null && mediaFile.isTypicalEpisodeDuration();
        analysis.hasMultipleAudio = mediaFile != null && mediaFile.hasMultipleAudioTracks;
        analysis.hasSubtitles = mediaFile != null && mediaFile.hasEmbeddedSubtitles;
        analysis.isWidescreen = mediaFile != null && mediaFile.isWidescreen();
        
        // Check for movie quality indicators in filename
        String filenameLower = filename.toLowerCase();
        for (String indicator : MOVIE_QUALITY_INDICATORS) {
            if (filenameLower.contains(indicator)) {
                analysis.hasMovieQualityIndicator = true;
                break;
            }
        }
        
        return analysis;
    }
    
    /**
     * Enhanced episode detection using regex patterns
     */
    private EpisodeDetection detectEpisodeInfo(String filename, PathAnalysis pathAnalysis) {
        EpisodeDetection detection = new EpisodeDetection();
        
        // 1. S01E01 Pattern
        Pattern s01e01 = Pattern.compile("(?i)(.*?)[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})(.*)");
        Matcher m1 = s01e01.matcher(filename);
        if (m1.matches()) {
            detection.season = Integer.parseInt(m1.group(2));
            detection.episode = Integer.parseInt(m1.group(3));
            detection.titleHint = m1.group(4).trim();
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "SxxExx";
            detection.confidence = 0.9;
            return detection;
        }

        // 2. 1x01 Pattern
        Pattern simpleX = Pattern.compile("(?i)(.*?)(\\d{1,2})[x×](\\d{1,3})(.*)");
        Matcher m2 = simpleX.matcher(filename);
        if (m2.matches()) {
            detection.season = Integer.parseInt(m2.group(2));
            detection.episode = Integer.parseInt(m2.group(3));
            detection.titleHint = m2.group(4).trim();
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "XxY";
            detection.confidence = 0.8;
            return detection;
        }

        // 3. "Episode 01" Pattern
        Pattern epOnly = Pattern.compile("(?i)(.*?)[eE]pisode[\\s\\._-]*(\\d{1,3})(.*)");
        Matcher m3 = epOnly.matcher(filename);
        if (m3.matches()) {
            detection.season = null;
            detection.episode = Integer.parseInt(m3.group(2));
            detection.titleHint = m3.group(3).trim();
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "EpisodeOnly";
            detection.confidence = 0.7;
            return detection;
        }
        
        // Check path structure for episode indicators
        if (pathAnalysis.hasSeasonFolder) {
            detection.hasSeasonFolder = true;
            detection.season = extractSeasonFromFolder(pathAnalysis.parentFolder);
            detection.detectionMethod = "PathStructure";
            detection.confidence = 0.6;
        }
        
        return detection;
    }
    
    /**
     * Determines the most likely media type based on all evidence
     */
    private MediaTypeDecision determineMediaType(String rawMediaType, EpisodeDetection episodeDetection,
                                               TechnicalAnalysis techAnalysis, PathAnalysis pathAnalysis,
                                               String filename) {
        
        double movieScore = 0.0;
        double episodeScore = 0.0;
        StringBuilder reasoning = new StringBuilder();

        // 0. DIRECTORY HINT (Highest precedence)
        if ("movie".equals(pathAnalysis.directoryTypeHint)) {
            movieScore += 2.0;
            reasoning.append("Directory hint: Movie (+2.0); ");
        } else if ("episode".equals(pathAnalysis.directoryTypeHint)) {
            episodeScore += 2.0;
            reasoning.append("Directory hint: TV Show (+2.0); ");
        }
        
        // Start with raw detection
        if ("episode".equals(rawMediaType)) {
            episodeScore += 0.4;
            reasoning.append("Raw detection: episode (+0.4); ");
        } else if ("movie".equals(rawMediaType)) {
            movieScore += 0.4;
            reasoning.append("Raw detection: movie (+0.4); ");
        }
        
        // Episode pattern analysis
        if (episodeDetection.hasEpisodePattern) {
            episodeScore += 0.5;
            reasoning.append("Episode pattern found (+0.5); ");
        }
        
        // Season folder analysis
        if (pathAnalysis.hasSeasonFolder) {
            episodeScore += 0.4;
            reasoning.append("Season folder structure (+0.4); ");
        }
        
        // Duration analysis
        if (techAnalysis.isTypicalMovieDuration) {
            movieScore += 0.3;
            reasoning.append("Typical movie duration (+0.3); ");
        } else if (techAnalysis.isTypicalEpisodeDuration) {
            episodeScore += 0.3;
            reasoning.append("Typical episode duration (+0.3); ");
        }
        
        // Quality indicators
        if (techAnalysis.hasMovieQualityIndicator) {
            movieScore += 0.2;
            reasoning.append("Movie quality indicator (+0.2); ");
        }
        
        // Path indicators
        if (pathAnalysis.hasTvIndicator) {
            episodeScore += 0.2;
            reasoning.append("TV indicator in path (+0.2); ");
        } else if (pathAnalysis.hasMovieFolderPattern) {
            movieScore += 0.2;
            reasoning.append("Movie folder pattern (+0.2); ");
        }
        
        // Audio tracks (movies often have multiple audio tracks)
        if (techAnalysis.hasMultipleAudio) {
            movieScore += 0.1;
            reasoning.append("Multiple audio tracks (+0.1 movie); ");
        }
        
        // Widescreen preference for movies
        if (techAnalysis.isWidescreen) {
            movieScore += 0.1;
            reasoning.append("Widescreen format (+0.1 movie); ");
        }
        
        String finalType = episodeScore > movieScore ? "episode" : "movie";
        double confidence = Math.max(episodeScore, movieScore) / (episodeScore + movieScore + 0.1);
        
        return new MediaTypeDecision(finalType, confidence, reasoning.toString());
    }
    
    /**
     * Extracts the best possible show name
     */
    private String extractShowName(String rawShowName, String filename, PathAnalysis pathAnalysis, 
                                  EpisodeDetection episodeDetection) {
        
        // 1. If we have a grandparent folder and parent is a season folder, grandparent is almost certainly the show name
        if (pathAnalysis.hasSeasonFolder && !pathAnalysis.grandParentFolder.isEmpty()) {
            String folderShowName = cleanShowName(pathAnalysis.grandParentFolder);
            if (!folderShowName.equals("Unknown Show") && !folderShowName.equalsIgnoreCase("tv shows") && !folderShowName.equalsIgnoreCase("shows")) {
                return folderShowName;
            }
        }
        
        // 2. If parent folder is not a season folder and not a generic "TV Shows" folder, it might be the show name
        if (!pathAnalysis.parentFolder.isEmpty()) {
            String folderShowName = cleanShowName(pathAnalysis.parentFolder);
            if (!folderShowName.equals("Unknown Show") && 
                !folderShowName.equalsIgnoreCase("tv shows") && 
                !folderShowName.equalsIgnoreCase("shows") &&
                !folderShowName.equalsIgnoreCase("movies") &&
                !pathAnalysis.hasSeasonFolder) {
                return folderShowName;
            }
        }
        
        // 3. Extract from filename if it has SxxExx pattern (the part before SxxExx)
        Pattern s01e01 = Pattern.compile("(?i)(.*?)[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})");
        Matcher m = s01e01.matcher(filename);
        if (m.find()) {
            String nameFromFilename = cleanShowName(m.group(1));
            if (!nameFromFilename.equals("Unknown Show")) {
                return nameFromFilename;
            }
        }
        
        // 4. Fall back to raw show name
        if (rawShowName != null && !rawShowName.trim().isEmpty()) {
            return cleanShowName(rawShowName);
        }
        
        return "Unknown Show";
    }

    /**
     * Extracts the best possible title
     */
    private String extractTitle(String rawTitle, String filename, EpisodeDetection episodeDetection, 
                               String mediaType) {
        
        // Use episode title hint if available
        if (episodeDetection.titleHint != null && !episodeDetection.titleHint.trim().isEmpty()) {
            return cleanTitle(episodeDetection.titleHint);
        }
        
        // Use raw title if available
        if (rawTitle != null && !rawTitle.trim().isEmpty()) {
            return cleanTitle(rawTitle);
        }
        
        // Extract from filename
        String filenameWithoutExt = filename.replaceFirst("\\.[^.]+$", "");
        
        // Remove episode patterns
        if ("episode".equals(mediaType)) {
            for (Pattern pattern : TV_SHOW_PATTERNS) {
                filenameWithoutExt = pattern.matcher(filenameWithoutExt).replaceAll("").trim();
            }
        }
        
        // Use simple cleaning for final result
        return cleanTitle(filenameWithoutExt);
    }
    
    /**
     * Extracts year from various sources
     */
    private Integer extractYear(Integer rawYear, String filename, PathAnalysis pathAnalysis) {
        if (rawYear != null) {
            return rawYear;
        }
        
        // Try to extract from filename
        java.util.regex.Pattern yearPattern = java.util.regex.Pattern.compile("\\b(19|20)\\d{2}\\b");
        java.util.regex.Matcher matcher = yearPattern.matcher(filename);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                // Continue
            }
        }
        
        // Try to extract from folder names
        String[] parts = (pathAnalysis.parentFolder + " " + pathAnalysis.grandParentFolder).split(" ");
        for (String part : parts) {
            matcher = yearPattern.matcher(part);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group());
                } catch (NumberFormatException e) {
                    // Continue
                }
            }
        }
        
        return null;
    }
    
    /**
     * Calculates final confidence score
     */
    private double calculateFinalConfidence(MediaTypeDecision mediaTypeDecision, 
                                           EpisodeDetection episodeDetection,
                                           TechnicalAnalysis techAnalysis,
                                           PathAnalysis pathAnalysis) {
        
        double baseConfidence = mediaTypeDecision.confidence;
        
        // Bonus factors
        if (episodeDetection.hasEpisodePattern) {
            baseConfidence += 0.1;
        }
        
        if (episodeDetection.confidence > 0.8) {
            baseConfidence += 0.1;
        }
        
        if (techAnalysis.isTypicalMovieDuration || techAnalysis.isTypicalEpisodeDuration) {
            baseConfidence += 0.05;
        }
        
        if (pathAnalysis.hasSeasonFolder) {
            baseConfidence += 0.05;
        }
        
        return Math.min(baseConfidence, 1.0);
    }
    
    /**
     * Cleans show name by removing common patterns and indicators
     */
    private static String cleanShowName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Unknown Show";
        }
        
        String cleaned = name;

        // 1. Remove bracketed content (usually release groups) at the beginning or end
        cleaned = cleaned.replaceAll("(?i)^[\\[\\(].*?[\\]\\)]\\s*", "");
        cleaned = cleaned.replaceAll("(?i)\\s*[\\[\\(].*?[\\]\\)]$", "");

        // 2. Remove anything that looks like metadata inside brackets/parentheses
        cleaned = cleaned.replaceAll("(?i)[\\[\\(].*?(720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|yts|yify|imax|x264|x265|hevc|aac|ac3|dts).*?[\\]\\)]", " ");
        
        // 3. Remove leading sequence numbers (e.g. "01. ", "1. ", "1 - ")
        cleaned = cleaned.replaceAll("^\\d+[\\s\\.\\-_]+", "");

        // 4. Remove year patterns in parentheses like (2024)
        cleaned = cleaned.replaceAll("\\((19|20)\\d{2}\\)", "");
        // Or standalone year
        cleaned = cleaned.replaceAll("\\b(19|20)\\d{2}\\b", "");

        // 5. Remove quality and release indicators (Comprehensive list)
        cleaned = cleaned.replaceAll("(?i)\\b(720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|yts|yify|imax|hybrid|remastered|extended|collector|ultimate|proper|repack|x264|x265|hevc|aac|ac3|dts|ddp|5\\s*[\\.\\s]?1|7\\s*[\\.\\s]?1|yts\\.mx|yts\\.am|yify\\.tv|shiniori|dual-audio|multi-audio|sub|dub|galaxyrg|neonoir|mzabi|psa|pahe|800mb|10bit|tri-audio|multi-subs|bonkai77|pahe\\.in)\\b", " ");
        
        // 6. Remove season/episode patterns
        cleaned = cleaned.replaceAll("(?i)\\b(season|s|episode|e|part)\\s*[\\d\\-]+", " ");
        
        // 7. Remove special characters and clean up
        cleaned = cleaned.replaceAll("[._\\-\\[\\]\\(\\)]+", " ").trim();
        
        // 8. Remove extra spaces and capitalize properly
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = toTitleCase(cleaned.trim());
        
        return cleaned.isEmpty() ? "Unknown Show" : cleaned;
    }
    
    /**
     * Cleans title by removing common artifacts
     */
    private static String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "Unknown Title";
        }
        
        String cleaned = title;

        // 1. Remove leading release groups in brackets
        cleaned = cleaned.replaceAll("(?i)^[\\[\\(].*?[\\]\\)]\\s*", "");

        // 2. Remove metadata in brackets/parentheses
        cleaned = cleaned.replaceAll("(?i)[\\[\\(].*?(720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|yts|yify|imax|x264|x265|hevc|aac|ac3|dts).*?[\\]\\)]", " ");

        // 3. Remove sequence numbers
        cleaned = cleaned.replaceAll("^\\d+[\\s\\.\\-_]+", "");

        // 4. Remove year
        cleaned = cleaned.replaceAll("\\((19|20)\\d{2}\\)", "");
        cleaned = cleaned.replaceAll("\\b(19|20)\\d{2}\\b", "");
        
        // 5. Remove quality indicators
        cleaned = cleaned.replaceAll("(?i)\\b(720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|yts|yify|imax|hybrid|remastered|extended|collector|ultimate|proper|repack|x264|x265|hevc|aac|ac3|dts|yts\\.mx|yts\\.am|shiniori|dual-audio|multi-audio|sub|dub|galaxyrg|neonoir|mzabi|psa|pahe|800mb|10bit|tri-audio|multi-subs|bonkai77|pahe\\.in)\\b", " ");
        
        // 6. Remove common release info
        cleaned = cleaned.replaceAll("(?i)\\b(proper|repack|extended|uncut|unrated|directors?.cut)\\b", " ");
        
        // 7. Clean up separators and special characters
        cleaned = cleaned.replaceAll("[._\\-\\[\\]\\(\\)]+", " ").trim();
        
        // 8. Remove extra spaces
        cleaned = cleaned.replaceAll("\\s+", " ");
        
        return toTitleCase(cleaned.trim());
    }
    
    /**
     * Converts to title case
     */
    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        
        return result.toString();
    }
    
    /**
     * Generates reasoning string
     */
    private String generateReasoning(MediaTypeDecision mediaTypeDecision, EpisodeDetection episodeDetection,
                                   TechnicalAnalysis techAnalysis, PathAnalysis pathAnalysis) {
        StringBuilder reasoning = new StringBuilder();
        
        reasoning.append("Media Type: ").append(mediaTypeDecision.reasoning);
        
        if (episodeDetection.hasEpisodePattern) {
            reasoning.append("Episode pattern: ").append(episodeDetection.detectionMethod).append("; ");
        }
        
        if (techAnalysis.isTypicalMovieDuration) {
            reasoning.append("Duration matches movie pattern; ");
        } else if (techAnalysis.isTypicalEpisodeDuration) {
            reasoning.append("Duration matches episode pattern; ");
        }
        
        if (pathAnalysis.hasSeasonFolder) {
            reasoning.append("Season folder detected; ");
        }
        
        return reasoning.toString();
    }
    
    /**
     * Extracts season number from folder name
     */
    private Integer extractSeasonFromFolder(String folderName) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)season[s]?[-_.]?(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(folderName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    // Helper classes for analysis results
    private static class PathAnalysis {
        public int pathDepth;
        public String parentFolder;
        public String grandParentFolder;
        public boolean hasSeasonFolder;
        public boolean hasTvIndicator;
        public boolean hasMovieFolderPattern;
        public String directoryTypeHint; // "movie" or "episode"
    }
    
    private static class TechnicalAnalysis {
        public int duration;
        public String resolution;
        public String quality;
        public boolean isHighQuality;
        public boolean isTypicalMovieDuration;
        public boolean isTypicalEpisodeDuration;
        public boolean hasMultipleAudio;
        public boolean hasSubtitles;
        public boolean isWidescreen;
        public boolean hasMovieQualityIndicator;
    }
    
    private static class EpisodeDetection {
        public Integer season;
        public Integer episode;
        public String titleHint;
        public boolean hasEpisodePattern;
        public boolean hasSeasonFolder;
        public String detectionMethod;
        public double confidence;
    }
    
    private static class MediaTypeDecision {
        public final String type;
        public final double confidence;
        public final String reasoning;
        
        public MediaTypeDecision(String type, double confidence, String reasoning) {
            this.type = type;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }
}