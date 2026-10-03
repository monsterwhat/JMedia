package Services;

import Models.Video.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Muxes sidecar subtitles into the media file itself on first play, so
 * progressive (native-container) Xtream streams carry selectable subtitle
 * tracks with instant seeking — no HLS session needed.
 *
 * Runs at most once per missing track set per file: stream-copy only
 * (1-3s per episode), verified before an atomic replace, and never throws —
 * any failure falls back to serving the original file untouched.
 */
@ApplicationScoped
public class SubtitleMuxService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleMuxService.class);
    private static final long MUX_TIMEOUT_SECONDS = 300;

    @Inject FFmpegDiscoveryService ffmpegDiscoveryService;
    @Inject SettingsService settingsService;
    @Inject EnhancedSubtitleMatcher subtitleMatcher;
    @Inject SubtitleTrackService subtitleTrackService;

    private final ConcurrentHashMap<Long, Object> muxLocks = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService muxExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "subtitle-mux");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    void shutdown() {
        muxExecutor.shutdownNow();
    }

    public boolean ensureEmbeddedSubtitles(Video video) {
        if (video == null || video.id == null || video.path == null || video.path.isBlank()) return false;
        // Fast synchronous check (ffprobe + directory scan, ~100-300ms); the actual
        // remux runs in the background so playback starts immediately. Next play
        // (or a later range request) finds the tracks embedded.
        List<Models.Video.SubtitleTrack> missing;
        try {
            missing = findMissingSubtitles(video);
        } catch (Exception e) {
            LOG.warn("Subtitle mux check failed for video {} ({}); serving original file", video.id, video.filename, e);
            return false;
        }
        if (missing.isEmpty()) return false;
        Object lock = muxLocks.computeIfAbsent(video.id, k -> new Object());
        synchronized (lock) {
            muxExecutor.submit(() -> {
                try {
                    muxMissingSubtitles(video, missing);
                } catch (Exception e) {
                    LOG.warn("Background subtitle mux failed for video {} ({}); serving original file", video.id, video.filename, e);
                }
            });
        }
        return false;
    }

    private List<Models.Video.SubtitleTrack> findMissingSubtitles(Video video) throws Exception {
        Path videoPath = resolveAbsolute(video.path);
        if (videoPath == null || !Files.isRegularFile(videoPath)) return new ArrayList<>();
        String lowerName = videoPath.getFileName().toString().toLowerCase();
        if (!lowerName.endsWith(".mp4") && !lowerName.endsWith(".m4v") && !lowerName.endsWith(".mkv")) {
            return new ArrayList<>();
        }
        String ffprobe = ffmpegDiscoveryService.findFFprobeExecutable();
        if (ffprobe == null) return new ArrayList<>();

        java.util.Map<String, Integer> embeddedCounts = new java.util.HashMap<>();
        for (String l : embeddedSubtitleLanguages(ffprobe, videoPath)) {
            String key = (l == null || l.isBlank()) ? "und" : l.toLowerCase();
            embeddedCounts.merge(key, 1, Integer::sum);
        }
        // Recursive discovery (covers Subs/ subfolders); the flat scan only sees
        // the video's own folder and would miss nested sidecars entirely.
        List<Models.Video.SubtitleTrack> discovered = subtitleMatcher.discoverSubtitleTracks(videoPath, video);
        List<Models.Video.SubtitleTrack> missing = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Models.Video.SubtitleTrack t : discovered) {
            if (t == null || t.isEmbedded || t.fullPath == null || t.fullPath.isBlank()) continue;
            String fmt = t.format != null ? t.format.toLowerCase() : "";
            if (!fmt.equals("srt") && !fmt.equals("vtt") && !fmt.equals("ass") && !fmt.equals("ssa") && !fmt.equals("subrip")) continue;
            if (!Files.isRegularFile(Paths.get(t.fullPath))) continue;
            String lang = t.languageCode != null && !t.languageCode.isBlank() ? t.languageCode
                    : subtitleTrackService.mapToThreeLetterLanguage(t.languageName != null ? t.languageName : "");
            if (lang == null || lang.isBlank()) lang = "und";
            lang = lang.toLowerCase();
            seen.add(t.filename + "->" + lang);
            // Multiset match: one embedded track covers one sidecar of the same
            // language. "und" matches "und" — without this, untagged tracks would
            // remux on every play and duplicate forever.
            int have = embeddedCounts.getOrDefault(lang, 0);
            if (have > 0) {
                embeddedCounts.put(lang, have - 1);
                continue;
            }
            missing.add(t);
        }
        LOG.info("Subtitle mux check for video {}: embedded={} discovered={} missing={}", video.id, embeddedCounts, seen, missing.size());
        return missing;
    }

    private boolean muxMissingSubtitles(Video video, List<Models.Video.SubtitleTrack> missing) throws Exception {
        Path videoPath = resolveAbsolute(video.path);
        if (videoPath == null || !Files.isRegularFile(videoPath)) return false;
        String lowerName = videoPath.getFileName().toString().toLowerCase();
        boolean isMp4 = lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v");
        boolean isMkv = lowerName.endsWith(".mkv");
        if (!isMp4 && !isMkv) return false;
        String subCodec = isMp4 ? "mov_text" : "srt";

        String ffmpeg = ffmpegDiscoveryService.findFFmpegExecutable();
        String ffprobe = ffmpegDiscoveryService.findFFprobeExecutable();
        if (ffmpeg == null || ffprobe == null) return false;

        if (missing.isEmpty()) {
            LOG.debug("No missing sidecar subtitles to mux for video {}", video.id);
            return false;
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-v"); command.add("error");
        command.add("-hide_banner");
        command.add("-i"); command.add(videoPath.toString());
        for (Models.Video.SubtitleTrack s : missing) {
            command.add("-i"); command.add(s.fullPath);
        }
        command.add("-map"); command.add("0");
        for (int i = 0; i < missing.size(); i++) {
            command.add("-map"); command.add((i + 1) + ":0");
        }
        command.add("-c"); command.add("copy");
        command.add("-c:s"); command.add(subCodec);
        for (int i = 0; i < missing.size(); i++) {
            Models.Video.SubtitleTrack t = missing.get(i);
            String lang = t.languageCode != null && !t.languageCode.isBlank() ? t.languageCode
                    : subtitleTrackService.mapToThreeLetterLanguage(t.languageName != null ? t.languageName : "");
            if (lang != null && !lang.isBlank() && !lang.equalsIgnoreCase("und")) {
                command.add("-metadata:s:s:" + (embeddedSubtitleCount(ffprobe, videoPath) + i));
                command.add("language=" + lang.toLowerCase());
            }
        }
        // Temp file must keep a real container extension (ffmpeg infers the
        // muxer from it); the trailing .tmp alone leaves format undecided.
        String baseName = videoPath.getFileName().toString();
        String containerExt = baseName.contains(".") ? baseName.substring(baseName.lastIndexOf('.')) : ".mp4";
        Path tmp = videoPath.resolveSibling(baseName + ".mux" + containerExt);
        Files.deleteIfExists(tmp);
        command.add(tmp.toString());

        LOG.info("Muxing {} subtitle track(s) into video {} ({})", missing.size(), video.id, videoPath.getFileName());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        boolean finished = process.waitFor(MUX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(tmp);
            LOG.warn("Subtitle mux timed out for video {}", video.id);
            return false;
        }
        if (process.exitValue() != 0) {
            Files.deleteIfExists(tmp);
            LOG.warn("Subtitle mux failed for video {}: {}", video.id, output.toString().trim());
            return false;
        }
        int before = countSubtitleStreams(ffprobe, videoPath);
        int after = countSubtitleStreams(ffprobe, tmp);
        if (after < before + missing.size()) {
            Files.deleteIfExists(tmp);
            LOG.warn("Subtitle mux verification failed for video {} (tracks {} -> {}, expected +{})", video.id, before, after, missing.size());
            return false;
        }
        Files.move(tmp, videoPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Embedded {} subtitle track(s) into video {}", missing.size(), video.id);
        try {
            subtitleTrackService.refreshSubtitleTracks(video);
        } catch (Exception e) {
            LOG.debug("Subtitle refresh after mux failed for video {}: {}", video.id, e.getMessage());
        }
        return true;
    }

    private Path resolveAbsolute(String videoPath) {
        if (videoPath == null || videoPath.isBlank()) return null;
        Path p = Paths.get(videoPath);
        if (p.isAbsolute()) return p;
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isBlank()) return Paths.get(libraryPath, videoPath);
        } catch (Exception e) {
            LOG.debug("Could not resolve library path: {}", e.getMessage());
        }
        return p;
    }

    // Returns one entry PER subtitle stream (duplicates kept): the caller
    // multiset-matches these against sidecars, so collapsing to a Set would
    // undercount and duplicate one track per play.
    private List<String> embeddedSubtitleLanguages(String ffprobe, Path videoPath) throws Exception {
        List<String> langs = new ArrayList<>();
        for (String line : probeSubtitleLines(ffprobe, videoPath)) {
            // Untagged streams come back as a bare codec ("mov_text") with no comma:
            // count those as und, or muxed-in tracks stay invisible and duplicate.
            String[] parts = line.split(",", -1);
            if (parts.length >= 2 && !parts[1].isBlank()) langs.add(parts[1].trim().toLowerCase());
            else langs.add("und");
        }
        return langs;
    }

    private int countSubtitleStreams(String ffprobe, Path videoPath) throws Exception {
        return probeSubtitleLines(ffprobe, videoPath).size();
    }

    private int embeddedSubtitleCount(String ffprobe, Path videoPath) {
        try {
            return countSubtitleStreams(ffprobe, videoPath);
        } catch (Exception e) {
            return 0;
        }
    }

    private List<String> probeSubtitleLines(String ffprobe, Path videoPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "s",
                "-show_entries", "stream=codec_name:stream_tags=language", "-of", "csv=p=0", videoPath.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) lines.add(line.trim());
            }
        }
        process.waitFor(30, TimeUnit.SECONDS);
        return lines;
    }
}
