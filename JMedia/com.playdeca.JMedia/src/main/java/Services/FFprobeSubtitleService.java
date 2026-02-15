package Services;

import Models.SubtitleTrack;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for extracting subtitle information using FFprobe
 */
@ApplicationScoped
public class FFprobeSubtitleService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FFprobeSubtitleService.class);
    
    // Regex patterns for parsing FFprobe output
    private static final Pattern STREAM_PATTERN = Pattern.compile(
        "\\[STREAM\\]\\s+Index: (\\d+).*?codec_name: (\\w+).*?codec_type: (\\w+)"
    );
    
    private static final Pattern SUBTITLE_PATTERN = Pattern.compile(
        "codec_name: (\\w+).*?tag:language=(\\w{3})?.*?DISPOSITION:.*?default=(\\d+).*?forced=(\\d+).*?hearing_impaired=(\\d+)"
    );
    
    // Supported subtitle codecs
    private static final List<String> SUPPORTED_SUBTITLE_CODECS = List.of(
        "subrip", "ass", "ssa", "webvtt", "srt", "vtt"
    );
    
    // Language name mapping (ISO 639-2 to full name)
    private static final java.util.Map<String, String> LANGUAGE_MAP = new java.util.HashMap<>();
    static {
        LANGUAGE_MAP.put("eng", "English");
        LANGUAGE_MAP.put("spa", "Español");
        LANGUAGE_MAP.put("fre", "Français");
        LANGUAGE_MAP.put("deu", "Deutsch");
        LANGUAGE_MAP.put("ita", "Italiano");
        LANGUAGE_MAP.put("por", "Português");
        LANGUAGE_MAP.put("jpn", "日本語");
        LANGUAGE_MAP.put("kor", "한국어");
        LANGUAGE_MAP.put("chi", "中文");
        LANGUAGE_MAP.put("rus", "Русский");
        LANGUAGE_MAP.put("ara", "العربية");
        LANGUAGE_MAP.put("hin", "हिन्दी");
        LANGUAGE_MAP.put("tha", "ไทย");
        LANGUAGE_MAP.put("vie", "Tiếng Việt");
    }
    
    /**
     * Extract subtitle tracks from a video file using FFprobe
     */
    @Transactional
    public List<SubtitleTrack> extractSubtitleTracks(Video video, String videoPath) {
        List<SubtitleTrack> subtitleTracks = new ArrayList<>();
        
        try {
            String ffprobePath = findFFprobeExecutable();
            if (ffprobePath == null) {
                LOGGER.warn("FFprobe not found, cannot extract embedded subtitles");
                return subtitleTracks;
            }
            
            ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                videoPath
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read JSON output
            StringBuilder jsonOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonOutput.append(line);
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOGGER.error("FFprobe failed with exit code: {}", exitCode);
                return subtitleTracks;
            }
            
            // Parse subtitle information from JSON
            parseSubtitleStreams(jsonOutput.toString(), video, subtitleTracks);
            
            LOGGER.info("Extracted {} subtitle tracks from {}", subtitleTracks.size(), videoPath);
            
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error extracting subtitles with FFprobe", e);
        }
        
        return subtitleTracks;
    }
    
    /**
     * Parse subtitle streams from FFprobe JSON output
     */
    private void parseSubtitleStreams(String jsonOutput, Video video, List<SubtitleTrack> subtitleTracks) {
        try {
            // Simple regex-based parsing (for environments without JSON libraries)
            parseWithRegex(jsonOutput, video, subtitleTracks);
        } catch (Exception e) {
            LOGGER.error("Error parsing subtitle streams", e);
        }
    }
    
    /**
     * Fallback regex-based parsing method
     */
    private void parseWithRegex(String output, Video video, List<SubtitleTrack> subtitleTracks) {
        String[] lines = output.split("\\r?\\n");
        
        for (String line : lines) {
            if (line.contains("codec_type: subtitle") || line.contains("codec_type: sub")) {
                SubtitleTrack track = parseSubtitleLine(line, video);
                if (track != null) {
                    subtitleTracks.add(track);
                }
            }
        }
    }
    
    /**
     * Parse a single subtitle stream line
     */
    private SubtitleTrack parseSubtitleLine(String line, Video video) {
        try {
            String codec = extractValue(line, "codec_name");
            String languageCode = extractLanguageCode(line);
            
            if (codec == null || !SUPPORTED_SUBTITLE_CODECS.contains(codec.toLowerCase())) {
                return null;
            }
            
            SubtitleTrack track = new SubtitleTrack();
            track.video = video;
            track.isEmbedded = true;
            track.codec = codec;
            track.format = determineFormat(codec);
            track.languageCode = languageCode != null ? languageCode : "und";
            track.languageName = LANGUAGE_MAP.getOrDefault(languageCode, languageCode != null ? languageCode.toUpperCase() : "Unknown");
            track.displayName = track.languageName;
            
            // Extract disposition information
            boolean isDefault = extractDisposition(line, "default");
            boolean isForced = extractDisposition(line, "forced");
            boolean isSDH = extractDisposition(line, "hearing_impaired");
            
            track.isDefault = isDefault;
            track.isForced = isForced;
            track.isSDH = isSDH;
            
            // Extract track index
            String indexStr = extractValue(line, "index");
            if (indexStr != null) {
                track.trackIndex = Integer.parseInt(indexStr);
            }
            
            // Generate filename for the embedded track
            track.filename = String.format("%s_track_%d.%s", 
                video.filename != null ? video.filename.replaceFirst("[.][^.]+$", "") : "embedded",
                track.trackIndex != null ? track.trackIndex : 0,
                track.format
            );
            
            track.isActive = true;
            track.userPreferenceOrder = 0;
            
            return track;
            
        } catch (Exception e) {
            LOGGER.error("Error parsing subtitle line: {}", line, e);
            return null;
        }
    }
    
    /**
     * Extract a specific field value from FFprobe output line
     */
    private String extractValue(String line, String field) {
        Pattern pattern = Pattern.compile(field + "\\s*:\\s*([^\\s,}]+)");
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1).replaceAll("\"", "") : null;
    }
    
    /**
     * Extract language code from FFprobe output
     */
    private String extractLanguageCode(String line) {
        // Try to extract from tag:language field
        String lang = extractValue(line, "tag:language");
        if (lang != null) {
            return lang.replaceAll("\"", "");
        }
        
        // Fallback to other common patterns
        if (line.contains("language=eng")) return "eng";
        if (line.contains("language=spa")) return "spa";
        if (line.contains("language=fre")) return "fre";
        if (line.contains("language=deu")) return "deu";
        if (line.contains("language=ita")) return "ita";
        if (line.contains("language=por")) return "por";
        if (line.contains("language=jpn")) return "jpn";
        if (line.contains("language=kor")) return "kor";
        if (line.contains("language=chi")) return "chi";
        
        return "und"; // Undetermined
    }
    
    /**
     * Extract disposition value from FFprobe output
     */
    private boolean extractDisposition(String line, String disposition) {
        String value = extractValue(line, disposition);
        return "1".equals(value);
    }
    
    /**
     * Determine subtitle format from codec
     */
    private String determineFormat(String codec) {
        switch (codec.toLowerCase()) {
            case "subrip":
                return "srt";
            case "ass":
                return "ass";
            case "ssa":
                return "ssa";
            case "webvtt":
                return "vtt";
            case "srt":
                return "srt";
            case "vtt":
                return "vtt";
            default:
                return "unknown";
        }
    }
    
    /**
     * Find FFprobe executable in the system
     */
    private String findFFprobeExecutable() {
        // Common FFprobe locations
        String[] possiblePaths = {
            "ffprobe",
            "/usr/bin/ffprobe",
            "/usr/local/bin/ffprobe",
            "C:\\Program Files\\FFmpeg\\bin\\ffprobe.exe",
            "C:\\ffmpeg\\bin\\ffprobe.exe"
        };
        
        for (String path : possiblePaths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(path, "-version");
                Process process = pb.start();
                if (process.waitFor() == 0) {
                    return path;
                }
            } catch (IOException | InterruptedException e) {
                // Continue to next path
            }
        }
        
        LOGGER.error("FFprobe executable not found in any standard location");
        return null;
    }
    
    /**
     * Extract subtitle tracks from all videos in a batch
     */
    @Transactional
    public void extractSubtitlesForAllVideos() {
        List<Video> videos = Video.list("isActive = true");
        
        LOGGER.info("Starting subtitle extraction for {} videos", videos.size());
        
        int processed = 0;
        int withSubtitles = 0;
        
        for (Video video : videos) {
            try {
                if (video.path != null && Files.exists(Paths.get(video.path))) {
                    List<SubtitleTrack> tracks = extractSubtitleTracks(video, video.path);
                    
                    // Remove existing embedded tracks for this video
                    SubtitleTrack.delete("video.id = ?1 and isEmbedded = true", video.id);
                    
                    // Save new tracks
                    for (SubtitleTrack track : tracks) {
                        track.persist();
                    }
                    
                    if (!tracks.isEmpty()) {
                        withSubtitles++;
                    }
                }
                processed++;
                
                // Log progress every 10 videos
                if (processed % 10 == 0) {
                    LOGGER.info("Processed {} of {} videos", processed, videos.size());
                }
                
            } catch (Exception e) {
                LOGGER.error("Error processing video: {}", video.id, e);
            }
        }
        
        LOGGER.info("Subtitle extraction completed. Processed: {}, With subtitles: {}", 
            processed, withSubtitles);
    }
    
    /**
     * Check if a video likely has embedded subtitles based on file characteristics
     */
    public boolean videoLikelyHasSubtitles(String videoPath) {
        if (videoPath == null) return false;
        
        Path path = Paths.get(videoPath);
        String fileName = path.getFileName().toString().toLowerCase();
        
        // Check file extensions and formats that commonly have embedded subtitles
        return fileName.endsWith(".mp4") || 
               fileName.endsWith(".mkv") || 
               fileName.endsWith(".mov") || 
               fileName.endsWith(".avi");
    }
    
    /**
     * Get statistics about subtitle extraction
     */
    public String getSubtitleExtractionStats() {
        try {
            long totalVideos = Video.count("isActive = true");
            long videosWithEmbedded = SubtitleTrack.count("isEmbedded = true");
            
            long totalSubtitles = SubtitleTrack.count("isEmbedded = true");
            long uniqueLanguages = SubtitleTrack.find("isEmbedded = true and isActive = true")
                .stream()
                .map(st -> ((SubtitleTrack) st).languageCode)
                .distinct()
                .count();
            
            return String.format(
                "Videos: %d, Processed: %d, Embedded subtitles: %d, Languages: %d",
                totalVideos, videosWithEmbedded, totalSubtitles, uniqueLanguages
            );
            
        } catch (Exception e) {
            LOGGER.error("Error getting subtitle stats", e);
            return "Statistics unavailable";
        }
    }
}