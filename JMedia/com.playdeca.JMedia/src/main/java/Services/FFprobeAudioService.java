package Services;

import Models.AudioTrack;
import Models.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for extracting audio track information using FFprobe
 */
@ApplicationScoped
public class FFprobeAudioService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FFprobeAudioService.class);
    
    @Inject
    ObjectMapper objectMapper;

    @Inject
    FFmpegDiscoveryService discoveryService;
    
    // Language name mapping (ISO 639-2 to full name)
    private static final Map<String, String> LANGUAGE_MAP = new HashMap<>();
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
        LANGUAGE_MAP.put("tha", "ภาษาไทย");
        LANGUAGE_MAP.put("vie", "Tiếng Việt");
        LANGUAGE_MAP.put("nld", "Nederlands");
        LANGUAGE_MAP.put("swe", "Svenska");
        LANGUAGE_MAP.put("nor", "Norsk");
        LANGUAGE_MAP.put("dan", "Dansk");
        LANGUAGE_MAP.put("fin", "Suomi");
        LANGUAGE_MAP.put("pol", "Polski");
        LANGUAGE_MAP.put("tur", "Türkçe");
        LANGUAGE_MAP.put("heb", "עברית");
        LANGUAGE_MAP.put("ces", "Čeština");
        LANGUAGE_MAP.put("hun", "Magyar");
        LANGUAGE_MAP.put("ron", "Română");
        LANGUAGE_MAP.put("bul", "Български");
        LANGUAGE_MAP.put("hrv", "Hrvatski");
        LANGUAGE_MAP.put("srp", "Српски");
        LANGUAGE_MAP.put("ukr", "Українська");
        LANGUAGE_MAP.put("ell", "Ελληνικά");
        LANGUAGE_MAP.put("ind", "Bahasa Indonesia");
        LANGUAGE_MAP.put("msa", "Bahasa Melayu");
        LANGUAGE_MAP.put("tam", "தமிழ்");
        LANGUAGE_MAP.put("tel", "తెలుగు");
        LANGUAGE_MAP.put("mar", "मराठी");
        LANGUAGE_MAP.put("ben", "বাংলা");
        LANGUAGE_MAP.put("guj", "ગુજરાતી");
        LANGUAGE_MAP.put("kan", "ಕನ್ನಡ");
        LANGUAGE_MAP.put("mal", "മലയാളം");
        LANGUAGE_MAP.put("cat", "Català");
        LANGUAGE_MAP.put("eus", "Euskara");
        LANGUAGE_MAP.put("glg", "Galego");
    }
    
    /**
     * Extract audio tracks from a video file using FFprobe
     */
    @Transactional
    public List<AudioTrack> extractAudioTracks(Video video, String videoPath) {
        List<AudioTrack> audioTracks = new ArrayList<>();
        
        try {
            String ffprobePath = discoveryService.findFFprobeExecutable();
            if (ffprobePath == null) {
                LOGGER.warn("FFprobe not found, cannot extract audio tracks");
                return audioTracks;
            }

            // Check if video file exists
            java.io.File videoFile = new java.io.File(videoPath);
            if (!videoFile.exists()) {
                LOGGER.error("Video file not found for audio extraction: {}", videoPath);
                return audioTracks;
            }
            LOGGER.info("FFprobe path: {}, Video file: {}", ffprobePath, videoFile.getAbsolutePath());
            
            ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                videoPath
            );
            
            Process process = pb.start();
            JsonNode root = objectMapper.readTree(process.getInputStream());
            JsonNode streams = root.path("streams");
            LOGGER.info("FFprobe found {} streams for {}", streams.isArray() ? streams.size() : 0, videoPath);
            
            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    String codecType = stream.path("codec_type").asText();
                    if ("audio".equals(codecType)) {
                        AudioTrack track = parseAudioStream(stream, video);
                        if (track != null) {
                            audioTracks.add(track);
                        }
                    }
                }
            }
            
            process.waitFor();
            LOGGER.info("Extracted {} audio tracks from {}", audioTracks.size(), videoPath);
            
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error extracting audio tracks with FFprobe", e);
            Thread.currentThread().interrupt();
        }
        
        return audioTracks;
    }
    
    private AudioTrack parseAudioStream(JsonNode stream, Video video) {
        String codec = stream.path("codec_name").asText();
        int index = stream.path("index").asInt();
        
        AudioTrack track = new AudioTrack();
        track.video = video;
        track.isEmbedded = true;
        track.codec = codec;
        track.format = codec;
        track.trackIndex = index;
        track.fullPath = video.path;
        
        // Extract language from tags
        JsonNode tags = stream.path("tags");
        String langCode = tags.path("language").asText("und");
        track.languageCode = langCode;
        track.languageName = LANGUAGE_MAP.getOrDefault(langCode, langCode.toUpperCase());
        
        // Extract title (e.g. "Director's Commentary")
        String title = tags.path("title").asText("");
        track.title = title.isEmpty() ? null : title;
        
        // Build display name
        if (title != null && !title.isEmpty()) {
            track.displayName = String.format("%s - %s", track.languageName, title);
        } else {
            track.displayName = track.languageName;
        }
        
        // Technical details
        track.channels = stream.path("channels").asInt(0);
        
        String bitrateStr = stream.path("bit_rate").asText("");
        if (!bitrateStr.isEmpty()) {
            try {
                track.bitrate = Integer.parseInt(bitrateStr);
            } catch (NumberFormatException e) {
                track.bitrate = 0;
            }
        }
        
        String sampleRateStr = stream.path("sample_rate").asText("");
        if (!sampleRateStr.isEmpty()) {
            try {
                track.sampleRate = Integer.parseInt(sampleRateStr);
            } catch (NumberFormatException e) {
                track.sampleRate = 0;
            }
        }
        
        // Disposition
        JsonNode disposition = stream.path("disposition");
        track.isDefault = disposition.path("default").asInt() == 1;
        
        track.filename = String.format("audio_%d.%s", index, codec);
        
        return track;
    }
}
