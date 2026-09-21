package Services;

import Models.Video.AudioTrack;
import Models.Video.Video;
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
import java.util.concurrent.TimeUnit;

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
            
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("FFprobe audio track probe timed out for: {}", videoPath);
            }
            
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
        
        // Extract language from tags — handle untagged tracks gracefully (no bare "UND")
        JsonNode tags = stream.path("tags");
        String rawLang = tags.path("language").asText(null);
        String langCode;
        if (rawLang == null || rawLang.isBlank() || "und".equalsIgnoreCase(rawLang.trim()) || "unknown".equalsIgnoreCase(rawLang.trim())) {
            langCode = "und";
        } else {
            langCode = rawLang.trim().toLowerCase(java.util.Locale.ROOT);
        }
        track.languageCode = langCode;
        if ("und".equals(langCode)) {
            track.languageName = null;
        } else {
            track.languageName = LANGUAGE_MAP.get(langCode);
            if (track.languageName == null) {
                // Unknown code — keep null so label falls back to layout/role instead of raw code
                track.languageName = null;
                LOGGER.debug("Unknown audio language code '{}' for stream index {}, will use layout/role label", rawLang, index);
            }
        }
        
        // Extract title (e.g. "Director's Commentary") — trim and null-out empties
        String rawTitle = tags.path("title").asText(null);
        String title = rawTitle != null ? rawTitle.trim() : null;
        if (title != null && title.isEmpty()) title = null;
        track.title = title;

        track.channels = stream.path("channels").asInt(0);
        JsonNode disposition = stream.path("disposition");
        track.isDefault = disposition.path("default").asInt() == 1;

        String langPart = track.languageName;
        String layout = channelLayout(track.channels);
        StringBuilder display = new StringBuilder();
        if (langPart != null) {
            display.append(langPart);
        }
        if (layout != null) {
            if (display.length() > 0) display.append(" ");
            display.append(layout);
        }
        if (display.length() == 0) {
            if (title != null) {
                display.append(title);
            } else if (track.isDefault) {
                display.append("Default");
            } else {
                display.append("Audio");
            }
        } else if (title != null && !title.equalsIgnoreCase(display.toString()) && (langPart == null || !title.equalsIgnoreCase(langPart))) {
            if (!display.toString().contains(title)) {
                display.append(" (").append(title).append(")");
            }
        }
        track.displayName = display.toString();
        
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
        
        track.filename = String.format("audio_%d.%s", index, codec);
        
        return track;
    }

    private String channelLayout(Integer channels) {
        if (channels == null || channels == 0) return null;
        if (channels == 1) return "Mono";
        if (channels == 2) return "Stereo";
        if (channels == 6) return "5.1";
        if (channels == 8) return "7.1";
        return channels + "ch";
    }
}
