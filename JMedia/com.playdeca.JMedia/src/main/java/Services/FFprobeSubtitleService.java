package Services;

import Models.SubtitleTrack;
import Models.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.HashMap;

import Models.Settings;

/**
 * Service for extracting subtitle information using FFprobe and extracting tracks with FFmpeg
 */
@ApplicationScoped
public class FFprobeSubtitleService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FFprobeSubtitleService.class);
    
    @Inject
    ObjectMapper objectMapper;

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    SettingsService settingsService;
    
    // Text-based subtitle codecs that can be streamed/converted to WebVTT
    private static final List<String> STREAMABLE_SUBTITLE_CODECS = List.of(
        "subrip", "ass", "ssa", "webvtt", "mov_text"
    );
    
    // Image-based subtitle codecs (PGS/DVD) - cannot be directly converted to WebVTT
    // These require OCR processing and are excluded from player listings
    private static final List<String> IMAGE_BASED_CODECS = List.of(
        "dvd_subtitle", "pgssub", "hdmv_pgs_subtitle", "dvb_subtitle"
    );
    
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
    }
    
    /**
     * Extract subtitle tracks from a video file using FFprobe
     */
    @Transactional
    public List<SubtitleTrack> extractSubtitleTracks(Video video, String videoPath) {
        List<SubtitleTrack> subtitleTracks = new ArrayList<>();
        
        try {
            String ffprobePath = discoveryService.findFFprobeExecutable();
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
            
            Process process = pb.start();
            JsonNode root = objectMapper.readTree(process.getInputStream());
            JsonNode streams = root.path("streams");
            
            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    String codecType = stream.path("codec_type").asText();
                    if ("subtitle".equals(codecType)) {
                        SubtitleTrack track = parseSubtitleStream(stream, video);
                        if (track != null) {
                            subtitleTracks.add(track);
                        }
                    }
                }
            }
            
            process.waitFor();
            LOGGER.info("Extracted {} subtitle tracks from {}", subtitleTracks.size(), videoPath);
            
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error extracting subtitles with FFprobe", e);
        }
        
        return subtitleTracks;
    }
    
    private SubtitleTrack parseSubtitleStream(JsonNode stream, Video video) {
        String codec = stream.path("codec_name").asText();
        int index = stream.path("index").asInt();
        
        // Skip image-based subtitles (PGS/DVD) - they cannot be streamed as WebVTT
        // These would require OCR processing which is not currently supported
        if (IMAGE_BASED_CODECS.contains(codec)) {
            LOGGER.debug("Skipping image-based subtitle track {} with codec '{}' - not streamable", 
                        index, codec);
            return null;
        }
        
        // Only process text-based subtitle codecs
        if (!STREAMABLE_SUBTITLE_CODECS.contains(codec)) {
            LOGGER.warn("Unknown subtitle codec '{}' for track {} - skipping", codec, index);
            return null;
        }
        
        SubtitleTrack track = new SubtitleTrack();
        track.video = video;
        track.isEmbedded = true;
        track.codec = codec;
        track.trackIndex = index;
        track.fullPath = video.path; // Use video path as full path for embedded tracks
        
        // Extract language from tags
        JsonNode tags = stream.path("tags");
        String langCode = tags.path("language").asText("und");
        track.languageCode = langCode;
        track.languageName = LANGUAGE_MAP.getOrDefault(langCode, langCode.toUpperCase());
        
        // Extract title or use language as display name
        String title = tags.path("title").asText("");
        if (title.isEmpty()) {
            track.displayName = track.languageName;
        } else {
            track.displayName = String.format("%s - %s", track.languageName, title);
        }
        
        // Disposition
        JsonNode disposition = stream.path("disposition");
        track.isDefault = disposition.path("default").asInt() == 1;
        track.isForced = disposition.path("forced").asInt() == 1;
        track.isSDH = disposition.path("hearing_impaired").asInt() == 1;
        
        if ("ass".equals(codec) || "ssa".equals(codec)) {
            track.format = codec;
            track.filename = String.format("internal_%d.%s", index, codec);
        } else {
            track.format = "vtt";
            track.filename = String.format("internal_%d.vtt", index);
        }
        
        return track;
    }

    /**
     * Extract an internal subtitle track and convert to WebVTT string on-the-fly
     */
    public String extractInternalSubtitleToVTT(SubtitleTrack track, double startOffset) throws IOException {
        if (!track.isEmbedded || track.trackIndex == null || track.video == null) {
            throw new IllegalArgumentException("Track is not an embedded subtitle track");
        }
        
        // Check if this is an image-based subtitle that cannot be converted to WebVTT
        if (track.codec != null && IMAGE_BASED_CODECS.contains(track.codec)) {
            throw new IOException("Image-based subtitles (" + track.codec + ") cannot be converted to WebVTT. " +
                                "OCR processing is required for PGS/DVD subtitle formats.");
        }

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found");
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path baseFilePath = Paths.get(track.video.path);
        Path filePath = baseFilePath.isAbsolute() ? baseFilePath : Paths.get(videoLibraryPath, track.video.path);

        // Extract full subtitle track WITHOUT seeking - preserve original timestamps
        // Browser handles sync based on video.currentTime
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-v");
        command.add("quiet");
        command.add("-i");
        command.add(filePath.toAbsolutePath().toString());
        
        // Don't use -ss - return full track with original timestamps
        // Browser will sync based on video.currentTime
        
        command.addAll(List.of(
            "-map", "0:" + track.trackIndex,
            "-f", "webvtt",
            "-"
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        try {
            if (process.waitFor() != 0) {
                throw new IOException("FFmpeg failed to extract subtitle track " + track.trackIndex);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted");
        }

        return output.toString();
    }

    /**
     * Extract raw ASS/SSA subtitle content from an embedded track, preserving all styling.
     * Uses a temporary file for extraction since FFmpeg's ASS muxer is not available in all builds.
     */
    public String extractRawSubtitle(SubtitleTrack track) throws IOException {
        if (!track.isEmbedded || track.trackIndex == null || track.video == null) {
            throw new IllegalArgumentException("Track is not an embedded subtitle track");
        }

        String codec = track.codec != null ? track.codec : track.format;
        if (!"ass".equals(codec) && !"ssa".equals(codec)) {
            throw new IOException("Track codec is not ASS/SSA: " + codec);
        }

        String ffmpegPath = discoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg not found");
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path baseFilePath = Paths.get(track.video.path);
        Path filePath = baseFilePath.isAbsolute() ? baseFilePath : Paths.get(videoLibraryPath, track.video.path);

        // Write to a temp .ass file, then read it back
        Path tempFile = Files.createTempFile("subs_", ".ass");
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-v");
            command.add("quiet");
            command.add("-y");
            command.add("-i");
            command.add(filePath.toAbsolutePath().toString());
            command.addAll(List.of(
                "-map", "0:" + track.trackIndex,
                "-c:s", "copy",
                tempFile.toAbsolutePath().toString()
            ));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    LOGGER.debug("FFmpeg: {}", line);
                }
            }

            if (process.waitFor() != 0) {
                throw new IOException("FFmpeg failed to extract raw subtitle track " + track.trackIndex);
            }

            return Files.readString(tempFile, java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted");
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOGGER.warn("Failed to delete temp subtitle file: {}", tempFile);
            }
        }
    }
}
