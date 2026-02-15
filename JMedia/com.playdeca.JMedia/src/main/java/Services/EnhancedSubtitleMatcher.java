package Services;

import Models.SubtitleTrack;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@ApplicationScoped
public class EnhancedSubtitleMatcher {
    
    // Language Code Mappings (ISO 639-2 ↔ Display Names)
    private static final Map<String, LanguageInfo> LANGUAGE_MAP = Map.ofEntries(
        Map.entry("eng", new LanguageInfo("eng", "en", "English")),
        Map.entry("en", new LanguageInfo("eng", "en", "English")),
        Map.entry("fre", new LanguageInfo("fre", "fr", "Français")),
        Map.entry("fr", new LanguageInfo("fre", "fr", "Français")),
        Map.entry("spa", new LanguageInfo("spa", "es", "Español")),
        Map.entry("es", new LanguageInfo("spa", "es", "Español")),
        Map.entry("deu", new LanguageInfo("deu", "de", "Deutsch")),
        Map.entry("de", new LanguageInfo("deu", "de", "Deutsch")),
        Map.entry("ita", new LanguageInfo("ita", "it", "Italiano")),
        Map.entry("it", new LanguageInfo("ita", "it", "Italiano")),
        Map.entry("por", new LanguageInfo("por", "pt", "Português")),
        Map.entry("pt", new LanguageInfo("por", "pt", "Português")),
        Map.entry("rus", new LanguageInfo("rus", "ru", "Русский")),
        Map.entry("ru", new LanguageInfo("rus", "ru", "Русский")),
        Map.entry("jpn", new LanguageInfo("jpn", "ja", "日本語")),
        Map.entry("ja", new LanguageInfo("jpn", "ja", "日本語")),
        Map.entry("kor", new LanguageInfo("kor", "ko", "한국어")),
        Map.entry("ko", new LanguageInfo("kor", "ko", "한국어")),
        Map.entry("chi", new LanguageInfo("chi", "zh", "中文")),
        Map.entry("zh", new LanguageInfo("chi", "zh", "中文"))
    );
    
    // Supported subtitle formats
    private static final List<String> SUPPORTED_FORMATS = List.of("srt", "vtt", "ass", "ssa");
    
    // Language tag patterns
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}$|^forced$|^sdh$");
    private static final Pattern FORCED_PATTERN = Pattern.compile("(?i)forced|\\.forced");
    private static final Pattern SDH_PATTERN = Pattern.compile("(?i)sdh|\\.sdh");
    private static final Pattern LANGUAGE_TAG_PATTERN = Pattern.compile("\\.([a-zA-Z]{2,3})(?=\\.|$)");
    
    public List<SubtitleTrack> discoverSubtitleTracks(Path videoPath, Video video) {
        List<SubtitleTrack> tracks = new ArrayList<>();
        
        // 1. External subtitle discovery
        tracks.addAll(discoverExternalSubtitles(videoPath, video));
        
        // 2. Language code analysis and track naming
        enrichWithLanguageMetadata(tracks);
        
        // 3. Default track selection
        selectDefaultTrack(tracks);
        
        return tracks;
    }
    
    private List<SubtitleTrack> discoverExternalSubtitles(Path videoPath, Video video) {
        List<SubtitleTrack> tracks = new ArrayList<>();
        Path videoDir = videoPath.getParent();
        String videoBasename = getBasename(videoPath.toString());
        
        if (videoDir == null || !Files.exists(videoDir)) {
            return tracks;
        }
        
        try {
            List<Path> subtitleFiles = Files.list(videoDir)
                .filter(Files::isRegularFile)
                .filter(path -> isSubtitleFile(path))
                .collect(Collectors.toList());
            
            for (Path subtitleFile : subtitleFiles) {
                SubtitleTrack track = createTrackFromFile(subtitleFile, videoBasename, video);
                if (track != null) {
                    tracks.add(track);
                }
            }
        } catch (Exception e) {
            System.err.println("Error discovering subtitle files: " + e.getMessage());
        }
        
        return tracks;
    }
    
    private boolean isSubtitleFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return SUPPORTED_FORMATS.stream().anyMatch(format -> filename.endsWith("." + format));
    }
    
    private SubtitleTrack createTrackFromFile(Path subtitleFile, String videoBasename, Video video) {
        try {
            String filename = subtitleFile.getFileName().toString();
            String basename = getBasename(filename);
            String format = getFileExtension(filename);
            
            // Check if this subtitle belongs to the video
            if (!isSubtitleForVideo(basename, videoBasename)) {
                return null;
            }
            
            SubtitleTrack track = new SubtitleTrack();
            track.filename = filename;
            track.fullPath = subtitleFile.toString();
            track.format = format;
            track.video = video;
            track.fileSize = Files.size(subtitleFile);
            track.isEmbedded = false;
            
            // Extract language and special tags
            extractLanguageAndTags(filename, track);
            
            return track;
            
        } catch (Exception e) {
            System.err.println("Error creating subtitle track from file: " + e.getMessage());
            return null;
        }
    }
    
    private boolean isSubtitleForVideo(String subtitleBasename, String videoBasename) {
        // Exact match: movie.srt ↔ movie.mp4
        if (subtitleBasename.equalsIgnoreCase(videoBasename)) {
            return true;
        }
        
        // Contains match: movie.en.srt ↔ movie.mp4
        if (subtitleBasename.startsWith(videoBasename)) {
            return true;
        }
        
        // Loose match: contains video basename anywhere
        return subtitleBasename.toLowerCase().contains(videoBasename.toLowerCase());
    }
    
    private void extractLanguageAndTags(String filename, SubtitleTrack track) {
        // Extract language tag
        String languageTag = extractLanguageTag(filename);
        if (languageTag != null) {
            track.languageCode = normalizeLanguageCode(languageTag);
            LanguageInfo langInfo = LANGUAGE_MAP.get(track.languageCode);
            if (langInfo != null) {
                track.languageName = langInfo.displayName;
            } else {
                track.languageName = languageTag.toUpperCase();
            }
        }
        
        // Check for special tags
        track.isForced = FORCED_PATTERN.matcher(filename).find();
        track.isSDH = SDH_PATTERN.matcher(filename).find();
        
        // Create display name
        track.displayName = createDisplayName(track);
    }
    
    private String extractLanguageTag(String filename) {
        // Pattern: movie.en.srt, movie.eng.srt, movie.forced.srt
        java.util.regex.Matcher matcher = LANGUAGE_TAG_PATTERN.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    private String normalizeLanguageCode(String code) {
        // Convert 2-letter codes to 3-letter codes when needed
        LanguageInfo info = LANGUAGE_MAP.get(code.toLowerCase());
        return info != null ? info.iso639_2 : code.toLowerCase();
    }
    
    private String createDisplayName(SubtitleTrack track) {
        StringBuilder displayName = new StringBuilder();
        
        if (track.languageName != null) {
            displayName.append(track.languageName);
        } else {
            displayName.append("Unknown");
        }
        
        if (track.isForced) {
            displayName.append(" (Forced)");
        }
        
        if (track.isSDH) {
            displayName.append(" (SDH)");
        }
        
        return displayName.toString();
    }
    
    private void enrichWithLanguageMetadata(List<SubtitleTrack> tracks) {
        // Ensure all tracks have proper language codes
        for (SubtitleTrack track : tracks) {
            if (track.languageCode == null) {
                track.languageCode = "und"; // Undetermined
                track.languageName = "Unknown";
                track.displayName = "Unknown Language";
            }
        }
    }
    
    private void selectDefaultTrack(List<SubtitleTrack> tracks) {
        if (tracks.isEmpty()) return;
        
        // Priority for default selection:
        // 1. User's preferred language
        // 2. English track
        // 3. First track in list
        
        for (SubtitleTrack track : tracks) {
            track.isDefault = false;
        }
        
        // Default to English if available
        Optional<SubtitleTrack> englishTrack = tracks.stream()
            .filter(track -> "eng".equals(track.languageCode))
            .findFirst();
        
        if (englishTrack.isPresent()) {
            englishTrack.get().isDefault = true;
        } else if (!tracks.isEmpty()) {
            tracks.get(0).isDefault = true;
        }
    }
    
    private static String getBasename(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
    
    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
    
    // Language info helper class
    public static class LanguageInfo {
        public final String iso639_2;  // 3-letter code (eng)
        public final String iso639_1;  // 2-letter code (en)
        public final String displayName; // Display name (English)
        
        public LanguageInfo(String iso639_2, String iso639_1, String displayName) {
            this.iso639_2 = iso639_2;
            this.iso639_1 = iso639_1;
            this.displayName = displayName;
        }
    }
}