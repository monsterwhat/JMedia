package Services;

import Models.Video.Video;
import Utils.FragmentedMp4Seeker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Segment-chain cache for video streaming.
 *
 * <p>One chain per (videoId, audioTrackIndex, qualityHeight). A segment is a
 * fragmented-MP4 file covering [startSeconds, endSeconds) of the video, named
 * {@code cache-<absHash(videoId|audio|quality)>-<%.3f startSeconds>.mp4} with a
 * JSON sidecar (same path + ".json") carrying the identity of the source file
 * at creation time (used for stale detection) and the segment's coverage.
 *
 * <p>Public serving contract (see design .omo/plans/cache-segment-system.md,
 * section 4.3):
 * <ul>
 *   <li>coverage lookup + stale purge,</li>
 *   <li>segment start / complete lifecycle,</li>
 *   <li>background gap backfill + positional concat converging the chain to one
 *       contiguous file,</li>
 *   <li>reader accounting so a segment being read is never deleted.</li>
 * </ul>
 *
 * <p>Mutations (start/complete/purge/backfill/concat) are serialized per chain
 * with a {@link ReentrantLock}; serving (byte reads by the caller) never takes
 * the lock. Gap transcodes are delegated to
 * {@link TranscodingService#runGapTranscode} on its dedicated executor.
 */
@ApplicationScoped
public class SegmentCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(SegmentCacheService.class);

    public static final String STATUS_GROWING = "growing";
    public static final String STATUS_COMPLETE = "complete";

    /** Cooldown after a FAILED gap transcode so a broken chain can't trigger a
     *  retry storm on every backfill trigger. */
    private static final long GAP_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L;

    /** Merge is deferred (reader guard) at most this many times before the
     *  chain is left as separate segments until the next natural trigger. */
    private static final int MERGE_DEFER_MAX = 5;
    private static final long MERGE_DEFER_DELAY_MS = 30_000L;

    private static final int COPY_BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_FILE_OP_ATTEMPTS = 3;
    private static final long PROBE_TIMEOUT_SECONDS = 10;
    /** Two times are "the same position" when they differ by less than this. */
    private static final double TIME_EPSILON = 0.001;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    FFmpegDiscoveryService discoveryService;

    @Inject
    TranscodingService transcodingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-chain (by absHash) reentrant lock guarding all mutations. */
    private final Map<String, ReentrantLock> chainLocks = new ConcurrentHashMap<>();
    /** Active readers per segment file (keyed by absolute file path). */
    private final Map<String, AtomicInteger> segmentReaders = new ConcurrentHashMap<>();
    /** Last failed gap-backfill timestamp per chain, for the failure cooldown. */
    private final Map<String, Long> lastGapFailure = new ConcurrentHashMap<>();
    /** Number of consecutive deferred merge passes per chain. */
    private final Map<String, Integer> mergeDeferCounts = new ConcurrentHashMap<>();
    /** Backfill pass keys currently queued (dedupe for concurrent triggers). */
    private final Set<String> pendingBackfills = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService backfillExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "segment-backfill");
        t.setDaemon(true);
        return t;
    });

    // ── Public API ─────────────────────────────────────────────────────────

    /** Immutable result of a coverage lookup. */
    public static final class SegmentCoverage {
        public final Path file;
        public final double startSeconds;
        /** 0.0 while the segment is still growing. */
        public final double endSeconds;
        public final String status;
        /** Byte offset of the fragment containing the requested time;
         *  null when the requested time is beyond the segment's written end. */
        public final Long byteOffset;

        public SegmentCoverage(Path file, double startSeconds, double endSeconds, String status, Long byteOffset) {
            this.file = file;
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
            this.status = status;
            this.byteOffset = byteOffset;
        }

        public boolean isGrowing() {
            return STATUS_GROWING.equals(status);
        }

        @Override
        public String toString() {
            return "SegmentCoverage{file=" + file + ", start=" + startSeconds
                    + ", end=" + endSeconds + ", status=" + status + ", byteOffset=" + byteOffset + "}";
        }
    }

    /**
     * Returns the best segment covering {@code targetSeconds}, or null on a miss.
     * <p>Per the design, step 1 is the stale check: if any segment in the chain
     * carries a source identity that no longer matches the current source file,
     * the whole chain is purged and a miss is returned. Step 2 picks the segment
     * with the latest start that still covers the target (furthest-along chain).
     */
    public SegmentCoverage findCoveringSegment(Long videoId, double targetSeconds,
                                               int audioTrackIndex, int qualityHeight, Path sourcePath) {
        if (videoId == null) {
            return null;
        }
        int absHash = absHash(videoHashKey(videoId, audioTrackIndex, qualityHeight));
        ReentrantLock lock = chainLock(absHash);
        lock.lock();
        try {
            List<SegmentEntry> chain = scanChain(absHash);
            for (SegmentEntry entry : chain) {
                if (isStale(entry.sidecar, sourcePath)) {
                    LOG.info("Segment chain {} is stale (source changed), purging", absHash);
                    purgeChainByHash(absHash);
                    return null;
                }
            }

            SegmentEntry best = null;
            for (SegmentEntry entry : chain) {
                if (entry.sidecar.startSeconds > targetSeconds + TIME_EPSILON) {
                    continue;
                }
                boolean covers = entry.isGrowing()
                        || (entry.sidecar.endSeconds != null && entry.sidecar.endSeconds > targetSeconds);
                if (!covers) {
                    continue;
                }
                if (best == null || entry.sidecar.startSeconds > best.sidecar.startSeconds) {
                    best = entry;
                }
            }
            if (best == null) {
                return null;
            }

            Long byteOffset = null;
            if (Files.exists(best.file)) {
                try {
                    // Segments carry a 0-based relative timeline (tfdt restarts at 0 per
                    // segment), so convert the video-absolute target into the segment's
                    // own coordinates before probing.
                    double relTarget = Math.max(0.0, targetSeconds - best.sidecar.startSeconds);
                    byteOffset = FragmentedMp4Seeker.byteOffsetForTime(best.file, relTarget);
                } catch (IOException e) {
                    LOG.warn("Failed to seek segment {} for time {}: {}", best.file, targetSeconds, e.getMessage());
                }
            }
            double end = best.isGrowing() ? 0.0 : (best.sidecar.endSeconds != null ? best.sidecar.endSeconds : 0.0);
            return new SegmentCoverage(best.file, best.sidecar.startSeconds, end, best.sidecar.status, byteOffset);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates (or reuses) the segment file starting at {@code targetSeconds} and
     * writes its {@code growing} sidecar. Idempotent: a concurrent caller seeking
     * to the same position gets the same file back. Returns null on failure.
     */
    public Path startSegment(Long videoId, double targetSeconds,
                             int audioTrackIndex, int qualityHeight, Path sourcePath) {
        if (videoId == null) {
            return null;
        }
        int absHash = absHash(videoHashKey(videoId, audioTrackIndex, qualityHeight));
        ReentrantLock lock = chainLock(absHash);
        lock.lock();
        try {
            if (sourcePath != null) {
                boolean stale = scanChain(absHash).stream().anyMatch(e -> isStale(e.sidecar, sourcePath));
                if (stale) {
                    LOG.info("Segment chain {} is stale on start, purging before creating new segment", absHash);
                    purgeChainByHash(absHash);
                }
            }

            Path file = segmentPath(absHash, targetSeconds);
            if (Files.exists(file)) {
                if (!Files.exists(sidecarPath(file))) {
                    writeSidecar(file, newSidecar(videoId, targetSeconds, audioTrackIndex, qualityHeight, sourcePath));
                }
                return file;
            }

            getTempDir(); // ensure the directory exists
            Files.createFile(file);
            writeSidecar(file, newSidecar(videoId, targetSeconds, audioTrackIndex, qualityHeight, sourcePath));
            LOG.debug("Started segment {} for video {} at {}s", file.getFileName(), videoId, targetSeconds);
            return file;
        } catch (IOException e) {
            LOG.warn("Failed to start segment for video {} at {}s: {}", videoId, targetSeconds, e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a segment as complete: probes its real end time, flips the sidecar
     * status to {@code complete}, and schedules a backfill/concat pass.
     * <p>Note: the ".done" marker is owned by TranscodingService (written on
     * genuine exit 0); it is deliberately NOT written here so a kept-on-
     * disconnect segment (complete-with-coverage) is not mistaken for a fully
     * downloaded stream by the existing exact-hash path.
     */
    public void completeSegment(Path segmentFile) {
        if (segmentFile == null || !Files.exists(segmentFile)) {
            return;
        }
        SegmentSidecar sidecar = readSidecar(sidecarPath(segmentFile));
        if (sidecar == null || sidecar.videoId == null) {
            LOG.warn("completeSegment called for {} but no valid sidecar exists", segmentFile);
            return;
        }
        int absHash = absHash(videoHashKey(sidecar.videoId,
                sidecar.audioTrackIndex != null ? sidecar.audioTrackIndex : -1,
                sidecar.qualityHeight != null ? sidecar.qualityHeight : 0));
        ReentrantLock lock = chainLock(absHash);
        lock.lock();
        try {
            double endSeconds = 0.0;
            try {
                endSeconds = FragmentedMp4Seeker.endTimeSeconds(segmentFile);
            } catch (IOException e) {
                LOG.warn("Failed to probe end time for {}: {}", segmentFile, e.getMessage());
            }
            if (endSeconds > 0) {
                // endTimeSeconds is relative to the segment's own 0-based timeline; store
                // the coverage end in video-absolute coordinates (like startSeconds) so
                // covers checks and gap detection compare like with like.
                sidecar.endSeconds = sidecar.startSeconds != null
                        ? sidecar.startSeconds + endSeconds : endSeconds;
            }
            sidecar.status = STATUS_COMPLETE;
            writeSidecar(segmentFile, sidecar);
            LOG.info("Completed segment {} (end={}s)", segmentFile.getFileName(), sidecar.endSeconds);
            if (sidecar.endSeconds != null && sidecar.endSeconds > sidecar.startSeconds + TIME_EPSILON) {
                scheduleBackfill(absHash);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Purges the whole chain for the given video/audio/quality: files, sidecars,
     *  markers and gap temps, plus per-chain failure/defer state. */
    public void purgeChain(Long videoId, int audioTrackIndex, int qualityHeight) {
        if (videoId == null) {
            return;
        }
        int absHash = absHash(videoHashKey(videoId, audioTrackIndex, qualityHeight));
        ReentrantLock lock = chainLock(absHash);
        lock.lock();
        try {
            purgeChainByHash(absHash);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until the fragment containing {@code targetSeconds} exists in the
     * segment (or the segment is complete and its recorded end covers the time),
     * or until the timeout elapses. Returns true when the data is available.
     */
    public boolean waitForTimeCovered(Path segmentFile, double targetSeconds, long timeoutMillis) {
        if (segmentFile == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(segmentFile)) {
                SegmentSidecar sidecar = readSidecar(sidecarPath(segmentFile));
                // Segments carry a 0-based relative timeline; convert the video-absolute
                // target into the segment's coordinates before probing fragments.
                double relTarget = targetSeconds;
                if (sidecar != null && sidecar.startSeconds != null) {
                    relTarget = Math.max(0.0, targetSeconds - sidecar.startSeconds);
                }
                try {
                    Long offset = FragmentedMp4Seeker.byteOffsetForTime(segmentFile, relTarget);
                    if (offset != null) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // file may be mid-write / being replaced — keep polling
                }
                if (sidecar != null && STATUS_COMPLETE.equals(sidecar.status)
                        && sidecar.endSeconds != null && sidecar.endSeconds > targetSeconds) {
                    return true;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Marks a segment as being read; the merge pass will not delete it while
     *  the reader count is above zero. */
    public void acquireReader(Path segmentFile) {
        if (segmentFile == null) {
            return;
        }
        segmentReaders.computeIfAbsent(segmentFile.toAbsolutePath().toString(),
                k -> new AtomicInteger()).incrementAndGet();
    }

    /** Releases a previously acquired reader. */
    public void releaseReader(Path segmentFile) {
        if (segmentFile == null) {
            return;
        }
        AtomicInteger counter = segmentReaders.get(segmentFile.toAbsolutePath().toString());
        if (counter != null) {
            int remaining = counter.decrementAndGet();
            if (remaining <= 0) {
                segmentReaders.remove(segmentFile.toAbsolutePath().toString(), counter);
            }
        }
    }

    /** Number of active readers of a segment file. */
    public int getActiveReaderCount(Path segmentFile) {
        if (segmentFile == null) {
            return 0;
        }
        AtomicInteger counter = segmentReaders.get(segmentFile.toAbsolutePath().toString());
        return counter != null ? counter.get() : 0;
    }

    /**
     * Hourly maintenance sweep: purges every segment chain whose source file has
     * changed on disk (or is unreadable). Reads each {@code cache-*.json}
     * sidecar, resolves the current source path and asks {@link #isStale}; stale
     * chains are purged wholesale (files + sidecars + markers + gap temps).
     * Per-file try/catch so one corrupt sidecar cannot abort the sweep.
     */
    public void sweepStaleChains() {
        Path dir;
        try {
            dir = getTempDir();
        } catch (IOException e) {
            LOG.warn("Cannot sweep stale chains: {}", e.getMessage());
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sidecars = files
                    .filter(p -> p.getFileName().toString().startsWith("cache-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .collect(Collectors.toList());
            for (Path sidecar : sidecars) {
                try {
                    SegmentSidecar data = readSidecar(sidecar);
                    if (data == null) {
                        continue;
                    }
                    if (!isStale(data, resolveSourcePath(data.sourcePath))) {
                        continue;
                    }
                    int absHash;
                    String fileName = sidecar.getFileName().toString();
                    try {
                        absHash = Integer.parseInt(fileName.substring("cache-".length(),
                                fileName.indexOf('-', "cache-".length())));
                    } catch (Exception parseEx) {
                        absHash = absHash(videoHashKey(data.videoId,
                                data.audioTrackIndex != null ? data.audioTrackIndex : -1,
                                data.qualityHeight != null ? data.qualityHeight : 0));
                    }
                    ReentrantLock lock = chainLock(absHash);
                    lock.lock();
                    try {
                        LOG.info("Stale chain {} (source changed), purging from hourly sweep", absHash);
                        purgeChainByHash(absHash);
                    } finally {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to sweep stale sidecar {}: {}", sidecar, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to list sidecars for stale sweep: {}", e.getMessage());
        }
    }

    // ── Sidecar model ──────────────────────────────────────────────────────

    /** JSON sidecar persisted next to each segment file. */
    public static class SegmentSidecar {
        public Long videoId;
        public Double startSeconds;
        /** null while growing; set to the probed last-fragment end on completion. */
        public Double endSeconds;
        public Integer audioTrackIndex;
        public Integer qualityHeight;
        public String status;
        public String sourcePath;
        public Long sourceMtime;
        public Long sourceSize;
    }

    // ── Chain internals ────────────────────────────────────────────────────

    private static final class SegmentEntry {
        final Path file;
        final SegmentSidecar sidecar;

        SegmentEntry(Path file, SegmentSidecar sidecar) {
            this.file = file;
            this.sidecar = sidecar;
        }

        boolean isGrowing() {
            return sidecar == null || STATUS_GROWING.equals(sidecar.status);
        }

        boolean isComplete() {
            return sidecar != null && STATUS_COMPLETE.equals(sidecar.status);
        }
    }

    private String videoHashKey(Long videoId, int audioTrackIndex, int qualityHeight) {
        return videoId + "|" + audioTrackIndex + "|" + qualityHeight;
    }

    private int absHash(String key) {
        return Math.abs(key.hashCode());
    }

    private String segmentFileName(int absHash, double startSeconds) {
        return "cache-" + absHash + "-" + String.format(Locale.ROOT, "%.3f", startSeconds) + ".mp4";
    }

    private Path segmentPath(int absHash, double startSeconds) throws IOException {
        return getTempDir().resolve(segmentFileName(absHash, startSeconds));
    }

    private Path sidecarPath(Path segmentFile) {
        return segmentFile.resolveSibling(segmentFile.getFileName().toString() + ".json");
    }

    private Path doneMarkerPath(Path segmentFile) {
        return segmentFile.resolveSibling(segmentFile.getFileName().toString() + ".done");
    }

    /** Mirrors TranscodingService.getTempDir: <videoLibraryPath>/mp4, falling
     *  back to <java.io.tmpdir>/jmedia-mp4. */
    private Path getTempDir() throws IOException {
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isEmpty()) {
                Path dir = Paths.get(libraryPath, "mp4");
                Files.createDirectories(dir);
                return dir;
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve video library path, falling back to temp dir: {}", e.getMessage());
        }
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jmedia-mp4");
        Files.createDirectories(dir);
        return dir;
    }

    private Path resolveSourcePath(String sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        Path raw = Paths.get(sourcePath);
        if (raw.isAbsolute()) {
            return raw;
        }
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isEmpty()) {
                return Paths.get(libraryPath, sourcePath);
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve video library path for source {}: {}", sourcePath, e.getMessage());
        }
        return raw;
    }

    private ReentrantLock chainLock(int absHash) {
        return chainLocks.computeIfAbsent(String.valueOf(absHash), k -> new ReentrantLock());
    }

    private SegmentSidecar newSidecar(Long videoId, double startSeconds, int audioTrackIndex,
                                      int qualityHeight, Path sourcePath) {
        SegmentSidecar sidecar = new SegmentSidecar();
        sidecar.videoId = videoId;
        sidecar.startSeconds = startSeconds;
        sidecar.endSeconds = null;
        sidecar.audioTrackIndex = audioTrackIndex;
        sidecar.qualityHeight = qualityHeight;
        sidecar.status = STATUS_GROWING;
        if (sourcePath != null) {
            sidecar.sourcePath = sourcePath.toString();
            try {
                sidecar.sourceMtime = Files.getLastModifiedTime(sourcePath).toMillis();
            } catch (IOException e) {
                sidecar.sourceMtime = 0L;
            }
            try {
                sidecar.sourceSize = Files.size(sourcePath);
            } catch (IOException e) {
                sidecar.sourceSize = 0L;
            }
        }
        return sidecar;
    }

    private SegmentSidecar readSidecar(Path sidecar) {
        if (sidecar == null || !Files.exists(sidecar)) {
            return null;
        }
        try {
            return objectMapper.readValue(sidecar.toFile(), SegmentSidecar.class);
        } catch (Exception e) {
            LOG.debug("Failed to read sidecar {}: {}", sidecar, e.getMessage());
            return null;
        }
    }

    private void writeSidecar(Path segmentFile, SegmentSidecar sidecar) {
        try {
            objectMapper.writeValue(sidecarPath(segmentFile).toFile(), sidecar);
        } catch (IOException e) {
            LOG.warn("Failed to write sidecar for {}: {}", segmentFile, e.getMessage());
        }
    }

    /** True when the sidecar's recorded source identity no longer matches the
     *  current source file (path, mtime or size). Missing sidecar or unreadable
     *  source is treated as stale. */
    private boolean isStale(SegmentSidecar sidecar, Path sourcePath) {
        if (sidecar == null) {
            return true;
        }
        if (sourcePath == null) {
            return false;
        }
        try {
            Path expected = resolveSourcePath(sidecar.sourcePath);
            if (expected != null
                    && !expected.toAbsolutePath().normalize().equals(sourcePath.toAbsolutePath().normalize())) {
                return true;
            }
            long mtime = Files.getLastModifiedTime(sourcePath).toMillis();
            long size = Files.size(sourcePath);
            if (sidecar.sourceMtime == null || sidecar.sourceSize == null) {
                return true;
            }
            return sidecar.sourceMtime != mtime || sidecar.sourceSize != size;
        } catch (IOException e) {
            return true; // source unreadable — assume stale
        }
    }

    /** Lists the chain sorted by startSeconds. Only sidecar-backed segments are
     *  returned; gap temps ("*.mp4.tmp") and merged temps never match the
     *  "*.mp4" suffix filter. Caller must hold the chain lock. */
    private List<SegmentEntry> scanChain(int absHash) {
        List<SegmentEntry> entries = new ArrayList<>();
        Path dir;
        try {
            dir = getTempDir();
        } catch (IOException e) {
            LOG.warn("Cannot list chain {}: {}", absHash, e.getMessage());
            return entries;
        }
        String prefix = "cache-" + absHash + "-";
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix))
                 .filter(p -> p.getFileName().toString().endsWith(".mp4"))
                 .forEach(p -> {
                     SegmentSidecar sidecar = readSidecar(sidecarPath(p));
                     if (sidecar != null && sidecar.startSeconds != null) {
                         entries.add(new SegmentEntry(p, sidecar));
                     }
                 });
        } catch (IOException e) {
            LOG.warn("Failed to list segment chain {}: {}", absHash, e.getMessage());
        }
        entries.sort(Comparator.comparingDouble(e -> e.sidecar.startSeconds));
        return entries;
    }

    /** Deletes every file of the chain (segments, sidecars, markers, temps).
     *  Caller must hold the chain lock. */
    private void purgeChainByHash(int absHash) {
        Path dir;
        try {
            dir = getTempDir();
        } catch (IOException e) {
            return;
        }
        String prefix = "cache-" + absHash + "-";
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> chainFiles = files
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".mp4") || name.endsWith(".json")
                                || name.endsWith(".done") || name.endsWith(".tmp");
                    })
                    .collect(Collectors.toList());
            for (Path f : chainFiles) {
                deleteFileWithRetry(f);
            }
        } catch (IOException e) {
            LOG.warn("Failed to list chain for purge {}: {}", absHash, e.getMessage());
        }
        String readerPrefix = dir.toAbsolutePath().resolve(prefix).toString();
        segmentReaders.keySet().removeIf(k -> k.startsWith(readerPrefix));
        lastGapFailure.remove(String.valueOf(absHash));
        mergeDeferCounts.remove(String.valueOf(absHash));
        LOG.info("Purged segment chain {}", absHash);
    }

    // ── Backfill + concat engine ───────────────────────────────────────────

    private void scheduleBackfill(int absHash) {
        String key = "pass-" + absHash;
        if (pendingBackfills.add(key)) {
            backfillExecutor.submit(() -> {
                try {
                    runBackfillPass(absHash);
                } finally {
                    pendingBackfills.remove(key);
                }
            });
        }
    }

    /**
     * One backfill pass for a chain (executor thread, chain lock held):
     * <ol>
     *   <li>fill every gap between consecutive complete segments via
     *       {@link TranscodingService#runGapTranscode} (with failure cooldown),</li>
     *   <li>then merge the complete run into one contiguous file.</li>
     * </ol>
     */
    private void runBackfillPass(int absHash) {
        ReentrantLock lock = chainLock(absHash);
        lock.lock();
        try {
            List<SegmentEntry> chain = scanChain(absHash);
            if (chain.size() < 2) {
                return;
            }

            long now = System.currentTimeMillis();
            Long lastFail = lastGapFailure.get(String.valueOf(absHash));
            boolean onCooldown = lastFail != null && now - lastFail < GAP_FAILURE_COOLDOWN_MS;

            if (!onCooldown) {
                for (int i = 0; i + 1 < chain.size(); i++) {
                    SegmentEntry a = chain.get(i);
                    SegmentEntry b = chain.get(i + 1);
                    if (!a.isComplete() || !b.isComplete()) {
                        continue; // growing neighbors are not mergeable yet
                    }
                    double gapStart = a.sidecar.endSeconds != null ? a.sidecar.endSeconds : a.sidecar.startSeconds;
                    double gapEnd = b.sidecar.startSeconds;
                    if (gapEnd <= gapStart + TIME_EPSILON) {
                        continue; // adjacent or overlapping — no gap
                    }
                    Path gapTemp = fillGap(a, b, gapStart, gapEnd);
                    if (gapTemp == null) {
                        lastGapFailure.put(String.valueOf(absHash), System.currentTimeMillis());
                        LOG.warn("Gap fill failed for chain {}, aborting merge pass", absHash);
                        return;
                    }
                    registerGapSegment(absHash, gapTemp, gapStart, gapEnd, a.sidecar);
                }
            } else {
                LOG.debug("Gap backfill for chain {} on cooldown (failed {}ms ago)", absHash, now - lastFail);
            }

            mergeChain(absHash);
        } finally {
            lock.unlock();
        }
    }

    /** Generates the missing [gapStart, gapEnd) content and returns the temp
     *  file, or null on failure (input missing, transcode failure, param gate). */
    private Path fillGap(SegmentEntry a, SegmentEntry b, double gapStart, double gapEnd) {
        Long videoId = a.sidecar.videoId;
        Video video = videoId != null ? videoService.findById(videoId) : null;
        if (video == null) {
            LOG.warn("Video {} not found for gap backfill", videoId);
            return null;
        }
        Path source = resolveSourcePath(a.sidecar.sourcePath);
        if (source == null || !Files.exists(source)) {
            LOG.warn("Source file missing for gap backfill: {}", source);
            return null;
        }
        double duration = gapEnd - gapStart;
        int audioTrackIndex = a.sidecar.audioTrackIndex != null ? a.sidecar.audioTrackIndex : -1;
        int qualityHeight = a.sidecar.qualityHeight != null ? a.sidecar.qualityHeight : 0;
        Path temp = transcodingService.runGapTranscode(video, source.toFile(), gapStart, duration,
                audioTrackIndex, qualityHeight);
        if (temp == null) {
            LOG.warn("Gap transcode returned no file for gap [{}, {})", gapStart, gapEnd);
            return null;
        }
        ProbeParams gapParams = probeParams(temp);
        ProbeParams pParams = probeParams(a.file);
        ProbeParams nParams = probeParams(b.file);
        if (gapParams == null || !paramsMatch(gapParams, pParams) || !paramsMatch(gapParams, nParams)) {
            LOG.warn("Gap segment param mismatch (gap={}, P={}, N={}); discarding gap, keeping segments",
                    gapParams, pParams, nParams);
            deleteFileQuietly(temp);
            return null;
        }
        return temp;
    }

    /** Moves a produced gap temp into the chain as a proper complete segment. */
    private void registerGapSegment(int absHash, Path gapTemp, double gapStart, double gapEnd,
                                    SegmentSidecar template) {
        try {
            Path finalFile = segmentPath(absHash, gapStart);
            moveWithRetry(gapTemp, finalFile);
            SegmentSidecar sidecar = new SegmentSidecar();
            sidecar.videoId = template.videoId;
            sidecar.startSeconds = gapStart;
            sidecar.endSeconds = gapEnd;
            sidecar.audioTrackIndex = template.audioTrackIndex;
            sidecar.qualityHeight = template.qualityHeight;
            sidecar.status = STATUS_COMPLETE;
            sidecar.sourcePath = template.sourcePath;
            sidecar.sourceMtime = template.sourceMtime;
            sidecar.sourceSize = template.sourceSize;
            writeSidecar(finalFile, sidecar);
            LOG.info("Registered gap segment {} covering [{}, {})", finalFile.getFileName(), gapStart, gapEnd);
        } catch (IOException e) {
            LOG.warn("Failed to register gap segment [{}, {}): {}", gapStart, gapEnd, e.getMessage());
            deleteFileQuietly(gapTemp);
        }
    }

    /**
     * Concatenates the complete prefix of the chain into one contiguous file.
     * <ul>
     *   <li>skips when fewer than 2 complete segments exist,</li>
     *   <li>defers when any constituent has active readers (bounded retries),</li>
     *   <li>ffprobe param gate before concat (skip concat on hard mismatch —
     *       segments keep serving correctly),</li>
     *   <li>byte-append: first segment's ftyp+moov, then each segment's
     *       fragments (earlier segment trimmed at the next segment's start),</li>
     *   <li>atomic swap with retry, then delete constituents.</li>
     * </ul>
     */
    private void mergeChain(int absHash) {
        List<SegmentEntry> chain = scanChain(absHash);
        if (chain.size() < 2) {
            return;
        }
        // A growing segment is the live chain head — merge only the complete prefix.
        int lastCompleteIndex = chain.size() - 1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).isGrowing()) {
                lastCompleteIndex = i - 1;
                break;
            }
        }
        if (lastCompleteIndex < 1) {
            return;
        }
        List<SegmentEntry> mergeSet = new ArrayList<>(chain.subList(0, lastCompleteIndex + 1));

        // Reader guard: never swap/delete a segment that is being served.
        for (SegmentEntry entry : mergeSet) {
            if (getActiveReaderCount(entry.file) > 0) {
                deferMerge(absHash);
                return;
            }
        }

        // Param gate: every constituent must share codec params with the first.
        ProbeParams reference = probeParams(mergeSet.get(0).file);
        if (reference == null) {
            LOG.warn("Cannot probe first segment of chain {}, skipping concat", absHash);
            return;
        }
        for (SegmentEntry entry : mergeSet) {
            ProbeParams params = probeParams(entry.file);
            if (!paramsMatch(reference, params)) {
                LOG.warn("Param mismatch in chain {} ({} vs {}), skipping concat (segments remain valid)",
                        absHash, reference, params);
                return;
            }
        }

        Path mergedFinal;
        Path mergedTemp = null;
        try {
            mergedFinal = segmentPath(absHash, mergeSet.get(0).sidecar.startSeconds);
            mergedTemp = mergedFinal.resolveSibling(mergedFinal.getFileName().toString() + ".tmp");
            concatSegments(mergeSet, mergedTemp);

            ProbeParams mergedParams = probeParams(mergedTemp);
            if (mergedParams == null || !paramsMatch(reference, mergedParams)) {
                LOG.warn("Merged output param mismatch for chain {}, discarding merge", absHash);
                deleteFileQuietly(mergedTemp);
                return;
            }

            moveWithRetry(mergedTemp, mergedFinal);

            SegmentSidecar first = mergeSet.get(0).sidecar;
            SegmentSidecar last = mergeSet.get(mergeSet.size() - 1).sidecar;
            SegmentSidecar merged = new SegmentSidecar();
            merged.videoId = first.videoId;
            merged.startSeconds = first.startSeconds;
            merged.endSeconds = last.endSeconds != null ? last.endSeconds : last.startSeconds;
            merged.audioTrackIndex = first.audioTrackIndex;
            merged.qualityHeight = first.qualityHeight;
            merged.status = STATUS_COMPLETE;
            merged.sourcePath = first.sourcePath;
            merged.sourceMtime = first.sourceMtime;
            merged.sourceSize = first.sourceSize;
            writeSidecar(mergedFinal, merged);
            // The merged file IS a fully-complete cache: mark it so the existing
            // LRU-cap / 7-day TTL sweeps treat it as a complete cache file.
            try {
                Files.write(doneMarkerPath(mergedFinal), new byte[0]);
            } catch (IOException e) {
                LOG.warn("Failed to write completion marker for merged file {}: {}", mergedFinal, e.getMessage());
            }
            LOG.info("Merged chain {} into {} covering [{}, {})",
                    absHash, mergedFinal.getFileName(), merged.startSeconds, merged.endSeconds);

            // Delete constituents (skip any that gained a reader mid-merge).
            // CRITICAL: the FIRST constituent's file path IS mergedFinal's path
            // (segmentPath(absHash, first.startSeconds)) — the merged output was moved
            // onto it. Deleting it would erase the merged file plus its fresh
            // sidecar/.done, leaving the chain empty and every later seek missing
            // coverage (and spawning duplicate transcodes).
            Path mergedFinalNormalized = mergedFinal.toAbsolutePath().normalize();
            for (SegmentEntry entry : mergeSet) {
                if (entry.file.toAbsolutePath().normalize().equals(mergedFinalNormalized)) {
                    LOG.debug("Keeping merged output {} (was the first constituent)", entry.file.getFileName());
                    continue;
                }
                if (getActiveReaderCount(entry.file) > 0) {
                    LOG.debug("Keeping constituent {} (active reader)", entry.file.getFileName());
                    continue;
                }
                deleteFileWithRetry(entry.file);
                deleteFileWithRetry(sidecarPath(entry.file));
                deleteFileWithRetry(doneMarkerPath(entry.file));
            }
            mergeDeferCounts.remove(String.valueOf(absHash));
        } catch (IOException e) {
            LOG.warn("Merge concat failed for chain {}: {}", absHash, e.getMessage());
            if (mergedTemp != null) {
                deleteFileQuietly(mergedTemp);
            }
            return;
        }

        // Re-scan: if a new segment appeared while merging, schedule another pass.
        Set<Double> mergedStarts = mergeSet.stream()
                .map(e -> e.sidecar.startSeconds)
                .collect(Collectors.toSet());
        boolean hasNew = scanChain(absHash).stream()
                .anyMatch(e -> !mergedStarts.contains(e.sidecar.startSeconds));
        if (hasNew) {
            scheduleBackfill(absHash);
        }
    }

    private void deferMerge(int absHash) {
        int count = mergeDeferCounts.merge(String.valueOf(absHash), 1, Integer::sum);
        if (count > MERGE_DEFER_MAX) {
            LOG.info("Merge for chain {} deferred too many times; leaving segments separate until next trigger", absHash);
            mergeDeferCounts.remove(String.valueOf(absHash));
            return;
        }
        String key = "defer-" + absHash + "-" + count;
        if (pendingBackfills.add(key)) {
            backfillExecutor.schedule(() -> {
                pendingBackfills.remove(key);
                runBackfillPass(absHash);
            }, MERGE_DEFER_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Appends the fragments of each segment in position order into outTemp:
     * the first segment contributes from byte 0 (ftyp+moov), every later
     * segment contributes only its fragments (from its first moof), and each
     * segment except the last is trimmed at the next segment's start to resolve
     * overlap between forward- and backward-created segments. Later segments'
     * tfdt values are shifted by their absolute start delta so the merged
     * timeline stays monotonic (each segment is 0-based relative).
     */
    private void concatSegments(List<SegmentEntry> mergeSet, Path outTemp) throws IOException {
        double firstStart = mergeSet.get(0).sidecar.startSeconds;
        // Per-track timescales (identical across constituents thanks to the param gate)
        // translate the start delta into tfdt ticks when shifting.
        Map<Integer, Integer> trackTimescales = null;
        try {
            trackTimescales = FragmentedMp4Seeker.readTrackTimescales(mergeSet.get(0).file);
        } catch (IOException e) {
            LOG.warn("Failed to read track timescales from {}: {}", mergeSet.get(0).file, e.getMessage());
        }
        try (OutputStream out = Files.newOutputStream(outTemp)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            for (int i = 0; i < mergeSet.size(); i++) {
                SegmentEntry entry = mergeSet.get(i);
                long fileSize = Files.size(entry.file);
                long startOffset = i == 0 ? 0 : FragmentedMp4Seeker.firstFragmentOffset(entry.file);
                long endOffset = fileSize;
                if (i + 1 < mergeSet.size()) {
                    // truncateLengthAtTime compares against the segment's own 0-based
                    // timeline, so express the next segment's start relatively.
                    double nextStartRel = Math.max(0.0,
                            mergeSet.get(i + 1).sidecar.startSeconds - entry.sidecar.startSeconds);
                    long truncLength = FragmentedMp4Seeker.truncateLengthAtTime(entry.file, nextStartRel);
                    endOffset = Math.min(truncLength, fileSize);
                }
                if (startOffset >= endOffset) {
                    LOG.warn("Segment {} has no data to append (start={}, end={})", entry.file, startOffset, endOffset);
                    continue;
                }
                long length = endOffset - startOffset;
                if (i == 0 || trackTimescales == null) {
                    copyRange(entry.file, out, startOffset, length, buffer);
                } else {
                    // Each segment past the first restarts its timeline at 0; shift its tfdt
                    // by the absolute start delta so merged lookups (target - firstStart)
                    // resolve to the right fragments.
                    double deltaSeconds = entry.sidecar.startSeconds - firstStart;
                    FragmentedMp4Seeker.copyFragmentsShifted(entry.file, startOffset, length,
                            deltaSeconds, trackTimescales, out);
                }
            }
        }
    }

    private void copyRange(Path file, OutputStream out, long offset, long length, byte[] buffer) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    if (in.read() == -1) {
                        throw new IOException("Unexpected EOF while skipping in " + file);
                    }
                    skipped++;
                } else {
                    skipped += n;
                }
            }
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    throw new IOException("Unexpected EOF while copying " + file);
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    // ── ffprobe param gate ─────────────────────────────────────────────────

    /** Codec parameters used to guarantee segments are concatenable. */
    private static final class ProbeParams {
        String videoCodec;
        int width = -1;
        int height = -1;
        String pixFmt = "";
        String audioCodec = "";
        String sampleRate = "";
        int channels = -1;

        @Override
        public String toString() {
            return "{v=" + videoCodec + " " + width + "x" + height + " pix=" + pixFmt
                    + ", a=" + audioCodec + " " + sampleRate + "Hz " + channels + "ch}";
        }
    }

    private ProbeParams probeParams(Path file) {
        String ffprobe = discoveryService.findFFprobeExecutable();
        if (ffprobe == null) {
            LOG.warn("ffprobe not found, cannot run param gate");
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobe, "-v", "error",
                    "-show_entries", "stream=codec_type,codec_name,width,height,pix_fmt,sample_rate,channels",
                    "-of", "json",
                    file.toAbsolutePath().toString());
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            process.getErrorStream().close();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("ffprobe timed out on {}", file);
                return null;
            }
            if (process.exitValue() != 0) {
                LOG.warn("ffprobe failed (exit {}) on {}", process.exitValue(), file);
                return null;
            }
            JsonNode root = objectMapper.readTree(output);
            JsonNode streams = root.get("streams");
            if (streams == null || !streams.isArray() || streams.isEmpty()) {
                LOG.warn("ffprobe returned no streams for {}", file);
                return null;
            }
            ProbeParams params = new ProbeParams();
            for (JsonNode stream : streams) {
                String type = stream.path("codec_type").asText("");
                if ("video".equals(type) && params.videoCodec == null) {
                    params.videoCodec = stream.path("codec_name").asText("");
                    params.width = stream.path("width").asInt(-1);
                    params.height = stream.path("height").asInt(-1);
                    params.pixFmt = stream.path("pix_fmt").asText("");
                } else if ("audio".equals(type) && params.audioCodec.isEmpty()) {
                    params.audioCodec = stream.path("codec_name").asText("");
                    params.sampleRate = stream.path("sample_rate").asText("");
                    params.channels = stream.path("channels").asInt(-1);
                }
            }
            return params.videoCodec == null ? null : params;
        } catch (Exception e) {
            LOG.warn("Failed to probe params of {}: {}", file, e.getMessage());
            return null;
        }
    }

    private boolean paramsMatch(ProbeParams a, ProbeParams b) {
        if (a == null || b == null) {
            return false;
        }
        return a.videoCodec != null && a.videoCodec.equals(b.videoCodec)
                && a.width == b.width && a.height == b.height
                && a.pixFmt != null && a.pixFmt.equals(b.pixFmt)
                && a.audioCodec.equals(b.audioCodec)
                && a.sampleRate.equals(b.sampleRate)
                && a.channels == b.channels;
    }

    // ── File helpers ───────────────────────────────────────────────────────

    private void moveWithRetry(Path temp, Path finalPath) throws IOException {
        for (int i = 0; i < MAX_FILE_OP_ATTEMPTS; i++) {
            try {
                Files.move(temp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                LOG.warn("Failed to move {} to {} (attempt {}/{}): {}",
                        temp, finalPath, i + 1, MAX_FILE_OP_ATTEMPTS, e.getMessage());
                if (i < MAX_FILE_OP_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    private void deleteFileWithRetry(Path path) {
        for (int i = 0; i < MAX_FILE_OP_ATTEMPTS; i++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException e) {
                LOG.warn("Failed to delete {} (attempt {}/{}): {}", path, i + 1, MAX_FILE_OP_ATTEMPTS, e.getMessage());
                if (i < MAX_FILE_OP_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        backfillExecutor.shutdownNow();
    }
}
