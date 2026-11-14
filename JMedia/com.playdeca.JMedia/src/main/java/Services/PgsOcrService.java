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
 * decodes each display set to an ARGB bitmap, then each bitmap keeps the bright glyph
 * fill (dropping the dark outline), is upscaled 3x and OCR'd with
 * {@code tesseract --psm 6 --oem 1}. Cues use the PGS 90 kHz PTS clock; the result is
 * cached per track so playback never re-runs OCR.
 */
@ApplicationScoped
public class PgsOcrService {

    private static final Logger LOG = LoggerFactory.getLogger(PgsOcrService.class);

    private static final int ALPHA_THRESHOLD = 64;
    private static final int LUMINANCE_THRESHOLD = 100;
    private static final int UPSCALE_FACTOR = 3;
    /** White margin around the bitmap: Tesseract reads blocks better with breathing room. */
    private static final int OCR_BORDER = 24;

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
            if (!(isPgsCodec(track.codec) || "pgs".equals(track.format))) {
                continue;
            }
            // Skip tracks that can never be OCR'd (missing stream index) so they don't
            // spam the log with ERRORs on every preload pass. A missing fullPath is not
            // disqualifying: embedded tracks carry no sidecar path and the owning
            // video's file is used instead.
            if (track.trackIndex == null) {
                LOG.debug("Skipping PGS track {} (id={}): missing stream index", track.trackIndex, track.id);
                continue;
            }
            preload(track);
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
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("missing stream index or video path")) {
                    LOG.warn("PGS OCR skipped for track {} ({}): {}", track.id, track.fullPath, msg);
                } else {
                    LOG.error("PGS OCR failed for track {} ({}): {}", track.id, track.fullPath, msg, e);
                }
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
        if (track.trackIndex == null) {
            throw new IOException("PGS track is missing its stream index");
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        // Embedded tracks store no sidecar path (fullPath is null by design — the
        // subtitle lives inside the video), so resolve the owning video's file. The
        // old code only looked at fullPath and every embedded PGS track failed with
        // "PGS track is missing stream index or video path", which surfaced in the
        // player as HTTP 500 on the subtitle request.
        Path filePath;
        if (track.fullPath != null && !track.fullPath.isBlank()) {
            Path baseFilePath = Paths.get(track.fullPath);
            filePath = baseFilePath.isAbsolute() ? baseFilePath : Paths.get(videoLibraryPath, track.fullPath);
        } else {
            Models.Video.Video owner = track.video;
            if (owner == null || owner.path == null) {
                throw new IOException("PGS track " + track.id + " has no fullPath and no owning video path");
            }
            Path baseFilePath = Paths.get(owner.path);
            filePath = baseFilePath.isAbsolute() ? baseFilePath : Paths.get(videoLibraryPath, owner.path);
        }

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

                double start = sub.startSeconds;
                String text = ocrImages(sub.images, workDir, tesseract);
                if (text == null || text.isBlank()) {
                    continue;
                }
                // Hold each cue until the next line starts. Tried ending at the
                // PGS clear display set (1.37.47) but partial/fade display sets
                // then stayed on screen as their own cues, which read worse than
                // the occasional linger across a silence.
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

    /** One binarization variant's reading plus the mean Tesseract word confidence. */
    private static class OcrResult {
        final String text;
        final double confidence;

        OcrResult(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private String ocrImages(List<BufferedImage> images, File workDir, String tesseract) throws Exception {
        List<String> lines = new ArrayList<>();
        String tessdataDir = tessdataDirectory();
        for (int imgIdx = 0; imgIdx < images.size(); imgIdx++) {
            BufferedImage src = images.get(imgIdx);
            if (src.getWidth() <= 0 || src.getHeight() <= 0) {
                continue;
            }

            OcrResult bright = ocrVariant(src, true, workDir, tesseract, tessdataDir, imgIdx);
            OcrResult chosen = bright;
            // Bright-on-dark dialogue is the norm. Only fall back to the dark fill
            // when the bright pass read nothing at all: low-confidence bright text is
            // still usually better than the outline-heavy dark variant.
            if (bright.text.isBlank()) {
                OcrResult dark = ocrVariant(src, false, workDir, tesseract, tessdataDir, imgIdx);
                if (!dark.text.isBlank()) {
                    chosen = dark;
                }
            }
            if (!chosen.text.isBlank()) {
                lines.add(chosen.text);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Binarizes the bitmap to dark text on white (keeping the bright fill for normal
     * dialogue, or the dark fill for inverted signs), upscales it 3x, adds the white
     * margin Tesseract expects, and reads it with TSV output so the caller can compare
     * variants by word confidence.
     */
    private OcrResult ocrVariant(BufferedImage src, boolean bright, File workDir, String tesseract,
                                 String tessdataDir, int imgIdx) throws Exception {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (299 * r + 587 * g + 114 * b) / 1000;
                boolean opaque = a >= ALPHA_THRESHOLD;
                // Bright variant: keep the glyph fill and drop the dark outline.
                // Dark variant: keep dark glyphs, for inverted/white-box captions.
                boolean text = opaque && (bright ? lum >= LUMINANCE_THRESHOLD : lum < LUMINANCE_THRESHOLD);
                bin.setRGB(x, y, text ? 0x000000 : 0xFFFFFF);
            }
        }

        int pad = OCR_BORDER;
        int upW = w * UPSCALE_FACTOR + 2 * pad;
        int upH = h * UPSCALE_FACTOR + 2 * pad;
        BufferedImage up = new BufferedImage(upW, upH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = up.createGraphics();
        g2.setColor(java.awt.Color.WHITE);
        g2.fillRect(0, 0, upW, upH);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(bin, pad, pad, w * UPSCALE_FACTOR, h * UPSCALE_FACTOR, null);
        g2.dispose();

        File png = new File(workDir, "img_" + System.nanoTime() + "_" + imgIdx + (bright ? "_b" : "_d") + ".png");
        ImageIO.write(up, "png", png);
        try {
            return runTesseract(tesseract, png, tessdataDir);
        } finally {
            png.delete();
        }
    }

    private OcrResult runTesseract(String tesseract, File png, String tessdataDir) throws Exception {
        List<String> args = new ArrayList<>(List.of(tesseract, png.getAbsolutePath(), "stdout",
                "--psm", "6", "--oem", "1", "-l", "eng",
                "-c", "preserve_interword_spaces=1"));
        if (tessdataDir != null) {
            args.add("--tessdata-dir");
            args.add(tessdataDir);
        }
        args.add("tsv");

        ProcessBuilder pb = new ProcessBuilder(args);
        Process process = pb.start();

        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = er.readLine()) != null) {
                    if (err.length() > 0) err.append(' ');
                    err.append(line.trim());
                }
            } catch (IOException e) {
                LOG.debug("Could not read Tesseract stderr: {}", e.getMessage());
            }
        }, "pgs-ocr-stderr");
        errReader.setDaemon(true);
        errReader.start();

        StringBuilder text = new StringBuilder();
        double confSum = 0;
        int wordCount = 0;
        int lastBlock = -1, lastPar = -1, lastLine = -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length < 12) continue;
                // level 5 = word; block/par/line/word/conf/text
                if (!"5".equals(cols[0])) continue;
                String word = cols[11].trim();
                if (word.isEmpty()) continue;

                int block, par, ln;
                try {
                    block = Integer.parseInt(cols[2]);
                    par = Integer.parseInt(cols[3]);
                    ln = Integer.parseInt(cols[4]);
                } catch (NumberFormatException e) {
                    LOG.debug("Skipping malformed Tesseract TSV row for track OCR: {}", line);
                    continue;
                }
                if (block != lastBlock || par != lastPar || ln != lastLine) {
                    if (text.length() > 0) text.append('\n');
                    lastBlock = block;
                    lastPar = par;
                    lastLine = ln;
                } else if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(word);

                double conf = 0;
                try {
                    conf = Double.parseDouble(cols[10]);
                } catch (NumberFormatException e) {
                    LOG.debug("Unparseable Tesseract confidence '{}' for word '{}'", cols[10], word);
                }
                if (conf >= 0) {
                    confSum += conf;
                    wordCount++;
                }
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Tesseract timed out");
        }
        try {
            errReader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while waiting for Tesseract stderr: {}", e.getMessage());
        }
        if (process.exitValue() != 0) {
            LOG.warn("Tesseract failed (exit {}): {}", process.exitValue(), err);
            return new OcrResult("", 0);
        }
        if (err.length() > 0) {
            LOG.debug("Tesseract stderr: {}", err);
        }
        String result = text.toString().trim();
        double confidence = wordCount > 0 ? confSum / wordCount : 0;
        return new OcrResult(result, confidence);
    }

    /** The bundled tessdata_best model, when the operator placed one next to the app. */
    private String tessdataDirectory() {
        try {
            Path dir = Paths.get("tessdata");
            if (Files.isRegularFile(dir.resolve("eng.traineddata"))) {
                return dir.toAbsolutePath().toString();
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not resolve the bundled tessdata directory: {}", e.getMessage());
        }
        return null;
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
            // v5: bright-fill binarization, 3x bicubic, white margin, TSV confidence;
            // the dark-fill variant only fills cues the bright pass could not read.
            // Kept as v5 after the 1.37.47 timing experiment was reverted.
            return "track_" + track.id + "_v5";
        }
        return (track.fullPath != null ? track.fullPath : "unknown") + "#" + track.trackIndex + "_v5";
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
