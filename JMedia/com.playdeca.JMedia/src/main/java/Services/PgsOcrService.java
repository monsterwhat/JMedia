package Services;

import Models.Video.SubtitleTrack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Converts embedded HDMV PGS (hdmv_pgs_subtitle) tracks to WebVTT via Tesseract OCR.
 * <p>
 * Flow: ffmpeg extracts the .sup stream with original timestamps, PgsSubtitleParser
 * decodes each display set to an ARGB bitmap, then each bitmap is binarized
 * (alpha >= threshold -&gt; black, else white), upscaled 2x and OCR'd with
 * {@code tesseract --psm 13}. Cues use the PGS 90 kHz PTS clock; the result is
 * cached per track so playback never re-runs OCR.
 */
@ApplicationScoped
public class PgsOcrService {

    private static final Logger LOG = LoggerFactory.getLogger(PgsOcrService.class);

    private static final int ALPHA_THRESHOLD = 150;
    private static final int UPSCALE_FACTOR = 2;

    /**
     * Working-directory-relative cache folder for generated PGS VTT files, mirroring the
     * thumbnail/storyboard convention. Persisting OCR output to disk means a server restart
     * (which clears the in-memory vttCache) does not re-extract and re-OCR every track.
     */
    private static final String PGS_VTT_DIR = "pgs-subtitles";

    @Inject
    FFmpegDiscoveryService ffmpegDiscoveryService;

    @Inject
    TesseractDiscoveryService tesseractDiscoveryService;

    @Inject
    SettingsService settingsService;

    @Inject
    TranscodingService transcodingService;

    private final ConcurrentHashMap<String, String> vttCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> failedUntil = new ConcurrentHashMap<>();

    private static final long OCR_FAILURE_COOLDOWN_MS = 30 * 60_000L;
    private static final long TRANSCODE_DEFER_MS = 120_000L;
    private volatile long deferStartedAt = 0;

    public static boolean isPgsCodec(String codec) {
        return codec != null && ("hdmv_pgs_subtitle".equals(codec) || "pgssub".equals(codec));
    }

    /**
     * Return the OCR'd WebVTT for a PGS track, generating (and caching) it on first use.
     */
    public String getOrCreateWebVTT(SubtitleTrack track) throws Exception {
        String key = cacheKey(track);
        String cached = vttCache.get(key);
        if (cached != null) {
            return cached;
        }
        Path diskFile = diskFileForKey(key);
        if (Files.exists(diskFile)) {
            try {
                String vtt = Files.readString(diskFile, StandardCharsets.UTF_8);
                vttCache.put(key, vtt);
                return vtt;
            } catch (IOException e) {
                LOG.warn("Failed to read cached PGS VTT {} - regenerating: {}", diskFile, e.getMessage());
            }
        }
        try {
            return getOrCreateFuture(key, track).get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("PGS OCR failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Kick off OCR in the background so the cache is warm before playback requests the VTT.
     */
    public void preload(SubtitleTrack track) {
        String key = cacheKey(track);
        if (vttCache.containsKey(key) || pending.containsKey(key) || Files.exists(diskFileForKey(key))) {
            return;
        }
        getOrCreateFuture(key, track);
    }

    /**
     * Preload every PGS track of a video. Tracks must be re-queried from the DB so
     * they carry an id: the cache key for persisted tracks is "track_<id>", which is
     * exactly the key the stream endpoint (SubtitleTrack.findById) will use later.
     */
    public void preloadForVideo(Long videoId) {
        if (videoId == null) {
            return;
        }
        List<SubtitleTrack> tracks = SubtitleTrack.list("video.id", videoId);
        for (SubtitleTrack track : tracks) {
            if (isPgsCodec(track.codec) || "pgs".equals(track.format)) {
                preload(track);
            }
        }
    }

    private CompletableFuture<String> getOrCreateFuture(String key, SubtitleTrack track) {
        return pending.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                String vtt = generateWebVTT(track);
                vttCache.put(k, vtt);
                persistToDisk(k, vtt);
                return vtt;
            } catch (Exception e) {
                LOG.error("PGS OCR failed for track {} ({}): {}", track.id, track.fullPath, e.getMessage(), e);
                throw new CompletionException(e);
            } finally {
                pending.remove(k);
            }
        }));
    }

    private String generateWebVTT(SubtitleTrack track) throws Exception {
        String tesseract = tesseractDiscoveryService.findTesseractExecutable();
        if (tesseract == null) {
            throw new IOException("Tesseract OCR is not installed. Install it from Settings > Import Setup.");
        }
        String ffmpeg = ffmpegDiscoveryService.findFFmpegExecutable();
        if (ffmpeg == null) {
            throw new IOException("FFmpeg is not installed.");
        }
        if (track.trackIndex == null || track.fullPath == null) {
            throw new IOException("PGS track is missing stream index or video path");
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path baseFilePath = Paths.get(track.fullPath);
        Path filePath = baseFilePath.isAbsolute() ? baseFilePath : Paths.get(videoLibraryPath, track.fullPath);

        Path tempSup = Files.createTempFile("pgs_ocr_", ".sup");
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpeg);
            command.add("-v");
            command.add("quiet");
            command.add("-y");
            command.add("-i");
            command.add(filePath.toAbsolutePath().toString());
            command.add("-map");
            command.add("0:" + track.trackIndex);
            command.add("-c:s");
            command.add("copy");
            command.add("-f");
            command.add("sup");
            command.add(tempSup.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("FFmpeg timed out extracting PGS stream");
            }
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg failed to extract PGS stream (exit " + process.exitValue() + ")");
            }

            byte[] supData = Files.readAllBytes(tempSup);
            if (supData.length == 0) {
                throw new IOException("Extracted PGS stream is empty");
            }

            List<PgsSubtitleParser.PgsSubtitle> subs = PgsSubtitleParser.parse(supData);
            if (subs.isEmpty()) {
                throw new IOException("No PGS subtitle events found in stream");
            }

            return buildWebVTT(subs, tesseract);
        } finally {
            try {
                Files.deleteIfExists(tempSup);
            } catch (IOException e) {
                LOG.debug("Failed to delete temp sup file: {}", tempSup);
            }
        }
    }

    private String buildWebVTT(List<PgsSubtitleParser.PgsSubtitle> subs, String tesseract) throws Exception {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        File workDir = Files.createTempDirectory("pgs_ocr_png_").toFile();
        try {
            for (int i = 0; i < subs.size(); i++) {
                PgsSubtitleParser.PgsSubtitle sub = subs.get(i);
                if (sub.images == null || sub.images.isEmpty()) {
                    continue;
                }

                String text = ocrImages(sub.images, workDir, tesseract);
                if (text == null || text.isBlank()) {
                    continue;
                }

                double start = sub.startSeconds;
                double end = (i + 1 < subs.size()) ? subs.get(i + 1).startSeconds : start + 3.0;
                if (end <= start) {
                    end = start + 1.0;
                }

                sb.append(formatTimestamp(start)).append(" --> ").append(formatTimestamp(end)).append("\n");
                sb.append(text).append("\n\n");
            }
        } finally {
            File[] files = workDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            workDir.delete();
        }
        return sb.toString();
    }

    private String ocrImages(List<BufferedImage> images, File workDir, String tesseract) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int imgIdx = 0; imgIdx < images.size(); imgIdx++) {
            BufferedImage src = images.get(imgIdx);
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) {
                continue;
            }

            BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (src.getRGB(x, y) >> 24) & 0xFF;
                    bin.setRGB(x, y, a >= ALPHA_THRESHOLD ? 0x000000 : 0xFFFFFF);
                }
            }

            BufferedImage up = new BufferedImage(w * UPSCALE_FACTOR, h * UPSCALE_FACTOR, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h * UPSCALE_FACTOR; y++) {
                for (int x = 0; x < w * UPSCALE_FACTOR; x++) {
                    up.setRGB(x, y, bin.getRGB(x / UPSCALE_FACTOR, y / UPSCALE_FACTOR));
                }
            }

            File png = new File(workDir, "img_" + System.nanoTime() + "_" + imgIdx + ".png");
            ImageIO.write(up, "png", png);
            try {
                String result = runTesseract(tesseract, png);
                if (result != null && !result.isBlank()) {
                    lines.add(result);
                }
            } finally {
                png.delete();
            }
        }
        return String.join("\n", lines);
    }

    private String runTesseract(String tesseract, File png) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(tesseract, png.getAbsolutePath(), "stdout", "--psm", "13");
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    out.append(trimmed).append("\n");
                }
            }
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Tesseract timed out");
        }
        String result = out.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String formatTimestamp(double seconds) {
        long totalMs = (long) Math.round(seconds * 1000);
        long h = totalMs / 3600000;
        long m = (totalMs % 3600000) / 60000;
        long s = (totalMs % 60000) / 1000;
        long ms = totalMs % 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
    }

    private String cacheKey(SubtitleTrack track) {
        if (track.id != null) {
            return "track_" + track.id;
        }
        return (track.fullPath != null ? track.fullPath : "unknown") + "#" + track.trackIndex;
    }

    /**
     * Cache directory for persisted PGS VTT files, following the same convention as the
     * thumbnail/storyboard directories: a working-directory-relative folder, auto-created.
     */
    private Path getPgsVttDirectory() {
        try {
            Path dir = Paths.get(PGS_VTT_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (IOException e) {
            LOG.error("Error creating PGS VTT directory: " + e.getMessage());
            return Paths.get(".");
        }
    }

    private Path diskFileForKey(String key) {
        String safeName = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return getPgsVttDirectory().resolve(safeName + ".vtt");
    }

    private void persistToDisk(String key, String vtt) {
        try {
            Path target = diskFileForKey(key);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, vtt, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to persist PGS VTT for {}: {}", key, e.getMessage());
        }
    }
}
