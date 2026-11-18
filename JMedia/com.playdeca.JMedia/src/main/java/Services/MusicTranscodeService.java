package Services;

import Models.Music.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * iOS fallback for FLAC.
 *
 * <p>iOS plays FLAC in a bare audio element, but routing it through Web Audio
 * (required for the crossfade/volume path) is unreliable. FLAC is therefore
 * transcoded once to AAC/M4A and cached; all other clients keep the lossless
 * original. The cache is bounded and evicted oldest-first.
 */
@ApplicationScoped
public class MusicTranscodeService {

    private static final Logger LOG = LoggerFactory.getLogger(MusicTranscodeService.class);

    private static final int AAC_BITRATE_KBPS = 256;
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long TRANSCODE_TIMEOUT_SECONDS = 180;

    @Inject
    FFmpegDiscoveryService discoveryService;

    private final Path cacheDir = Paths.get(System.getProperty("user.home"), ".jmedia", "music-transcode");
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public boolean isIOSClient(String userAgent) {
        if (userAgent == null) {
            return false;
        }
        return userAgent.contains("iPhone")
                || userAgent.contains("iPad")
                || userAgent.contains("iPod")
                // iPadOS 13+ reports a desktop macOS UA plus Mobile/…
                || (userAgent.contains("Macintosh") && userAgent.contains("Mobile/"));
    }

    public boolean isFlac(File source) {
        return source != null && source.getName().toLowerCase(Locale.ROOT).endsWith(".flac");
    }

    /**
     * Returns an iOS-playable file (cached AAC/M4A) or the original source when
     * transcoding is unavailable/fails, so a failure never blocks playback.
     */
    public File ensureIosPlayable(Song song, File source) {
        if (song == null || source == null || !isFlac(source)) {
            return source;
        }
        String ffmpeg = discoveryService.findFFmpegExecutable();
        if (ffmpeg == null || ffmpeg.isBlank()) {
            LOG.warn("FLAC transcode skipped for song {}: ffmpeg not available", song.id);
            return source;
        }

        try {
            Files.createDirectories(cacheDir);
            String key = "song-" + song.id + "-" + source.length() + "-" + source.lastModified()
                    + "-aac" + AAC_BITRATE_KBPS;
            Path output = cacheDir.resolve(key + ".m4a");
            if (isUsable(output)) {
                return output.toFile();
            }

            Object lock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                if (isUsable(output)) {
                    return output.toFile();
                }
                Path temp = cacheDir.resolve(key + ".tmp.m4a");
                Files.deleteIfExists(temp);
                long started = System.currentTimeMillis();

                Process process = new ProcessBuilder(
                        ffmpeg, "-y", "-v", "error",
                        "-i", source.getAbsolutePath(),
                        "-map", "0:a:0", "-vn", "-ac", "2", "-ar", "44100",
                        "-c:a", "aac", "-b:a", AAC_BITRATE_KBPS + "k",
                        "-movflags", "+faststart", "-f", "mp4", temp.toString())
                        .redirectErrorStream(true)
                        .start();

                boolean finished = process.waitFor(TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    LOG.warn("FLAC transcode timed out for song {} after {}s", song.id, TRANSCODE_TIMEOUT_SECONDS);
                    Files.deleteIfExists(temp);
                    return source;
                }
                String processLog;
                try (var in = process.getInputStream()) {
                    processLog = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (process.exitValue() != 0 || !isUsable(temp)) {
                    LOG.warn("FLAC transcode failed for song {} (exit {}): {}", song.id, process.exitValue(),
                            processLog.length() > 500 ? processLog.substring(0, 500) : processLog);
                    Files.deleteIfExists(temp);
                    return source;
                }

                moveIntoPlace(temp, output);
                LOG.info("FLAC transcode ready for song {} in {} ms -> {}", song.id,
                        System.currentTimeMillis() - started, output.getFileName());
                cleanupCache();
                return output.toFile();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("FLAC transcode interrupted for song {}: {}", song.id, e.getMessage());
            return source;
        } catch (IOException | RuntimeException e) {
            LOG.warn("FLAC transcode error for song {}: {}", song.id, e.getMessage());
            return source;
        }
    }

    private boolean isUsable(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            LOG.warn("Could not inspect transcode cache file {}: {}", path, e.getMessage());
            return false;
        }
    }

    private void moveIntoPlace(Path temp, Path output) throws IOException {
        try {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Keep the cache bounded; oldest files are removed first. */
    private void cleanupCache() {
        try (var stream = Files.list(cacheDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".m4a"))
                    .toList();
            long total = files.stream().mapToLong(this::size).sum();
            if (total <= MAX_CACHE_BYTES) {
                return;
            }
            List<Path> ordered = files.stream()
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .toList();
            for (Path path : ordered) {
                if (total <= MAX_CACHE_BYTES) {
                    break;
                }
                long fileSize = size(path);
                try {
                    Files.deleteIfExists(path);
                    total -= fileSize;
                    LOG.info("Evicted FLAC transcode cache file {}", path.getFileName());
                } catch (IOException e) {
                    LOG.warn("Could not evict transcode cache file {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not clean FLAC transcode cache: {}", e.getMessage());
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            LOG.warn("Could not read transcode cache size for {}: {}", path, e.getMessage());
            return 0;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            LOG.warn("Could not read transcode cache mtime for {}: {}", path, e.getMessage());
            return 0;
        }
    }
}
