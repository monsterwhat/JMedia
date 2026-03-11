package Services;

import Models.SubtitleTrack;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
        Map.entry("spl", new LanguageInfo("spl", "es", "Español (LATAM)")),
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
    
    @Inject
    FFprobeSubtitleService ffprobeSubtitleService;

    public List<SubtitleTrack> discoverSubtitleTracks(Path videoPath, Video video) {
        List<SubtitleTrack> tracks = new ArrayList<>();
        
        // 1. External subtitle discovery
        tracks.addAll(discoverExternalSubtitles(videoPath, video));

        // 2. Internal subtitle discovery
        try {
            tracks.addAll(ffprobeSubtitleService.extractSubtitleTracks(video, videoPath.toString()));
        } catch (Exception e) {
            System.err.println("Error discovering internal subtitles: " + e.getMessage());
        }
        
        // 3. Language code analysis and track naming
        enrichWithLanguageMetadata(tracks);
        
        // 4. Default track selection
        selectDefaultTrack(tracks);
        
        return tracks;
    }
    
    public List<Models.DTOs.LocalSubtitleFile> scanAllSubtitleFiles(Path videoPath, Video video) {
        List<Models.DTOs.LocalSubtitleFile> tracks = new ArrayList<>();
        Path videoDir = videoPath.getParent();
        
        if (videoDir == null || !Files.exists(videoDir)) {
            return tracks;
        }
        
        try (java.util.stream.Stream<Path> stream = Files.walk(videoDir, 3)) {
            List<Path> subtitleFiles = stream
                .filter(Files::isRegularFile)
                .filter(this::isSubtitleFile)
                .collect(Collectors.toList());
            
            for (Path subtitleFile : subtitleFiles) {
                String filename = subtitleFile.getFileName().toString();
                String fullPath = subtitleFile.toString();
                String format = getFileExtension(filename);
                long fileSize = Files.size(subtitleFile);
                
                SubtitleTrack tempTrack = new SubtitleTrack();
                extractLanguageAndTags(filename, tempTrack);
                
                tracks.add(new Models.DTOs.LocalSubtitleFile(
                    filename,
                    fullPath,
                    tempTrack.languageName,
                    format,
                    fileSize
                ));
            }
        } catch (Exception e) {
            System.err.println("Error scanning all subtitle files recursively: " + e.getMessage());
        }
        
        return tracks;
    }

    private List<SubtitleTrack> discoverExternalSubtitles(Path videoPath, Video video) {
        List<SubtitleTrack> tracks = new ArrayList<>();
        Path videoDir = videoPath.getParent();
        String videoFilename = videoPath.getFileName().toString();
        String videoBasename = getBasename(videoFilename);
        
        if (videoDir == null || !Files.exists(videoDir)) {
            return tracks;
        }
        
        // Use Files.walk to recursively search for subtitles (depth 3 covers most "Subs/" or "Lang/" folder structures)
        try (java.util.stream.Stream<Path> stream = Files.walk(videoDir, 3)) {
            List<Path> subtitleFiles = stream
                .filter(Files::isRegularFile)
                .filter(this::isSubtitleFile)
                .collect(Collectors.toList());
            
            for (Path subtitleFile : subtitleFiles) {
                SubtitleTrack track = createTrackFromFile(subtitleFile, videoBasename, video);
                if (track != null) {
                    tracks.add(track);
                }
            }
        } catch (Exception e) {
            System.err.println("Error discovering subtitle files recursively: " + e.getMessage());
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
            String format = getFileExtension(filename);
            
            // Check if this subtitle belongs to the video
            if (!isSubtitleForVideo(filename, videoBasename)) {
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
    
    private boolean isSubtitleForVideo(String subtitleFilename, String videoBasename) {
        String subName = subtitleFilename.toLowerCase();
        String vidName = videoBasename.toLowerCase();
        
        // 1. Check if the subtitle filename starts with the video basename
        if (subName.startsWith(vidName)) {
            // If it's an exact match (plus extension which is handled elsewhere)
            if (subName.length() == vidName.length()) return true;
            
            // If it's followed by a delimiter
            char nextChar = subName.charAt(vidName.length());
            if (nextChar == '.' || nextChar == '_' || nextChar == '-' || nextChar == ' ') {
                return true;
            }
        }
        
        // 2. Handle cases where the video basename might be part of a larger filename
        // or where both have dots that don't align perfectly.
        // We also check if the video basename is contained and followed by standard markers
        if (subName.contains(vidName)) {
            int index = subName.indexOf(vidName);
            int endOfVidName = index + vidName.length();
            
            // Check what follows the video name in the subtitle filename
            if (endOfVidName < subName.length()) {
                char nextChar = subName.charAt(endOfVidName);
                if (nextChar == '.' || nextChar == '_' || nextChar == '-' || nextChar == ' ') {
                    return true;
                }
            } else {
                return true; // Ends with the video name
            }
        }
        
        return false;
    }
    
    public void extractLanguageAndTags(String filename, SubtitleTrack track) {
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
        // Look for language tags near the end of the filename (before the extension)
        // Standard formats: movie.en.srt, movie.eng.os-123.srt, movie.spa.forced.srt
        
        String[] parts = filename.toLowerCase().split("\\.");
        // parts[last] is extension, parts[last-1] is often the language or os-id
        
        for (int i = parts.length - 2; i >= 0; i--) {
            String part = parts[i];
            
            // Check if this part matches our language map
            if (LANGUAGE_MAP.containsKey(part)) {
                return part;
            }
            
            // Check if it's a 2 or 3 letter code that looks like a language
            if (LANGUAGE_PATTERN.matcher(part).matches() && !part.equals("srt") && !part.equals("vtt")) {
                // Skip common non-language tags that might match the pattern
                if (List.of("720p", "1080p", "2160p", "x264", "x265", "aac", "dts").contains(part)) {
                    continue;
                }
                return part;
            }
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
        
        if (track.languageName != null && !track.languageName.equalsIgnoreCase("Unknown")) {
            displayName.append(track.languageName);
        } else if (track.filename != null) {
            // Fallback to filename if no language is detected
            displayName.append(track.filename);
        } else {
            displayName.append("Unknown");
        }
        
        if (track.isForced) {
            displayName.append(" (Forced)");
        }
        
        if (track.isSDH) {
            displayName.append(" (SDH)");
        }
        
        if (Boolean.TRUE.equals(track.isManual)) {
            displayName.append(" (Manual)");
        }
        
        return displayName.toString();
    }
    
    private void enrichWithLanguageMetadata(List<SubtitleTrack> tracks) {
        // Ensure all tracks have proper language codes
        for (SubtitleTrack track : tracks) {
            if (track.languageCode == null) {
                track.languageCode = "und"; // Undetermined
                if (track.languageName == null) track.languageName = "Unknown";
                track.displayName = createDisplayName(track);
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