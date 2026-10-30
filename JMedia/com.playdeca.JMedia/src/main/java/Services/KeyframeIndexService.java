package Services;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves the keyframe a stream-copy seek will actually land on.
 *
 * <p>ffmpeg cannot cut mid-GOP when the video is copied, so an {@code -ss N} on a
 * copy stream starts at the previous keyframe K and hands the difference back as
 * negative timestamps. The stream path normalises those to zero, so element time 0
 * is content K — but subtitle cues are shifted by the requested N, which leaves them
 * off by exactly {@code N - K}. That is the "subtitles are a second or two out, and
 * the amount changes when I seek" symptom.
 *
 * <p>Both the stream path and the subtitle path call this so they agree on the same
 * position. The result is cached per source file and seek point (with a hard cap) so
 * repeated range requests for one segment do not re-probe.
 */
@ApplicationScoped
public class KeyframeIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(KeyframeIndexService.class);

    /** Look this far before the target for the preceding keyframe. */
    private static final double LOOKBACK_SECONDS = 15.0;

    /** Never snap back further than this — a huge jump looks worse than the offset. */
    private static final double MAX_SNAP_SECONDS = 10.0;

    private static final long PROBE_TIMEOUT_SECONDS = 20;
    private static final int MAX_CACHE_ENTRIES = 4096;

    private final Map<String, Double> cache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheSize = new AtomicInteger();

    /**
     * Returns the keyframe time a copy seek to {@code atSeconds} would start at.
     *
     * @param source      the media file being seeked
     * @param atSeconds   the requested position; non-positive values are returned unchanged
     * @return the snapped position (equal to {@code atSeconds} when it is already a
     *         keyframe, when no keyframe is close enough, or when probing fails)
     */
    public double snapToKeyframe(Path source, double atSeconds) {
        if (source == null || atSeconds <= 0.5) {
            return atSeconds;
        }
        String key = null;
        try {
            FileTime modified = Files.getLastModifiedTime(source);
            key = source.toAbsolutePath() + "|" + modified.toMillis() + "|"
                    + String.format(Locale.ROOT, "%.3f", atSeconds);
            Double hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        } catch (IOException e) {
            LOG.debug("Cannot stat {} for keyframe lookup: {}", source, e.getMessage(), e);
        }

        double snapped = probe(source, atSeconds);
        if (key != null) {
            if (cacheSize.incrementAndGet() > MAX_CACHE_ENTRIES) {
                cache.clear();
                cacheSize.set(1);
            }
            cache.put(key, snapped);
        }
        return snapped;
    }

    private double probe(Path source, double atSeconds) {
        // Ask the demuxer where a seek actually lands: reading ONE packet from the seek
        // point reports the keyframe ffmpeg itself starts at. Verified against a real
        // copy seek to 120s (first output PTS -2.252 => starts at 117.743): this probe
        // returns 117.743. Sequential window scans were unreliable — the 105..121 window
        // reported up to 116.408 and missed 117.743, which is exactly the residual
        // "still a second or two behind" after a seek.
        String interval = String.format(Locale.ROOT, "%.3f%%+#1", atSeconds);
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-read_intervals", interval,
                "-show_entries", "packet=pts_time,flags",
                "-of", "csv=p=0",
                source.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = null;
        try {
            process = pb.start();
            double keyframe = -1.0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String[] parts = trimmed.split(",");
                    if (parts.length < 2 || !parts[1].toUpperCase(Locale.ROOT).contains("K")) {
                        continue;
                    }
                    try {
                        keyframe = Double.parseDouble(parts[0].trim());
                    } catch (NumberFormatException ignored) {
                        // ffprobe prints headers/notes on some inputs; skip them.
                    }
                    break;
                }
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Keyframe probe for {} at {}s timed out", source.getFileName(), atSeconds);
                process.destroyForcibly();
                return atSeconds;
            }
            if (keyframe < 0) {
                LOG.debug("No keyframe reported for a seek to {}s in {}; keeping the requested position",
                        atSeconds, source.getFileName());
                return atSeconds;
            }
            if (keyframe > atSeconds + 0.5) {
                LOG.debug("Keyframe probe for {} returned {}s, past the requested {}s; keeping the request",
                        source.getFileName(), String.format(Locale.ROOT, "%.3f", keyframe), atSeconds);
                return atSeconds;
            }
            if (atSeconds - keyframe > MAX_SNAP_SECONDS) {
                LOG.info("Previous keyframe for {} is {}s before the request ({}s); not snapping",
                        source.getFileName(),
                        String.format(Locale.ROOT, "%.2f", atSeconds - keyframe),
                        String.format(Locale.ROOT, "%.2f", atSeconds));
                return atSeconds;
            }
            if (atSeconds - keyframe >= 0.05) {
                LOG.info("Snapshot start for {}: requested {}s -> keyframe {}s",
                        source.getFileName(),
                        String.format(Locale.ROOT, "%.3f", atSeconds),
                        String.format(Locale.ROOT, "%.3f", keyframe));
            }
            return keyframe;
        } catch (IOException e) {
            LOG.warn("Keyframe probe failed for {} at {}s: {}", source.getFileName(), atSeconds, e.getMessage(), e);
            return atSeconds;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return atSeconds;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
