package Services;

import Models.MediaFile;
import Services.SmartShowNameDetector;
import Detectors.EpisodeDetector;
import Detectors.MovieDetector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
        "bluray", "bdrip", "dvdrip", "web-dl", "webrip", "hdcam", "ts", "tc"
    );
    
    // TV show indicators in filenames
    private static final List<String> TV_SHOW_INDICATORS = Arrays.asList(
        "season", "episode", "ep", "series", "show", "complete"
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
        
        LOGGER.info("Smart naming completed for {}: {} -> {} (confidence: {:.2f})", 
                   filename, mediaTypeDecision.type, finalTitle, finalConfidence);
        
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
        analysis.parentFolder = pathDepth > 1 ? path.getParent().getFileName().toString() : "";
        analysis.grandParentFolder = pathDepth > 2 ? path.getParent().getParent().getFileName().toString() : "";
        
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
        
        analysis.duration = mediaFile.durationSeconds;
        analysis.resolution = mediaFile.getResolutionString();
        analysis.quality = mediaFile.getQualityIndicator();
        analysis.isHighQuality = mediaFile.isHighQuality();
        analysis.isTypicalMovieDuration = mediaFile.isTypicalMovieDuration();
        analysis.isTypicalEpisodeDuration = mediaFile.isTypicalEpisodeDuration();
        analysis.hasMultipleAudio = mediaFile.hasMultipleAudioTracks;
        analysis.hasSubtitles = mediaFile.hasEmbeddedSubtitles;
        analysis.isWidescreen = mediaFile.isWidescreen();
        
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
     * Enhanced episode detection using multiple methods
     */
    private EpisodeDetection detectEpisodeInfo(String filename, PathAnalysis pathAnalysis) {
        EpisodeDetection detection = new EpisodeDetection();
        
        // Try existing EpisodeDetector
        Optional<EpisodeDetector.EpisodeInfo> episodeInfo = EpisodeDetector.detect(filename);
        if (episodeInfo.isPresent()) {
            EpisodeDetector.EpisodeInfo info = episodeInfo.get();
            detection.season = info.season;
            detection.episode = info.episode;
            detection.titleHint = info.titleHint;
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "EpisodeDetector";
        }
        
        // Try SmartShowNameDetector for more comprehensive analysis
        if (!detection.hasEpisodePattern) {
            Optional<SmartShowNameDetector.ShowInfo> showInfo = SmartShowNameDetector.detectFromFilename(filename);
            if (showInfo.isPresent()) {
                SmartShowNameDetector.ShowInfo info = showInfo.get();
                detection.season = info.season;
                detection.episode = info.episode;
                detection.titleHint = info.titleHint;
                detection.hasEpisodePattern = true;
                detection.detectionMethod = "SmartShowNameDetector";
                detection.confidence = info.confidence;
            }
        }
        
        // Check path structure for episode indicators
        if (!detection.hasEpisodePattern && pathAnalysis.hasSeasonFolder) {
            detection.hasSeasonFolder = true;
            // Try to extract season number from folder name
            detection.season = extractSeasonFromFolder(pathAnalysis.parentFolder);
            detection.detectionMethod = "PathStructure";
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
        double confidence = Math.max(episodeScore, movieScore) / (episodeScore + movieScore);
        
        return new MediaTypeDecision(finalType, confidence, reasoning.toString());
    }
    
    /**
     * Extracts the best possible show name
     */
    private String extractShowName(String rawShowName, String filename, PathAnalysis pathAnalysis, 
                                  EpisodeDetection episodeDetection) {
        
        // Try SmartShowNameDetector on folder names
        if (pathAnalysis.hasSeasonFolder && !pathAnalysis.grandParentFolder.isEmpty()) {
            Optional<String> folderShowName = SmartShowNameDetector.detectFromFolder(pathAnalysis.grandParentFolder);
            if (folderShowName.isPresent()) {
                return folderShowName.get();
            }
        }
        
        if (!pathAnalysis.parentFolder.isEmpty()) {
            Optional<String> folderShowName = SmartShowNameDetector.detectFromFolder(pathAnalysis.parentFolder);
            if (folderShowName.isPresent()) {
                return folderShowName.get();
            }
        }
        
        // Try SmartShowNameDetector on filename
        Optional<SmartShowNameDetector.ShowInfo> filenameShowInfo = SmartShowNameDetector.detectFromFilename(filename);
        if (filenameShowInfo.isPresent()) {
            return filenameShowInfo.get().cleanName;
        }
        
        // Fall back to raw show name with cleaning
        if (rawShowName != null && !rawShowName.trim().isEmpty()) {
            return SmartShowNameDetector.cleanShowNameStatic(rawShowName);
        }
        
        // Last resort: try to extract from parent folder
        if (!pathAnalysis.parentFolder.isEmpty()) {
            return SmartShowNameDetector.cleanShowNameStatic(pathAnalysis.parentFolder);
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
            return SmartShowNameDetector.cleanTitleHintStatic(episodeDetection.titleHint);
        }
        
        // Use raw title if available
        if (rawTitle != null && !rawTitle.trim().isEmpty()) {
            return SmartShowNameDetector.cleanTitleHintStatic(rawTitle);
        }
        
        // Extract from filename
        String filenameWithoutExt = filename.replaceFirst("\\.[^.]+$", "");
        
        // Remove episode patterns
        if ("episode".equals(mediaType)) {
            for (Pattern pattern : TV_SHOW_PATTERNS) {
                filenameWithoutExt = pattern.matcher(filenameWithoutExt).replaceAll("").trim();
            }
        }
        
        // Use SmartShowNameDetector for final cleaning
        return SmartShowNameDetector.cleanShowNameStatic(filenameWithoutExt);
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