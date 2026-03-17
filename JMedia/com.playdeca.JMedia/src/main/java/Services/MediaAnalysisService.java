package Services;

import Models.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MediaAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(MediaAnalysisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    FFmpegDiscoveryService discoveryService;

    public void analyze(Video video) {
        if (video == null || video.path == null) return;
        
        // Generate hash first to help identify moved files
        video.mediaHash = generateFingerprint(video.path);

        JsonNode root = probe(video.path);
        if (root != null) {
            populateVideoMetadata(video, root);
            LOG.info("Successfully analyzed media for: {}", video.path);
        }
    }

    public void analyze(Models.MediaFile mediaFile) {
        if (mediaFile == null || mediaFile.path == null) return;
        
        if (mediaFile.mediaHash == null) {
            mediaFile.mediaHash = generateFingerprint(mediaFile.path);
        }

        JsonNode root = probe(mediaFile.path);
        if (root != null) {
            populateMediaFileMetadata(mediaFile, root);
            LOG.info("Successfully analyzed media file for: {}", mediaFile.path);
        }
    }

    private JsonNode probe(String path) {
        String ffprobePath = discoveryService.findFFprobeExecutable();
        if (ffprobePath == null) {
            LOG.error("FFprobe not found, skipping analysis for: {}", path);
            return null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "error",
                "-show_format",
                "-show_streams",
                "-of", "json",
                path
            );

            Process process = pb.start();
            JsonNode root = objectMapper.readTree(process.getInputStream());
            
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.error("FFprobe timed out for: {}", path);
                return null;
            }

            if (process.exitValue() != 0) {
                LOG.error("FFprobe failed for: {} with exit code {}", path, process.exitValue());
                return null;
            }
            return root;
        } catch (Exception e) {
            LOG.error("Error probing media {}: {}", path, e.getMessage());
            return null;
        }
    }

    private void populateMediaFileMetadata(Models.MediaFile mediaFile, JsonNode root) {
        JsonNode format = root.path("format");
        if (format.has("duration")) {
            mediaFile.durationSeconds = (int) format.get("duration").asDouble();
        }
        if (format.has("size")) {
            mediaFile.size = format.get("size").asLong();
        }

        JsonNode streams = root.path("streams");
        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType)) {
                mediaFile.videoCodec = stream.path("codec_name").asText();
                mediaFile.width = stream.path("width").asInt();
                mediaFile.height = stream.path("height").asInt();
            } else if ("audio".equals(codecType)) {
                if (mediaFile.audioCodec == null) {
                    mediaFile.audioCodec = stream.path("codec_name").asText();
                    mediaFile.audioLanguage = stream.path("tags").path("language").asText("und");
                }
            } else if ("subtitle".equals(codecType)) {
                mediaFile.hasEmbeddedSubtitles = true;
            }
        }
    }

    private void populateVideoMetadata(Video video, JsonNode root) {
        // 1. Format metadata
        JsonNode format = root.path("format");
        if (format.has("duration")) {
            video.duration = (long) (format.get("duration").asDouble() * 1000);
        }
        if (format.has("size")) {
            video.size = format.get("size").asLong();
            video.fileSize = video.size;
        }
        if (format.has("format_name")) {
            video.container = format.get("format_name").asText().split(",")[0];
        }
        if (format.has("bit_rate")) {
            video.bitrate = format.get("bit_rate").asInt();
        }

        // 2. Stream metadata
        JsonNode streams = root.path("streams");
        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            
            if ("video".equals(codecType)) {
                video.videoCodec = stream.path("codec_name").asText();
                video.videoProfile = stream.path("profile").asText();
                video.resolution = stream.path("width").asInt() + "x" + stream.path("height").asInt();
                
                // Aspect Ratio
                if (stream.has("display_aspect_ratio")) {
                    String dar = stream.get("display_aspect_ratio").asText();
                    if (dar.contains(":")) {
                        String[] parts = dar.split(":");
                        try {
                            video.aspectRatio = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
                        } catch (Exception ignored) {}
                    }
                }
                
                // Framerate
                if (stream.has("r_frame_rate")) {
                    String fr = stream.get("r_frame_rate").asText();
                    if (fr.contains("/")) {
                        String[] parts = fr.split("/");
                        try {
                            double num = Double.parseDouble(parts[0]);
                            double den = Double.parseDouble(parts[1]);
                            if (den != 0) video.frameRate = (int) Math.round(num / den);
                        } catch (Exception ignored) {}
                    }
                }
            } else if ("audio".equals(codecType)) {
                // If multiple audio streams, we'll take the first one as primary
                if (video.audioCodec == null) {
                    video.audioCodec = stream.path("codec_name").asText();
                    video.audioProfile = stream.path("profile").asText();
                    video.audioChannels = stream.path("channels").asInt();
                    video.primaryAudioLanguage = stream.path("tags").path("language").asText("und");
                    video.audioBitrate = stream.path("bit_rate").asInt();
                }
            } else if ("subtitle".equals(codecType)) {
                video.hasSubtitles = true;
            }
        }
        
        // Quality indicator
        video.quality = calculateQuality(video.resolution);
        video.displayResolution = calculateDisplayResolution(video.resolution);
    }

    private String calculateQuality(String resolution) {
        if (resolution == null) return "Unknown";
        String[] parts = resolution.split("x");
        if (parts.length != 2) return "Unknown";
        try {
            int height = Integer.parseInt(parts[1]);
            if (height >= 4320) return "8K";
            if (height >= 2160) return "4K";
            if (height >= 1080) return "Full HD";
            if (height >= 720) return "HD";
            return "SD";
        } catch (NumberFormatException e) {
            return "Unknown";
        }
    }

    private String calculateDisplayResolution(String resolution) {
        if (resolution == null) return null;
        String[] parts = resolution.split("x");
        if (parts.length != 2) return resolution;
        try {
            int height = Integer.parseInt(parts[1]);
            if (height >= 2160) return "2160p";
            if (height >= 1080) return "1080p";
            if (height >= 720) return "720p";
            if (height >= 480) return "480p";
            return height + "p";
        } catch (NumberFormatException e) {
            return resolution;
        }
    }

    /**
     * Generates a fast fingerprint of a media file (Plex-style)
     * Uses file size + first 1MB + last 1MB
     */
    public String generateFingerprint(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) return null;

            long size = file.length();
            MessageDigest digest = MessageDigest.getInstance("MD5");
            
            // Add size to digest
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(size).array());

            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
                
                // Read first 1MB
                int read = raf.read(buffer);
                if (read > 0) digest.update(buffer, 0, read);
                
                // Read last 1MB
                if (size > buffer.length) {
                    raf.seek(size - buffer.length);
                    read = raf.read(buffer);
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("Could not generate fingerprint for {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
