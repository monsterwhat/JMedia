package Services;

import Models.Video.AudioTrack;
import Models.Video.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MediaAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(MediaAnalysisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    FFprobeAudioService audioService;

    @Inject
    AudioPreferenceEngine audioPreferenceEngine;

    @PersistenceContext(unitName = "video")
    EntityManager entityManager;

    @Transactional
    public void analyze(Video video) {
        if (video == null || video.path == null) return;
        
        // Generate hash first to help identify moved files
        video.mediaHash = generateFingerprint(video.path);

        JsonNode root = probe(video.path);
        if (root != null) {
            populateVideoMetadata(video, root);
            double containerSec = video.duration != null ? video.duration / 1000.0 : 0;
            double effective = resolveEffectiveDuration(root, video.path, containerSec);
            if (effective > 0 && Math.abs(effective - containerSec) > 1) {
                video.duration = Math.round(effective * 1000);
            }
            
            // Extract and persist audio tracks immediately during initial scan
            extractAndPersistAudioTracks(video);
            
            LOG.info("Successfully analyzed media for: {}", video.path);
        }
    }

    public void analyze(Models.Video.MediaFile mediaFile) {
        if (mediaFile == null || mediaFile.path == null) return;
        
        if (mediaFile.mediaHash == null) {
            mediaFile.mediaHash = generateFingerprint(mediaFile.path);
        }

        JsonNode root = probe(mediaFile.path);
        if (root != null) {
            populateMediaFileMetadata(mediaFile, root);
            double effective = resolveEffectiveDuration(root, mediaFile.path, mediaFile.durationSeconds);
            if (effective > 0 && Math.abs(effective - mediaFile.durationSeconds) > 1) {
                mediaFile.durationSeconds = (int) Math.round(effective);
            }
            LOG.info("Successfully analyzed media file for: {}", mediaFile.path);
        }
    }

    /**
     * The container duration is not always the length of what can be played. An MKV
     * whose subtitle tracks run past the picture reports the subtitle length, so a
     * 23:52 episode showed as 34:33 with ten minutes of dead air: the player drew a
     * bar for content that does not exist and stopped itself at the real end.
     *
     * <p>When the probe gives no per-stream duration to corroborate the container
     * (typical for MKV), the last video packet is measured instead. That costs one
     * extra demux pass per file at scan time, so it only runs when there is nothing
     * cheaper to trust.
     */
    private double resolveEffectiveDuration(JsonNode root, String path, double containerSeconds) {
        if (containerSeconds <= 0) return containerSeconds;

        double streamSeconds = 0;
        boolean primaryVideoSeen = false;
        for (JsonNode stream : root.path("streams")) {
            if (!"video".equals(stream.path("codec_type").asText())) {
                continue;
            }
            if (primaryVideoSeen) {
                break;
            }
            primaryVideoSeen = true;
            // Some MKVs carry an extra attached video stream (cover art) whose duration
            // is the CONTAINER duration. Only the first video stream speaks for the
            // file, and it is the one the packet scan measures.
            JsonNode d = stream.path("duration");
            if (d.isMissingNode() || d.isNull()) {
                break;
            }
            try {
                streamSeconds = Double.parseDouble(d.asText());
            } catch (NumberFormatException ignored) {
                streamSeconds = 0;
            }
            break;
        }
        if (streamSeconds > 0 && Math.abs(containerSeconds - streamSeconds) <= 30) {
            return containerSeconds;
        }

        double mediaEnd = probeLastVideoPacketSeconds(path);
        if (mediaEnd <= 0) return containerSeconds;
        if (containerSeconds - mediaEnd > 30) {
            LOG.warn("Container duration for {} is {}s but the media ends at {}s "
                    + "(subtitle/attachment tail); using the media end",
                    path, Math.round(containerSeconds), Math.round(mediaEnd));
            return mediaEnd;
        }
        return containerSeconds;
    }

    /**
     * One-off maintenance: re-probe a video whose stored duration may be the container
     * length instead of the playable length, and persist the corrected value.
     *
     * <p>The probe shells out to ffprobe, so it runs outside any transaction; only the
     * short read and the update are transactional.
     *
     * @return true when the stored duration was corrected
     */
    public boolean repairDurationIfStretched(Long videoId) {
        Object[] row = QuarkusTransaction.requiringNew().call(() -> {
            Video v = Video.findById(videoId);
            if (v == null || v.path == null || v.duration == null || v.duration <= 0) {
                return null;
            }
            return new Object[]{ v.path, v.duration / 1000.0 };
        });
        if (row == null) return false;

        String path = (String) row[0];
        double stored = (Double) row[1];

        JsonNode root = probe(path);
        if (root == null) return false;

        double effective = resolveEffectiveDuration(root, path, stored);
        if (effective <= 0 || stored - effective <= 30) return false;

        QuarkusTransaction.requiringNew().run(() -> {
            Video v = Video.findById(videoId);
            if (v == null) return;
            v.duration = Math.round(effective * 1000);
            v.persist();
        });
        LOG.info("Duration repaired for {} (id={}): {}s -> {}s",
                path, videoId, Math.round(stored), Math.round(effective));
        return true;
    }

    /** Last video packet timestamp, i.e. where the picture actually stops. */
    private double probeLastVideoPacketSeconds(String path) {
        String ffprobePath = discoveryService.findFFprobeExecutable();
        if (ffprobePath == null) return 0;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "packet=pts_time",
                    "-of", "csv=p=0", path);
            Process process = pb.start();
            // Read before waiting: the packet list outgrows the pipe buffer, so
            // waiting first would deadlock.
            String out = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("Media-end probe timed out for: {}", path);
                return 0;
            }
            if (process.exitValue() != 0 || out.isEmpty()) return 0;
            String[] lines = out.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String v = lines[i].trim();
                if (v.isEmpty()) continue;
                try {
                    return Double.parseDouble(v);
                } catch (NumberFormatException ignored) {
                    // header or "N/A" line: keep looking upward
                }
            }
            return 0;
        } catch (Exception e) {
            LOG.warn("Could not determine media end for {}: {}", path, e.getMessage());
            return 0;
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

    private void populateMediaFileMetadata(Models.Video.MediaFile mediaFile, JsonNode root) {
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
                } else {
                    mediaFile.hasMultipleAudioTracks = true;
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
            String formatName = format.get("format_name").asText();
            // FFprobe reports MP4 container as "mov,mp4,m4a,3gp,3g2,mj2" — check all tokens
            video.container = formatName.contains("mp4") ? "mp4" : formatName.split(",")[0];
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
                } else {
                    video.hasMultipleAudioTrack = true;
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

    /**
     * Extracts audio tracks via ffprobe and persists them for the given video.
     * Returns the extracted tracks (possibly empty for unreadable files or files
     * with no audio streams); callers use the result for negative caching.
     */
    public List<AudioTrack> extractAndPersistAudioTracks(Video video) {
        List<AudioTrack> tracks = new ArrayList<>();
        try {
            tracks = audioService.extractAudioTracks(video, video.path);
            if (tracks != null && !tracks.isEmpty()) {
                for (AudioTrack track : tracks) {
                    track.video = video;
                    entityManager.persist(track);
                }
                video.hasMultipleAudioTrack = tracks.size() > 1;
                LOG.info("Extracted and persisted {} audio tracks for: {}", tracks.size(), video.path);
                if (video.defaultAudioTrackId == null) {
                    try {
                        String prefLang = video.preferredAudioLanguage != null && !video.preferredAudioLanguage.isBlank()
                                ? video.preferredAudioLanguage : "eng";
                        AudioTrack best = audioPreferenceEngine.selectBestAudioTrack(tracks, prefLang);
                        if (best != null && best.id != null) {
                            video.defaultAudioTrackId = best.id;
                            video.preferredAudioLanguage = best.languageCode;
                            LOG.info("Auto-selected default audio track {} (lang={}, channels={}) for video {}", best.id, best.languageCode, best.channels, video.path);
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to auto-select default audio track for {}: {}", video.path, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not extract audio tracks for {}: {}", video.path, e.getMessage());
            return new ArrayList<>();
        }
        return tracks == null ? new ArrayList<>() : tracks;
    }
}
