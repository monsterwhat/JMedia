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
    
    // --- COMPILED PATTERNS FOR PERFORMANCE ---
    
    // Episode detection patterns
    private static final Pattern EPISODE_SXXEXX = Pattern.compile("(?i)(.*?)[\\s\\._-]*[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})(.*)");
    private static final Pattern EPISODE_SXXEXX_PREFIX = Pattern.compile("(?i)(.*?)[sS](\\d{1,2})[\\s\\._-]*[eE](\\d{1,3})");
    private static final Pattern EPISODE_XXY = Pattern.compile("(?i)(.*?)(\\b[0-3]?\\d)[x×]([0-1]?\\d{1,2}\\b)(.*)");
    private static final Pattern EPISODE_ONLY = Pattern.compile("(?i)(.*?)[eE]pisode[\\s\\._-]*(\\d{1,3})(.*)");
    private static final Pattern EPISODE_SIMPLE = Pattern.compile("(?i)(.*?)[\\s\\._-]+(\\d{1,3})(\\s*v\\d+)?\\.[^.]+$");
    
    // Season/Folder patterns
    private static final Pattern SEASON_FOLDER_PATTERN = Pattern.compile("(?i)season[s]?[-_.]?(\\d+)");
    private static final Pattern SEASON_NAME_PATTERN = Pattern.compile("(?i)^s\\d{1,3}$");
    private static final Pattern SEASON_EMBEDDED_PATTERN = Pattern.compile("(?i).*\\.s\\d{1,3}.*");
    private static final Pattern SEASON_WORD_PATTERN = Pattern.compile("(?i)(season|libro|temporada|book|volume|chapter|episode)[s]?[-_. ]?\\d{1,3}.*");
    private static final Pattern SPECIALS_PATTERN = Pattern.compile("(?i)(specials?)");
    
    // TV Show detection patterns (used for cleaning titles)
    private static final List<Pattern> TV_SHOW_PATTERNS = Arrays.asList(
        Pattern.compile("(?i)season.*\\d+.*episode.*\\d+"),
        Pattern.compile("(?i)s\\d+.*e\\d+"),
        Pattern.compile("(?i)\\d+x\\d+"),
        Pattern.compile("(?i)episode.*\\d+"),
        Pattern.compile("(?i)part.*\\d+")
    );
    
    // Technical terms regex string (used in composite patterns)
    private static final String RELEASE_TERMS = "720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|yts|imax|hybrid|remastered|extended|collector|ultimate|repack|x264|x265|hevc|aac|ac3|dts|ddp|5\\s*[\\.\\s]?1|7\\s*[\\.\\s]?1|yts\\.mx|yts\\.am|dual-audio|multi-audio|tri-audio|multi-subs|10bit|rarbg|ettv|shaanig|nitro|fgt|ozlem|juggs|axxo|klaxxon|vostfr|amzn|galaxytv|tgx|rzerox|mazar|pahe\\.in|800mb|proper|uncut|unrated|directors?.cut|nf|galaxyty";
    
    // Compiled cleaning patterns
    private static final Pattern RELEASE_CLEAN_PATTERN = Pattern.compile("(?i)\\b(" + RELEASE_TERMS + ")($|\\s)");
    
    // Metadata in brackets/parens (Non-greedy, respects boundaries)
    // Matches: [720p], (1080p x265), [ReleaseGroup 720p]
    // Does NOT match: (US), [Special] unless they contain a technical term
    private static final Pattern METADATA_BRACKETS_PATTERN = Pattern.compile("(?i)(\\[[^\\]]*?(" + RELEASE_TERMS + ")[^\\]]*?\\]|\\([^\\)]*?(" + RELEASE_TERMS + ")[^\\)]*?\\))");
    
    private static final Pattern LEADING_BRACKETS_PATTERN = Pattern.compile("(?i)^[\\[\\(].*?[\\]\\)]\\s*");
    private static final Pattern TRAILING_BRACKETS_PATTERN = Pattern.compile("(?i)\\s*[\\[\\(].*?[\\]\\)]$");
    private static final Pattern LEADING_SEQUENCE_PATTERN = Pattern.compile("^\\d+[\\s\\.\\-_]+");
    private static final Pattern YEAR_PAREN_PATTERN = Pattern.compile("\\((19|20)\\d{2}\\)");
    private static final Pattern YEAR_STANDALONE_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    
    // Splitters
    private static final Pattern SEASON_SPLIT_PATTERN = Pattern.compile("(?i)\\s+season\\s*\\d+|\\s+S\\d{2,}|\\.s\\d{2,}(?=\\.|\\s|$)|\\s+series\\s*\\d+");
    private static final Pattern COMPLETE_SPLIT_PATTERN = Pattern.compile("(?i)\\s+complete|\\.complete");
    private static final Pattern SPECIALS_SPLIT_PATTERN = Pattern.compile("(?i)\\s+specials?|\\.specials?");
    
    // Indicators removal
    private static final Pattern SEASON_EPISODE_REMOVE_PATTERN = Pattern.compile("(?i)\\b(season|series|episode|e|part|temporada|tome|volume|vol)[s]?\\s*[\\d\\-]+");
    private static final Pattern STANDALONE_SXX_PATTERN = Pattern.compile("(?i)\\bS\\d{1,2}\\b");
    
    // Cleanup
    private static final Pattern CLEANUP_CHARS_PATTERN = Pattern.compile("[._\\-\\[\\]\\(\\)]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    
    // Filename-like check
    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("(?i)\\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|mpg|mpeg)$");

    // Quality indicators that suggest movies
    private static final List<String> MOVIE_QUALITY_INDICATORS = Arrays.asList(
        "bluray", "bdrip", "dvdrip", "web-dl", "webrip", "hdcam", "ts", "tc", "yts", "yify", "imax"
    );
    
    // TV show indicators in filenames
    private static final List<String> TV_SHOW_INDICATORS = Arrays.asList(
        "season", "episode", "ep", "series", "show", "complete", "hdtv"
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
        String finalShowName = extractShowName(rawShowName, filename, pathAnalysis, episodeDetection, relativePath);

        // Extract title using enhanced methods
        String finalTitle = extractTitle(rawTitle, filename, episodeDetection, mediaTypeDecision.type, pathAnalysis, finalShowName);
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

        // Get actual path segments with original case
        String[] pathSegments = relativePath.replace('\\', '/').split("/");
        String[] pathPartsLower = relativePath.toLowerCase().replace('\\', '/').split("/");

        // Scan all path segments for explicit "Movies" or "TV Shows" indicators
        // This takes high precedence as users often organize libraries this way
        String fullPathLower = relativePath.toLowerCase().replace('\\', '/');

        for (int i = 0; i < pathPartsLower.length; i++) {
            String segment = pathPartsLower[i];
            String originalSegment = i < pathSegments.length ? pathSegments[i] : "";

            // Check for explicit "Movies" or "TV Shows" in folder names
            if (segment.equals("movies") || segment.equals("movie") || segment.equals("films") || segment.equals("film") || 
                segment.startsWith("movie ") || segment.endsWith(" movies") || segment.contains(" movie ")) {
                analysis.directoryTypeHint = "movie";
                analysis.libraryRootIndex = i;
                analysis.libraryRootFolder = originalSegment;
            }
            if (segment.equals("tv shows") || segment.equals("tv") || segment.equals("shows") || segment.equals("tvshow") || 
                segment.equals("tvseries") || segment.equals("series") || segment.startsWith("tv ") || 
                segment.contains("tv show") || segment.contains("tv serie")) {
                analysis.directoryTypeHint = "episode";
                analysis.libraryRootIndex = i;
                analysis.libraryRootFolder = originalSegment;
            }
        }

        // Final check on full path for common patterns if no segment matched perfectly
        if (analysis.directoryTypeHint == null) {
            if (fullPathLower.contains("/tv shows/") || fullPathLower.contains("/tv-shows/") || 
                fullPathLower.contains("/tv_shows/") || fullPathLower.contains("/tvseries/")) {
                analysis.directoryTypeHint = "episode";
            }
        }

        // NEW: Identify explicit show folder and season folder from path structure
        if (analysis.libraryRootIndex != -1 && pathSegments.length > analysis.libraryRootIndex + 1) {
            // Show folder is the segment after "TV Shows"
            int showIdx = analysis.libraryRootIndex + 1;
            if (showIdx < pathSegments.length - 1) { // -1 to exclude filename
                analysis.showFolderIndex = showIdx;
                analysis.showFolder = pathSegments[showIdx];
                
                // Check if next segment is a season folder
                int seasonIdx = showIdx + 1;
                if (seasonIdx < pathSegments.length - 1) {
                    String potentialSeason = pathSegments[seasonIdx];
                    if (isSeasonFolderName(potentialSeason)) {
                        analysis.seasonFolderIndex = seasonIdx;
                        analysis.seasonFolder = potentialSeason;
                    }
                }
            }
        }

        // Check for season folder patterns
        analysis.hasSeasonFolder = isSeasonFolderName(analysis.parentFolder) || 
                                    SEASON_EMBEDDED_PATTERN.matcher(analysis.parentFolder).matches() ||
                                    SEASON_WORD_PATTERN.matcher(analysis.parentFolder).matches() ||
                                    analysis.seasonFolder != null; // explicit season folder

        // Check for TV show indicators in path
        for (String indicator : Arrays.asList("season", "series", "episode", "tvshow", "libro", "temporada", "complete", "specials")) {
            if (fullPathLower.contains(indicator)) {
                analysis.hasTvIndicator = true;
                break;
            }
        }

        // Check for movie folder patterns
        analysis.hasMovieFolderPattern = YEAR_PAREN_PATTERN.matcher(analysis.parentFolder).find() ||
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
            // Check for whole words to avoid false positives (e.g. "it.is.a.trap")
            if (filenameLower.contains("." + indicator + ".") || 
                filenameLower.contains("-" + indicator + "-") || 
                filenameLower.contains(" " + indicator + " ") ||
                filenameLower.contains("[" + indicator + "]") ||
                filenameLower.contains("(" + indicator + ")")) {
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

        // 1. S01E01 Pattern - Enhanced to capture show name prefix
        Matcher m1 = EPISODE_SXXEXX.matcher(filename);
        if (m1.matches()) {
            String prefix = m1.group(1).trim();
            detection.season = Integer.parseInt(m1.group(2));
            detection.episode = Integer.parseInt(m1.group(3));
            String hint = m1.group(4).trim();
            
            if (!prefix.isEmpty()) {
                detection.showNameHint = cleanShowName(prefix);
            }
            
            // Remove extension and common technical tags from hint
            String hintClean = hint.replaceFirst("\\.[^.]+$", "");
            if (hintClean.matches("(?i)^[\\s\\._-]*(720p|1080p|2160p|4k|nf|webrip|x264|x265|hevc|galaxytg|galaxyty|hdtv|bluray).*")) {
                detection.titleHint = null;
            } else {
                detection.titleHint = hintClean.trim();
            }
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "SxxExx";
            detection.confidence = 0.9;
            return detection;
        }

        // 2. 1x01 Pattern (Ensure it doesn't match years like 1993)
        // We look for numbers separated by x that are small (usually < 50 for season, < 100 for episode)
        Matcher m2 = EPISODE_XXY.matcher(filename);
        if (m2.matches()) {
            detection.season = Integer.parseInt(m2.group(2));
            detection.episode = Integer.parseInt(m2.group(3));
            String hint = m2.group(4).trim();
            detection.titleHint = hint.replaceFirst("\\.[^.]+$", "");
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "XxY";
            detection.confidence = 0.8;
            return detection;
        }
        // 3. "Episode 01" Pattern
        Matcher m3 = EPISODE_ONLY.matcher(filename);
        if (m3.matches()) {
            detection.season = null;
            detection.episode = Integer.parseInt(m3.group(2));
            String hint = m3.group(3).trim();
            detection.titleHint = hint.replaceFirst("\\.[^.]+$", "");
            detection.hasEpisodePattern = true;
            detection.detectionMethod = "EpisodeOnly";
            detection.confidence = 0.7;
            return detection;
        }

        // 4. " - 01" or " 01" Pattern (Common in anime/simple sets)
        // We look for a number at the end or preceded by a dash/space, not part of a year
        Matcher m4 = EPISODE_SIMPLE.matcher(filename);
        if (m4.matches()) {
            int num = Integer.parseInt(m4.group(2));
            // Basic sanity check: if it's not a year
            if (num > 0 && num < 1000) {
                detection.season = null;
                detection.episode = num;
                detection.hasEpisodePattern = true;
                detection.detectionMethod = "SimpleNumber";
                detection.confidence = 0.6;
                return detection;
            }
        }

        // Check path structure for episode indicators
        if (pathAnalysis.hasSeasonFolder || pathAnalysis.seasonFolder != null) {
            detection.hasSeasonFolder = true;
            // Use explicit season folder from path structure if available
            if (pathAnalysis.seasonFolder != null) {
                detection.season = extractSeasonFromFolder(pathAnalysis.seasonFolder);
                detection.detectionMethod = "PathStructure";
            } else {
                detection.season = extractSeasonFromFolder(pathAnalysis.parentFolder);
                detection.detectionMethod = "PathStructure";
            }
            detection.confidence = 0.7; // Higher confidence with explicit season folder
        }

        // NEW: If no season detected but we have explicit show folder (direct episodes under show folder)
        // Default to season 1
        if (detection.season == null && pathAnalysis.showFolder != null && !pathAnalysis.hasSeasonFolder) {
            detection.season = 1; // Default to season 1 for direct episodes
            detection.detectionMethod = "PathStructureDefault";
            detection.confidence = 0.5;
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
            movieScore += 3.0;
            reasoning.append("Directory hint: Movie (+3.0); ");
        } else if ("episode".equals(pathAnalysis.directoryTypeHint)) {
            episodeScore += 3.0;
            reasoning.append("Directory hint: TV Show (+3.0); ");
        }

        // Start with raw detection
        if ("episode".equals(rawMediaType)) {
            episodeScore += 0.4;
            reasoning.append("Raw detection: episode (+0.4); ");
        } else if ("movie".equals(rawMediaType)) {
            movieScore += 0.4;
            reasoning.append("Raw detection: movie (+0.4); ");
        }

        // Episode pattern analysis (VERY STRONG indicator)
        if (episodeDetection.hasEpisodePattern) {
            episodeScore += 1.5;
            reasoning.append("Episode pattern found (+1.5); ");
        }

        // Season folder analysis (STRONG indicator)
        if (pathAnalysis.hasSeasonFolder) {
            episodeScore += 1.2;
            reasoning.append("Season folder structure (+1.2); ");
        }

        // Duration analysis
        if (techAnalysis.isTypicalMovieDuration && !techAnalysis.isTypicalEpisodeDuration) {
            movieScore += 0.3;
            reasoning.append("Duration suggests movie (+0.3); ");
        } else if (techAnalysis.isTypicalEpisodeDuration && !techAnalysis.isTypicalMovieDuration) {
            episodeScore += 0.3;
            reasoning.append("Duration suggests episode (+0.3); ");
        } else if (techAnalysis.isTypicalEpisodeDuration && techAnalysis.isTypicalMovieDuration) {
             // Overlapping duration, minor nudge to movie if it's long, but don't penalize episodes
             if (techAnalysis.duration > 3600) { // > 1 hour
                 movieScore += 0.1;
                 reasoning.append("Overlapping duration, slightly long (+0.1 movie); ");
             }
        }

        // Quality indicators
        if (techAnalysis.hasMovieQualityIndicator) {
            movieScore += 0.2;
            reasoning.append("Movie quality indicator (+0.2); ");
        }

        // Path indicators
        if (pathAnalysis.hasTvIndicator) {
            episodeScore += 0.3;
            reasoning.append("TV indicator in path (+0.3); ");
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
            movieScore += 0.05; // Reduced impact
            reasoning.append("Widescreen format (+0.05 movie); ");
        }

        String finalType = episodeScore >= movieScore ? "episode" : "movie";
        double confidence = Math.abs(episodeScore - movieScore) / (episodeScore + movieScore + 0.1);

        return new MediaTypeDecision(finalType, confidence, reasoning.toString());
    }
    /**
     * Extracts the best possible show name
     */
    private String extractShowName(String rawShowName, String filename, PathAnalysis pathAnalysis, 
                                  EpisodeDetection episodeDetection, String relativePath) {
        
        // 0. NEW: Use explicit showFolder from path structure (BEST priority)
        // This is set when we have: TV Shows/ShowName/... or TV Shows/ShowName/Season X/...
        if (pathAnalysis.showFolder != null && !pathAnalysis.showFolder.isEmpty()) {
            // CRITICAL: Ensure the show folder itself isn't a season folder
            if (!isSeasonFolderName(pathAnalysis.showFolder)) {
                String showFromPath = cleanShowName(pathAnalysis.showFolder);
                if (!showFromPath.equals("Unknown Show") && showFromPath.length() > 1) {
                    return showFromPath;
                }
            }
        }
        
        // 0b. Use hint from episode detection (filename prefix) if high confidence
        if (episodeDetection.showNameHint != null && !episodeDetection.showNameHint.equals("Unknown Show")) {
            return episodeDetection.showNameHint;
        }

        // 1. STRONGEST HINT: If we know where the "TV Shows" folder is, the next folder is usually the show name
        if ("episode".equals(pathAnalysis.directoryTypeHint) && pathAnalysis.libraryRootIndex != -1) {
            Path path = Paths.get(relativePath);
            int showFolderIndex = pathAnalysis.libraryRootIndex + 1;
            
            if (showFolderIndex < path.getNameCount() - 1) { // -1 to avoid the filename itself
                String folderName = path.getName(showFolderIndex).toString();
                
                // CRITICAL FIX: If the next folder is a season folder (e.g. "TV Shows/Season 1/"),
                // then "Season 1" is NOT the show name.
                if (isSeasonFolderName(folderName)) {
                    // Check if grandparent folder exists and is a valid show name
                    int grandParentIndex = showFolderIndex + 1;
                    if (grandParentIndex < path.getNameCount() - 1) {
                        String grandParentName = path.getName(grandParentIndex).toString();
                        if (!isSeasonFolderName(grandParentName)) {
                            String gpShowName = cleanShowName(grandParentName);
                            if (!gpShowName.equals("Unknown Show")) {
                                return gpShowName;
                            }
                        }
                    }
                    // Try to get show name from the filename or raw data
                    if (rawShowName != null && !rawShowName.trim().isEmpty()) {
                        String cleaned = cleanShowName(rawShowName);
                        if (!cleaned.equals("Unknown Show")) {
                            return cleaned;
                        }
                    }
                    // Or from the part of the filename before SxxExx
                    Matcher m = EPISODE_SXXEXX_PREFIX.matcher(filename);
                    if (m.find()) {
                        String name = cleanShowName(m.group(1));
                        if (!name.equals("Unknown Show") && name.length() > 1) return name;
                    }
                } else {
                    String folderShowName = cleanShowName(folderName);
                    if (!folderShowName.equals("Unknown Show")) {
                        return folderShowName;
                    }
                }
            }
        }

        // 2. If we have a grandparent folder and parent is a season/metadata folder, grandparent is likely the show name
        if (!pathAnalysis.grandParentFolder.isEmpty()) {
            String parentLower = pathAnalysis.parentFolder.toLowerCase();
            boolean parentIsMetadata = isSeasonFolderName(parentLower) || 
                                     parentLower.contains("complete") || 
                                     SEASON_EMBEDDED_PATTERN.matcher(parentLower).matches() ||
                                     parentLower.contains("720p") || 
                                     parentLower.contains("1080p") ||
                                     parentLower.contains("galaxytv");
                                     
            if (parentIsMetadata || pathAnalysis.hasSeasonFolder) {
                // First try grandparent folder
                String folderShowName = cleanShowName(pathAnalysis.grandParentFolder);
                if (!folderShowName.equals("Unknown Show") && 
                    !folderShowName.equalsIgnoreCase("tv shows") && 
                    !folderShowName.equalsIgnoreCase("shows")) {
                    return folderShowName;
                }
                
                // If grandparent didn't work, try cleaning parent folder
                // This handles cases like "Archer (2009) Season 9 S09" where show+season are combined
                if (!pathAnalysis.parentFolder.isEmpty()) {
                    String cleanedParent = cleanShowName(pathAnalysis.parentFolder);
                    if (!cleanedParent.equals("Unknown Show") && 
                        !cleanedParent.equalsIgnoreCase("tv shows") && 
                        !cleanedParent.equalsIgnoreCase("shows") &&
                        !cleanedParent.equalsIgnoreCase("movies") &&
                        cleanedParent.length() > 1) {
                        return cleanedParent;
                    }
                }
            }
        }
        
        // 3. Extract from filename if it has SxxExx pattern (the part before SxxExx)
        Matcher m = EPISODE_SXXEXX_PREFIX.matcher(filename);
        if (m.find()) {
            String nameFromFilename = cleanShowName(m.group(1));
            if (!nameFromFilename.equals("Unknown Show") && nameFromFilename.length() > 1) {
                return nameFromFilename;
            }
        }
        
        // 4. If parent folder is not a season folder and not generic, it might be the show name
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
        
        // 5. Fall back to raw show name
        if (rawShowName != null && !rawShowName.trim().isEmpty()) {
            String cleaned = cleanShowName(rawShowName);
            if (!cleaned.equals("Unknown Show")) {
                return cleaned;
            }
        }
        
        return "Unknown Show";
    }

    private boolean isSeasonFolderName(String name) {
        if (name == null || name.isEmpty()) return false;
        
        // Match "S1", "S01", "S001" format (exact or embedded)
        if (SEASON_NAME_PATTERN.matcher(name).matches()) return true;
        if (SEASON_EMBEDDED_PATTERN.matcher(name).matches()) return true;  // Embedded like .S01.
        
        // Match patterns like "Season 1", "Season 01", "Temporada 2", "Libro 1", "Volume 3"
        if (SEASON_WORD_PATTERN.matcher(name).matches()) return true;
        
        // Match standalone numbers that are likely seasons (e.g., folder named "1" or "01")
        // But only if it looks like a reasonable season number (1-999)
        if (name.matches("^\\d{1,3}$")) {
            try {
                int num = Integer.parseInt(name);
                return num >= 1 && num <= 999;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        
        // Match "1x01" style (anime/foreign series format)
        if (name.matches("(?i)\\d+[xX]\\d+.*")) return true;
        
        // Match "Specials" folder
        if (SPECIALS_PATTERN.matcher(name).matches()) return true;
        
        return false;
    }

    /**
     * Extracts the best possible title
     */
    private String extractTitle(String rawTitle, String filename, EpisodeDetection episodeDetection, 
                               String mediaType, PathAnalysis pathAnalysis, String showName) {
        
        // 1. If it's a movie and the filename is generic, use the parent folder
        if ("movie".equals(mediaType)) {
            String filenameTitle = cleanTitle(filename.replaceFirst("\\.[^.]+$", ""));
            if (isGenericName(filenameTitle) && !pathAnalysis.parentFolder.isEmpty()) {
                return cleanTitle(pathAnalysis.parentFolder);
            }
            return filenameTitle;
        }

        // 2. Use episode title hint if available
        if (episodeDetection.titleHint != null && !episodeDetection.titleHint.trim().isEmpty()) {
            String cleanedHint = cleanTitle(episodeDetection.titleHint);
            // If the cleaned hint is very short or just metadata we missed, ignore it
            if (!cleanedHint.equalsIgnoreCase("Unknown Title") && cleanedHint.length() > 1) {
                return cleanedHint;
            }
        }
        
        // 3. If filename is generic and it's an episode, check if the parent folder is the episode title
        // (But only if the parent isn't a "Season X" or generic folder)
        if ("episode".equals(mediaType) && isGenericName(filename.replaceFirst("\\.[^.]+$", ""))) {
            if (!pathAnalysis.parentFolder.isEmpty() && !pathAnalysis.hasSeasonFolder) {
                String parentTitle = cleanTitle(pathAnalysis.parentFolder);
                if (!parentTitle.equalsIgnoreCase(showName) && !isGenericName(parentTitle)) {
                    return parentTitle;
                }
            }
        }
        
        // 4. Extract from filename
        String filenameWithoutExt = filename.replaceFirst("\\.[^.]+$", "");
        
        // Remove episode patterns
        for (Pattern pattern : TV_SHOW_PATTERNS) {
            filenameWithoutExt = pattern.matcher(filenameWithoutExt).replaceAll("").trim();
        }
        
        String finalTitle = cleanTitle(filenameWithoutExt);
        
        // 5. Special logic for episodes to avoid redundancy
        if ("episode".equals(mediaType)) {
            // If we have no show name yet, don't return null
            if (showName == null || showName.equals("Unknown Show")) {
                return finalTitle;
            }

            // If the title is just the show name or generic, return null to let the creation service 
            // use the "Show - SxxExx" fallback which looks better
            if (finalTitle.equalsIgnoreCase(showName) || isGenericName(finalTitle)) {
                return null; 
            }
            
            // If the title is very long and contains the show name, it's likely just the filename again
            if (finalTitle.length() > 20 && finalTitle.toLowerCase().contains(showName.toLowerCase())) {
                return null;
            }
        }
        
        return finalTitle.equalsIgnoreCase("Unknown Title") ? null : finalTitle;
    }

    private boolean isGenericName(String name) {
        if (name == null || name.isEmpty()) return true;
        String lower = name.toLowerCase();
        return lower.equals("movie") || lower.equals("video") || lower.equals("mkv") || 
               lower.equals("mp4") || lower.equals("avi") || lower.equals("m4v") || lower.matches("\\d+") ||
               lower.equals("vid") || lower.equals("film") || lower.equals("unknown title");
    }
    
    /**
     * Extracts year from various sources
     */
    private Integer extractYear(Integer rawYear, String filename, PathAnalysis pathAnalysis) {
        if (rawYear != null) {
            return rawYear;
        }
        
        // Try to extract from filename
        Matcher matcher = YEAR_STANDALONE_PATTERN.matcher(filename);
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
            matcher = YEAR_STANDALONE_PATTERN.matcher(part);
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
    public static String cleanShowName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Unknown Show";
        }
        
        String cleaned = name;

        // 0. First, split on "Season" or "S##" if present
        Matcher seasonMatcher = SEASON_SPLIT_PATTERN.matcher(cleaned);
        if (seasonMatcher.find()) {
            String[] parts = SEASON_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        // Handle "The Complete Series" or "Complete"
        Matcher completeMatcher = COMPLETE_SPLIT_PATTERN.matcher(cleaned);
        if (completeMatcher.find()) {
            String[] parts = COMPLETE_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        // Handle "Specials"
        Matcher specialsMatcher = SPECIALS_SPLIT_PATTERN.matcher(cleaned);
        if (specialsMatcher.find()) {
            String[] parts = SPECIALS_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        // 1. Remove leading release groups in brackets
        cleaned = LEADING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = TRAILING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");

        // 2. Remove metadata inside brackets/parentheses using non-greedy pattern
        cleaned = METADATA_BRACKETS_PATTERN.matcher(cleaned).replaceAll(" ");
        
        // 3. Remove leading sequence numbers
        cleaned = LEADING_SEQUENCE_PATTERN.matcher(cleaned).replaceAll("");

        // 4. Remove year patterns
        cleaned = YEAR_PAREN_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_STANDALONE_PATTERN.matcher(cleaned).replaceAll("");

        // 5. Remove quality and release indicators
        cleaned = RELEASE_CLEAN_PATTERN.matcher(cleaned).replaceAll(" ");
        
        // 6. Remove season/episode patterns
        cleaned = SEASON_EPISODE_REMOVE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = STANDALONE_SXX_PATTERN.matcher(cleaned).replaceAll(" ");
        
        // 7. Remove special characters and clean up
        cleaned = CLEANUP_CHARS_PATTERN.matcher(cleaned).replaceAll(" ").trim();
        
        // 8. Remove extra spaces and capitalize properly
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = toTitleCase(cleaned.trim());
        
        // If it was just metadata, it might be empty now
        if (cleaned.isEmpty()) {
             return "Unknown Show";
        }
        
        // NEW: Check if the result looks like a filename (extension)
        if (FILE_EXTENSION_PATTERN.matcher(cleaned).find()) {
            return "Unknown Show";
        }
        
        // NEW: Check if the result is just a technical term that got missed or a single letter
        if (cleaned.length() < 2 || cleaned.matches("(?i)^(mkv|mp4|avi|show|series|season)$")) {
            return "Unknown Show";
        }
        
        return cleaned;
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
        cleaned = LEADING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");

        // 2. Remove metadata in brackets/parentheses
        cleaned = METADATA_BRACKETS_PATTERN.matcher(cleaned).replaceAll(" ");

        // 3. Remove leading sequence numbers
        cleaned = LEADING_SEQUENCE_PATTERN.matcher(cleaned).replaceAll("");

        // 4. Remove year
        cleaned = YEAR_PAREN_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_STANDALONE_PATTERN.matcher(cleaned).replaceAll("");
        
        // 5. Remove quality and release indicators
        cleaned = RELEASE_CLEAN_PATTERN.matcher(cleaned).replaceAll(" ");
        
        // 7. Clean up separators and special characters
        cleaned = CLEANUP_CHARS_PATTERN.matcher(cleaned).replaceAll(" ").trim();
        
        // 8. Remove extra spaces
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        
        String result = toTitleCase(cleaned.trim());
        if (result.isEmpty()) {
            return "Unknown Title";
        }
        return result;
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
        Matcher matcher = SEASON_FOLDER_PATTERN.matcher(folderName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Normalizes show name for comparison/merge detection
     * "Archer (2009)" -> "archer", "Archer2009" -> "archer"
     */
    private static String normalizeShowNameForComparison(String name) {
        if (name == null || name.isEmpty()) return "";
        
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]", "")      // Remove all punctuation/spaces
            .replaceAll("\\d{4}", "");       // Remove 4-digit years
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
        public int libraryRootIndex = -1; // Index of "TV Shows" or "Movies" in path
        
        // NEW: Explicit path structure for TV shows
        public String libraryRootFolder;   // "TV Shows" or "Movies"
        public String showFolder;         // The show directory (e.g., "Archer")
        public String seasonFolder;       // Season folder if exists (e.g., "Season 1")
        public int showFolderIndex = -1;  // Index of show folder in path
        public int seasonFolderIndex = -1; // Index of season folder in path
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
        public String showNameHint;
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