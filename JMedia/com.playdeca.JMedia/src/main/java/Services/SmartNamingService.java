package Services;

import Models.MediaFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
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
    private static final Pattern SEASON_WORD_PATTERN = Pattern.compile("(?i)(season|libro|temporada|book|volume|chapter|episode|arco|saga|tome)[s]?[-_. ]?\\d{1,3}.*");
    private static final Pattern SPECIALS_PATTERN = Pattern.compile("(?i)(specials?)");
    
    // NEW: Additional season patterns
    private static final Pattern SERIES_FOLDER_PATTERN = Pattern.compile("(?i)series\\s*(\\d+)");
    private static final Pattern TEMPORADA_SHORT_PATTERN = Pattern.compile("(?i)^t(\\d{1,3})$");
    private static final Pattern SEASON_COMBINED_PATTERN = Pattern.compile("(?i)(?:season|temporada|libro|book|volume|chapter|arco|saga|tome)[s]?[-_. ]?(\\d+).*?[sS](\\d{2,})");
    private static final Pattern SEASON_SXX_ONLY_PATTERN = Pattern.compile("(?i).*?[\\s\\._-][sS](\\d{1,3})[\\s\\._-].*");
    
    // TV Show detection patterns (used for cleaning titles)
    private static final List<Pattern> TV_SHOW_PATTERNS = Arrays.asList(
        Pattern.compile("(?i)season.*\\d+.*episode.*\\d+"),
        Pattern.compile("(?i)s\\d+.*e\\d+"),
        Pattern.compile("(?i)\\d+x\\d+"),
        Pattern.compile("(?i)episode.*\\d+"),
        Pattern.compile("(?i)part.*\\d+")
    );
    
    // Technical terms regex string (used in composite patterns)
    private static final String RELEASE_TERMS = "720p|1080p|2160p|4k|480p|360p|576p|bluray|bdrip|dvdrip|web-dl|webrip|re-?webrip|hdtv|yts|imax|hybrid|remastered|extended|collector|ultimate|repack|x264|x265|hevc|aac|ac3|dts|ddp|5\\s*[\\.\\s]?1|7\\s*[\\.\\s]?1|yts\\.mx|yts\\.am|dual-audio|multi-audio|tri-audio|multi-subs|10bit|rarbg|ettv|shaanig|nitro|fgt|ozlem|juggs|axxo|klaxxon|vostfr|amzn|galaxytv|tgx|rzerox|mazar|pahe\\.in|800mb|proper|uncut|unrated|directors?.cut|nf|galaxyty|complete|eztv\\.re|kontrast|elite|hodl|galaxyty|galaxy-tv|galaxy_tv|re-?enc|h264|h265|avc|hevc";
    
    // Compiled cleaning patterns
    private static final Pattern RELEASE_CLEAN_PATTERN = Pattern.compile("(?i)\\b(" + RELEASE_TERMS + ")($|[\\s\\._-])");
    
    // Metadata in brackets/parens (Non-greedy, respects boundaries)
    // Matches: [720p], (1080p x265), [ReleaseGroup 720p], (360p re-webrip)
    // Does NOT match: (US), [Special] unless they contain a technical term or arc indicator
    private static final Pattern METADATA_BRACKETS_PATTERN = Pattern.compile("(?i)(\\[[^\\]]*?(" + RELEASE_TERMS + "|arco|saga|season|temporada)[^\\]]*?\\]|\\([^\\)]*?(" + RELEASE_TERMS + "|arco|saga|season|temporada)[^\\)]*?\\))");
    
    // Additional pattern for brackets that ONLY contain resolution/quality (no other meaningful content)
    private static final Pattern QUALITY_ONLY_BRACKETS_PATTERN = Pattern.compile("(?i)^\\([\\d]p\\s*[a-z-]*\\)$");
    
    private static final Pattern LEADING_BRACKETS_PATTERN = Pattern.compile("(?i)^[\\[\\(].*?[\\]\\)]\\s*");
    private static final Pattern TRAILING_BRACKETS_PATTERN = Pattern.compile("(?i)\\s*[\\[\\(].*?[\\]\\)]$");
    private static final Pattern LEADING_SEQUENCE_PATTERN = Pattern.compile("^(?:\\d+[\\s\\.\\-_]*)+");
    private static final Pattern YEAR_PAREN_PATTERN = Pattern.compile("\\((19|20)\\d{2}[-)]\\)");
    private static final Pattern YEAR_STANDALONE_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    
    // Splitters - Split string to find potential show name before metadata
    private static final Pattern SEASON_SPLIT_PATTERN = Pattern.compile("(?i)\\s+(?:season|arco|saga|libro|book|volume|vol|tome)\\s*\\d+|\\s+S\\d{2,}|\\.s\\d{2,}(?=\\.|\\s|$)|\\s+series\\s*\\d+|\\s+t\\d{1,3}(?=\\s|$|\\))");
    private static final Pattern SEASON_RANGE_PATTERN = Pattern.compile("(?i)\\s+s\\d{1,2}\\s*-\\s*s\\d{1,2}|\\s*\\[\\d+-\\d+\\]|\\s*\\d+-\\d+");  // S01-S12 style ranges or [101-105] or 136-138
    private static final Pattern COMPLETE_SPLIT_PATTERN = Pattern.compile("(?i)\\s+complete|\\.complete");
    private static final Pattern SPECIALS_SPLIT_PATTERN = Pattern.compile("(?i)\\s+specials?|\\.specials?");
    private static final Pattern PLUS_MEDIA_PATTERN = Pattern.compile("(?i)\\s*\\+\\s*(movies?|spinoffs?|extras?|specials?|season).*");  // "+ Movies + Spinoffs"
    
    // Indicators removal - remove keywords from final string
    private static final Pattern SEASON_EPISODE_REMOVE_PATTERN = Pattern.compile("(?i)\\b(season|series|episode|e|part|temporada|tome|volume|vol|libro|book|chapter|arco|saga)[s]?\\s*[\\d\\-]+");
    private static final Pattern STANDALONE_SXX_PATTERN = Pattern.compile("(?i)\\bS\\d{1,2}\\b");
    private static final Pattern TEMPORADA_T_PATTERN = Pattern.compile("(?i)\\bt\\d{1,3}\\b");
    
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
        String fullPathLower = relativePath.toLowerCase().replace('\\', '/');

        for (int i = 0; i < pathPartsLower.length; i++) {
            String segment = pathPartsLower[i];
            String originalSegment = i < pathSegments.length ? pathSegments[i] : "";

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

        if (analysis.directoryTypeHint == null) {
            if (fullPathLower.contains("/tv shows/") || fullPathLower.contains("/tv-shows/") || 
                fullPathLower.contains("/tv_shows/") || fullPathLower.contains("/tvseries/")) {
                analysis.directoryTypeHint = "episode";
            }
        }

        // Identify explicit show folder and season folder from path structure
        if (analysis.libraryRootIndex != -1 && pathSegments.length > analysis.libraryRootIndex + 1) {
            int showIdx = analysis.libraryRootIndex + 1;
            if (showIdx < pathSegments.length - 1) {
                analysis.showFolderIndex = showIdx;
                analysis.showFolder = pathSegments[showIdx];
                
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
                                    analysis.seasonFolder != null;

        // Check for TV show indicators in path
        for (String indicator : Arrays.asList("season", "series", "episode", "tvshow", "libro", "temporada", "arco", "saga", "complete", "specials")) {
            if (fullPathLower.contains(indicator)) {
                analysis.hasTvIndicator = true;
                break;
            }
        }

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

        String filenameLower = filename.toLowerCase();
        for (String indicator : MOVIE_QUALITY_INDICATORS) {
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

        Matcher m1 = EPISODE_SXXEXX.matcher(filename);
        if (m1.matches()) {
            String prefix = m1.group(1).trim();
            detection.season = Integer.parseInt(m1.group(2));
            detection.episode = Integer.parseInt(m1.group(3));
            String hint = m1.group(4).trim();
            
            if (!prefix.isEmpty()) {
                detection.showNameHint = cleanShowName(prefix);
            }
            
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

        Matcher m4 = EPISODE_SIMPLE.matcher(filename);
        if (m4.matches()) {
            int num = Integer.parseInt(m4.group(2));
            if (num > 0 && num < 1000) {
                detection.season = null;
                detection.episode = num;
                detection.hasEpisodePattern = true;
                detection.detectionMethod = "SimpleNumber";
                detection.confidence = 0.6;
                return detection;
            }
        }

        if (pathAnalysis.hasSeasonFolder || pathAnalysis.seasonFolder != null) {
            detection.hasSeasonFolder = true;
            if (pathAnalysis.seasonFolder != null) {
                detection.season = extractSeasonFromFolder(pathAnalysis.seasonFolder);
                detection.detectionMethod = "PathStructure";
            } else {
                detection.season = extractSeasonFromFolder(pathAnalysis.parentFolder);
                detection.detectionMethod = "PathStructure";
            }
            detection.confidence = 0.7;
        }

        if (detection.season == null && pathAnalysis.showFolder != null && !pathAnalysis.hasSeasonFolder) {
            detection.season = 1;
            detection.detectionMethod = "PathStructureDefault";
            detection.confidence = 0.5;
        }

        return detection;
    }

    private MediaTypeDecision determineMediaType(String rawMediaType, EpisodeDetection episodeDetection,
                                               TechnicalAnalysis techAnalysis, PathAnalysis pathAnalysis,
                                               String filename) {

        double movieScore = 0.0;
        double episodeScore = 0.0;
        StringBuilder reasoning = new StringBuilder();

        if ("movie".equals(pathAnalysis.directoryTypeHint)) {
            movieScore += 3.0;
            reasoning.append("Directory hint: Movie (+3.0); ");
        } else if ("episode".equals(pathAnalysis.directoryTypeHint)) {
            episodeScore += 3.0;
            reasoning.append("Directory hint: TV Show (+3.0); ");
        }

        if ("episode".equals(rawMediaType)) {
            episodeScore += 0.4;
            reasoning.append("Raw detection: episode (+0.4); ");
        } else if ("movie".equals(rawMediaType)) {
            movieScore += 0.4;
            reasoning.append("Raw detection: movie (+0.4); ");
        }

        if (episodeDetection.hasEpisodePattern) {
            episodeScore += 1.5;
            reasoning.append("Episode pattern found (+1.5); ");
        }

        if (pathAnalysis.hasSeasonFolder) {
            episodeScore += 1.2;
            reasoning.append("Season folder structure (+1.2); ");
        }

        if (techAnalysis.isTypicalMovieDuration && !techAnalysis.isTypicalEpisodeDuration) {
            movieScore += 0.3;
            reasoning.append("Duration suggests movie (+0.3); ");
        } else if (techAnalysis.isTypicalEpisodeDuration && !techAnalysis.isTypicalMovieDuration) {
            episodeScore += 0.3;
            reasoning.append("Duration suggests episode (+0.3); ");
        } else if (techAnalysis.isTypicalEpisodeDuration && techAnalysis.isTypicalMovieDuration) {
             if (techAnalysis.duration > 3600) {
                 movieScore += 0.1;
                 reasoning.append("Overlapping duration, slightly long (+0.1 movie); ");
             }
        }

        if (techAnalysis.hasMovieQualityIndicator) {
            movieScore += 0.2;
            reasoning.append("Movie quality indicator (+0.2); ");
        }

        if (pathAnalysis.hasTvIndicator) {
            episodeScore += 0.3;
            reasoning.append("TV indicator in path (+0.3); ");
        } else if (pathAnalysis.hasMovieFolderPattern) {
            movieScore += 0.2;
            reasoning.append("Movie folder pattern (+0.2); ");
        }

        if (techAnalysis.hasMultipleAudio) {
            movieScore += 0.1;
            reasoning.append("Multiple audio tracks (+0.1 movie); ");
        }

        if (techAnalysis.isWidescreen) {
            movieScore += 0.05;
            reasoning.append("Widescreen format (+0.05 movie); ");
        }

        String finalType = episodeScore >= movieScore ? "episode" : "movie";
        double confidence = Math.abs(episodeScore - movieScore) / (episodeScore + movieScore + 0.1);

        return new MediaTypeDecision(finalType, confidence, reasoning.toString());
    }

    private String extractShowName(String rawShowName, String filename, PathAnalysis pathAnalysis, 
                                  EpisodeDetection episodeDetection, String relativePath) {
        
        if (pathAnalysis.showFolder != null && !pathAnalysis.showFolder.isEmpty()) {
            if (!isSeasonFolderName(pathAnalysis.showFolder)) {
                String showFromPath = cleanShowName(pathAnalysis.showFolder);
                if (!showFromPath.equals("Unknown Show") && showFromPath.length() > 1) {
                    return showFromPath;
                }
            }
        }
        
        if (episodeDetection.showNameHint != null && !episodeDetection.showNameHint.equals("Unknown Show")) {
            return episodeDetection.showNameHint;
        }

        if ("episode".equals(pathAnalysis.directoryTypeHint) && pathAnalysis.libraryRootIndex != -1) {
            Path path = Paths.get(relativePath);
            int showFolderIndex = pathAnalysis.libraryRootIndex + 1;
            
            if (showFolderIndex < path.getNameCount() - 1) {
                String folderName = path.getName(showFolderIndex).toString();
                
                if (isSeasonFolderName(folderName)) {
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
                    if (rawShowName != null && !rawShowName.trim().isEmpty()) {
                        String cleaned = cleanShowName(rawShowName);
                        if (!cleaned.equals("Unknown Show")) {
                            return cleaned;
                        }
                    }
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

        if (!pathAnalysis.grandParentFolder.isEmpty()) {
            String parentLower = pathAnalysis.parentFolder.toLowerCase();
            boolean parentIsMetadata = isSeasonFolderName(parentLower);
                                     
            if (parentIsMetadata || pathAnalysis.hasSeasonFolder) {
                String folderShowName = cleanShowName(pathAnalysis.grandParentFolder);
                if (!folderShowName.equals("Unknown Show") && 
                    !folderShowName.equalsIgnoreCase("tv shows") && 
                    !folderShowName.equalsIgnoreCase("shows")) {
                    return folderShowName;
                }
                
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
        
        Matcher m = EPISODE_SXXEXX_PREFIX.matcher(filename);
        if (m.find()) {
            String nameFromFilename = cleanShowName(m.group(1));
            if (!nameFromFilename.equals("Unknown Show") && nameFromFilename.length() > 1) {
                return nameFromFilename;
            }
        }
        
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
        
        String nameLower = name.toLowerCase();
        
        if (SEASON_NAME_PATTERN.matcher(name).matches()) return true;
        if (SEASON_EMBEDDED_PATTERN.matcher(name).matches()) return true;
        
        if (SEASON_WORD_PATTERN.matcher(name).find()) return true;
        
        if (SERIES_FOLDER_PATTERN.matcher(name).matches()) return true;
        
        if (name.matches("^\\d{1,3}$")) {
            try {
                int num = Integer.parseInt(name);
                return num >= 1 && num <= 999;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        
        if (name.matches("(?i)\\d+[xX]\\d+.*")) return true;
        if (SPECIALS_PATTERN.matcher(name).matches()) return true;
        if (TEMPORADA_SHORT_PATTERN.matcher(name).matches()) return true;
        
        if (nameLower.matches(".*s\\d{1,3}.*season\\s*\\d+.*") || 
            nameLower.matches(".*season\\s*\\d+.*s\\d{1,3}.*")) {
            return true;
        }
        
        if (name.matches("(?i).*[\\.\\-_ ]s0\\d[\\.\\-_ ].*") || 
            name.matches("(?i).*[\\.\\-_ ]s1\\d[\\.\\-_ ].*") ||
            name.matches("(?i).*[\\.\\-_ ]s\\d{2}[\\.\\-_ ].*")) {
            return true;
        }

        if (nameLower.contains("complete") || 
            nameLower.contains("720p") || 
            nameLower.contains("1080p") ||
            nameLower.contains("galaxytv") ||
            nameLower.matches(".*\\d+x\\d+.*") || 
            nameLower.matches(".*\\[\\d+-\\d+\\].*")) return true;
        
        return false;
    }

    private String extractTitle(String rawTitle, String filename, EpisodeDetection episodeDetection, 
                               String mediaType, PathAnalysis pathAnalysis, String showName) {
        
        if ("movie".equals(mediaType)) {
            String filenameTitle = cleanTitle(filename.replaceFirst("\\.[^.]+$", ""));
            if (isGenericName(filenameTitle) && !pathAnalysis.parentFolder.isEmpty()) {
                return cleanTitle(pathAnalysis.parentFolder);
            }
            return filenameTitle;
        }

        if (episodeDetection.titleHint != null && !episodeDetection.titleHint.trim().isEmpty()) {
            String cleanedHint = cleanTitle(episodeDetection.titleHint);
            if (!cleanedHint.equalsIgnoreCase("Unknown Title") && cleanedHint.length() > 1) {
                return cleanedHint;
            }
        }
        
        if ("episode".equals(mediaType) && isGenericName(filename.replaceFirst("\\.[^.]+$", ""))) {
            if (!pathAnalysis.parentFolder.isEmpty() && !pathAnalysis.hasSeasonFolder) {
                String parentTitle = cleanTitle(pathAnalysis.parentFolder);
                if (!parentTitle.equalsIgnoreCase(showName) && !isGenericName(parentTitle)) {
                    return parentTitle;
                }
            }
        }
        
        String filenameWithoutExt = filename.replaceFirst("\\.[^.]+$", "");
        for (Pattern pattern : TV_SHOW_PATTERNS) {
            filenameWithoutExt = pattern.matcher(filenameWithoutExt).replaceAll("").trim();
        }
        
        String finalTitle = cleanTitle(filenameWithoutExt);
        
        if ("episode".equals(mediaType)) {
            if (showName == null || showName.equals("Unknown Show")) {
                return finalTitle;
            }

            if (finalTitle.equalsIgnoreCase(showName) || isGenericName(finalTitle)) {
                return null; 
            }
            
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
    
    private Integer extractYear(Integer rawYear, String filename, PathAnalysis pathAnalysis) {
        if (rawYear != null) {
            return rawYear;
        }
        
        Matcher matcher = YEAR_STANDALONE_PATTERN.matcher(filename);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {}
        }
        
        String[] parts = (pathAnalysis.parentFolder + " " + pathAnalysis.grandParentFolder).split(" ");
        for (String part : parts) {
            matcher = YEAR_STANDALONE_PATTERN.matcher(part);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group());
                } catch (NumberFormatException e) {}
            }
        }
        
        return null;
    }
    
    private double calculateFinalConfidence(MediaTypeDecision mediaTypeDecision, 
                                           EpisodeDetection episodeDetection,
                                           TechnicalAnalysis techAnalysis,
                                           PathAnalysis pathAnalysis) {
        
        double baseConfidence = mediaTypeDecision.confidence;
        if (episodeDetection.hasEpisodePattern) baseConfidence += 0.1;
        if (episodeDetection.confidence > 0.8) baseConfidence += 0.1;
        if (techAnalysis.isTypicalMovieDuration || techAnalysis.isTypicalEpisodeDuration) baseConfidence += 0.05;
        if (pathAnalysis.hasSeasonFolder) baseConfidence += 0.05;
        
        return Math.min(baseConfidence, 1.0);
    }
    
    public static String cleanShowName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Unknown Show";
        }
        
        String cleaned = name;

        Matcher seasonMatcher = SEASON_SPLIT_PATTERN.matcher(cleaned);
        if (seasonMatcher.find()) {
            String[] parts = SEASON_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher tMatcher = Pattern.compile("(?i)\\s+t\\d{1,3}(?=\\s|$|\\))").matcher(cleaned);
        if (tMatcher.find()) {
            String[] parts = cleaned.split("(?i)\\s+t\\d{1,3}(?=\\s|$|\\))", 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher bookMatcher = Pattern.compile("(?i)\\s+(libro|book|volume|chapter|vol|tome|arco|saga)[s]?\\s*\\d+").matcher(cleaned);
        if (bookMatcher.find()) {
            String[] parts = cleaned.split("(?i)\\s+(libro|book|volume|chapter|vol|tome|arco|saga)[s]?\\s*\\d+", 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher completeMatcher = COMPLETE_SPLIT_PATTERN.matcher(cleaned);
        if (completeMatcher.find()) {
            String[] parts = COMPLETE_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher specialsMatcher = SPECIALS_SPLIT_PATTERN.matcher(cleaned);
        if (specialsMatcher.find()) {
            String[] parts = SPECIALS_SPLIT_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher rangeMatcher = SEASON_RANGE_PATTERN.matcher(cleaned);
        if (rangeMatcher.find()) {
            String[] parts = cleaned.split("(?i)\\s+s\\d{1,2}\\s*-\\s*s\\d{1,2}|\\s*\\[\\d+-\\d+\\]|\\s*\\d+-\\d+", 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher plusMediaMatcher = PLUS_MEDIA_PATTERN.matcher(cleaned);
        if (plusMediaMatcher.find()) {
            String[] parts = PLUS_MEDIA_PATTERN.split(cleaned, 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher complexMatcher = Pattern.compile("(?i)[\\.\\-_ ]s\\d{2}[\\.\\-_ ]").matcher(cleaned);
        if (complexMatcher.find()) {
            String[] parts = cleaned.split("(?i)[\\.\\-_ ]s\\d{2}[\\.\\-_ ]", 2);
            if (parts.length > 0 && parts[0].trim().length() > 1) {
                cleaned = parts[0];
            }
        }
        
        Matcher qualityParenMatcher = Pattern.compile("(?i)\\s*\\(\\d{3,4}p[\\s-]*[a-z]*\\)\\s*$").matcher(cleaned);
        cleaned = qualityParenMatcher.replaceAll("");
        
        cleaned = LEADING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = TRAILING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = METADATA_BRACKETS_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = Pattern.compile("(?i)\\s*\\([\\d]p[\\s-]*[a-z]*\\)\\s*$").matcher(cleaned).replaceAll("");
        cleaned = LEADING_SEQUENCE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_PAREN_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_STANDALONE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = RELEASE_CLEAN_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = SEASON_EPISODE_REMOVE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = STANDALONE_SXX_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = TEMPORADA_T_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = CLEANUP_CHARS_PATTERN.matcher(cleaned).replaceAll(" ").trim();
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = toTitleCase(cleaned.trim());
        
        if (cleaned.isEmpty()) return "Unknown Show";
        if (FILE_EXTENSION_PATTERN.matcher(cleaned).find()) return "Unknown Show";
        if (cleaned.length() < 2 || cleaned.matches("(?i)^(mkv|mp4|avi|show|series|season|arco|saga)$")) return "Unknown Show";
        
        return cleaned;
    }
    
    private static String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "Unknown Title";
        String cleaned = title;
        cleaned = LEADING_BRACKETS_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = METADATA_BRACKETS_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = LEADING_SEQUENCE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_PAREN_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = YEAR_STANDALONE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = RELEASE_CLEAN_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = CLEANUP_CHARS_PATTERN.matcher(cleaned).replaceAll(" ").trim();
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        String result = toTitleCase(cleaned.trim());
        return result.isEmpty() ? "Unknown Title" : result;
    }
    
    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
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
    
    private String generateReasoning(MediaTypeDecision mediaTypeDecision, EpisodeDetection episodeDetection,
                                   TechnicalAnalysis techAnalysis, PathAnalysis pathAnalysis) {
        StringBuilder reasoning = new StringBuilder();
        reasoning.append("Media Type: ").append(mediaTypeDecision.reasoning);
        if (episodeDetection.hasEpisodePattern) reasoning.append("Episode pattern: ").append(episodeDetection.detectionMethod).append("; ");
        if (techAnalysis.isTypicalMovieDuration) reasoning.append("Duration matches movie pattern; ");
        else if (techAnalysis.isTypicalEpisodeDuration) reasoning.append("Duration matches episode pattern; ");
        if (pathAnalysis.hasSeasonFolder) reasoning.append("Season folder detected; ");
        return reasoning.toString();
    }
    
    private Integer extractSeasonFromFolder(String folderName) {
        if (folderName == null || folderName.isEmpty()) return null;
        String nameLower = folderName.toLowerCase();
        Matcher combinedMatcher = SEASON_COMBINED_PATTERN.matcher(folderName);
        if (combinedMatcher.find()) {
            try { return Integer.parseInt(combinedMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        Pattern wordPattern = Pattern.compile("(?i)(season|libro|temporada|book|volume|chapter|episode|arco|saga|tome)[s]?[-_. ]?(\\d{1,3})");
        Matcher wordMatcher = wordPattern.matcher(folderName);
        if (wordMatcher.find()) {
            try { return Integer.parseInt(wordMatcher.group(2)); } catch (NumberFormatException e) {}
        }
        Matcher seriesMatcher = SERIES_FOLDER_PATTERN.matcher(folderName);
        if (seriesMatcher.find()) {
            try { return Integer.parseInt(seriesMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        Matcher sxxMatcher = Pattern.compile("(?i)^s(\\d{1,3})$").matcher(folderName);
        if (sxxMatcher.matches()) {
            try { return Integer.parseInt(sxxMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        Matcher embeddedMatcher = Pattern.compile("(?i)\\.s(\\d{1,3})\\.").matcher(folderName);
        if (embeddedMatcher.find()) {
            try { return Integer.parseInt(embeddedMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        Matcher tMatcher = TEMPORADA_SHORT_PATTERN.matcher(folderName);
        if (tMatcher.matches()) {
            try { return Integer.parseInt(tMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        if (folderName.matches("^\\d{1,3}$")) {
            try {
                int num = Integer.parseInt(folderName);
                return num >= 1 && num <= 999 ? num : null;
            } catch (NumberFormatException e) {}
        }
        Matcher animeMatcher = Pattern.compile("(?i)(\\d+)[xX]\\d+").matcher(folderName);
        if (animeMatcher.find()) {
            try { return Integer.parseInt(animeMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        Matcher complexMatcher = Pattern.compile("(?i)[\\.\\-_ ]s(\\d{2})[\\.\\-_ ]").matcher(folderName);
        if (complexMatcher.find()) {
            try { return Integer.parseInt(complexMatcher.group(1)); } catch (NumberFormatException e) {}
        }
        if (nameLower.matches(".*s\\d{1,3}.*season\\s*(\\d+).*")) {
            Matcher m = Pattern.compile("(?i)season\\s*(\\d+)").matcher(folderName);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) {}
            }
        }
        return null;
    }
    
    private static String normalizeShowNameForComparison(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]", "").replaceAll("\\d{4}", "");
    }
    
    private static class PathAnalysis {
        public int pathDepth;
        public String parentFolder;
        public String grandParentFolder;
        public boolean hasSeasonFolder;
        public boolean hasTvIndicator;
        public boolean hasMovieFolderPattern;
        public String directoryTypeHint;
        public int libraryRootIndex = -1;
        public String libraryRootFolder;
        public String showFolder;
        public String seasonFolder;
        public int showFolderIndex = -1;
        public int seasonFolderIndex = -1;
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