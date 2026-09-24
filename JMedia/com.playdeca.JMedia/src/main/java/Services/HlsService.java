package Services;

import Models.Video.AudioTrack;
import Models.Video.SubtitleTrack;
import Models.Video.Video;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HlsService {

    private static final Logger LOG = LoggerFactory.getLogger(HlsService.class);
    private static final String VIDEO_VARIANT = "video_stream";
    private static final boolean USE_FMP4_HLS = true;
    private static final int MAX_ACTIVE_SESSIONS = 8;
    private static final int HW_ENCODER_MAX_RETRIES = 3;

    /**
     * ISO 639-2 (bibliographic) → ISO 639-1 (2-letter) for the SUBTITLES LANGUAGE attribute.
     */
    private static final Map<String, String> ISO_639_2_TO_1 = Map.ofEntries(
        Map.entry("aar", "aa"), Map.entry("abk", "ab"), Map.entry("afr", "af"),
        Map.entry("amh", "am"), Map.entry("ara", "ar"), Map.entry("arg", "an"),
        Map.entry("asm", "as"), Map.entry("ava", "av"), Map.entry("ave", "ae"),
        Map.entry("aym", "ay"), Map.entry("aze", "az"), Map.entry("bak", "ba"),
        Map.entry("bam", "bm"), Map.entry("bel", "be"), Map.entry("ben", "bn"),
        Map.entry("bis", "bi"), Map.entry("bod", "bo"), Map.entry("bos", "bs"),
        Map.entry("bre", "br"), Map.entry("bul", "bg"), Map.entry("cat", "ca"),
        Map.entry("ces", "cs"), Map.entry("cha", "ch"), Map.entry("che", "ce"),
        Map.entry("chu", "cu"), Map.entry("chv", "cv"), Map.entry("cor", "kw"),
        Map.entry("cos", "co"), Map.entry("cre", "cr"), Map.entry("cym", "cy"),
        Map.entry("dan", "da"), Map.entry("deu", "de"), Map.entry("div", "dv"),
        Map.entry("dzo", "dz"), Map.entry("ell", "el"), Map.entry("eng", "en"),
        Map.entry("epo", "eo"), Map.entry("est", "et"), Map.entry("eus", "eu"),
        Map.entry("ewe", "ee"), Map.entry("fao", "fo"), Map.entry("fas", "fa"),
        Map.entry("fij", "fj"), Map.entry("fin", "fi"), Map.entry("fra", "fr"),
        Map.entry("fry", "fy"), Map.entry("ful", "ff"), Map.entry("gla", "gd"),
        Map.entry("gle", "ga"), Map.entry("glg", "gl"), Map.entry("glv", "gv"),
        Map.entry("grn", "gn"), Map.entry("guj", "gu"), Map.entry("hat", "ht"),
        Map.entry("hau", "ha"), Map.entry("heb", "he"), Map.entry("her", "hz"),
        Map.entry("hin", "hi"), Map.entry("hmo", "ho"), Map.entry("hrv", "hr"),
        Map.entry("hun", "hu"), Map.entry("hye", "hy"), Map.entry("ibo", "ig"),
        Map.entry("ido", "io"), Map.entry("iii", "ii"), Map.entry("iku", "iu"),
        Map.entry("ile", "ie"), Map.entry("ina", "ia"), Map.entry("ind", "id"),
        Map.entry("ipk", "ik"), Map.entry("isl", "is"), Map.entry("ita", "it"),
        Map.entry("jav", "jv"), Map.entry("jpn", "ja"), Map.entry("kal", "kl"),
        Map.entry("kan", "kn"), Map.entry("kas", "ks"), Map.entry("kat", "ka"),
        Map.entry("kau", "kr"), Map.entry("kaz", "kk"), Map.entry("khm", "km"),
        Map.entry("kik", "ki"), Map.entry("kin", "rw"), Map.entry("kir", "ky"),
        Map.entry("kom", "kv"), Map.entry("kon", "kg"), Map.entry("kor", "ko"),
        Map.entry("kua", "kj"), Map.entry("kur", "ku"), Map.entry("lao", "lo"),
        Map.entry("lat", "la"), Map.entry("lav", "lv"), Map.entry("lim", "li"),
        Map.entry("lin", "ln"), Map.entry("lit", "lt"), Map.entry("ltz", "lb"),
        Map.entry("lub", "lu"), Map.entry("lug", "lg"), Map.entry("mah", "mh"),
        Map.entry("mal", "ml"), Map.entry("mar", "mr"), Map.entry("mkd", "mk"),
        Map.entry("mlg", "mg"), Map.entry("mlt", "mt"), Map.entry("mon", "mn"),
        Map.entry("mri", "mi"), Map.entry("msa", "ms"), Map.entry("mya", "my"),
        Map.entry("nau", "na"), Map.entry("nav", "nv"), Map.entry("nbl", "nr"),
        Map.entry("nde", "nd"), Map.entry("ndo", "ng"), Map.entry("nep", "ne"),
        Map.entry("nld", "nl"), Map.entry("nno", "nn"), Map.entry("nob", "nb"),
        Map.entry("nor", "no"), Map.entry("nya", "ny"), Map.entry("oci", "oc"),
        Map.entry("oji", "oj"), Map.entry("ori", "or"), Map.entry("orm", "om"),
        Map.entry("oss", "os"), Map.entry("pan", "pa"), Map.entry("pli", "pi"),
        Map.entry("pol", "pl"), Map.entry("por", "pt"), Map.entry("pus", "ps"),
        Map.entry("que", "qu"), Map.entry("roh", "rm"), Map.entry("ron", "ro"),
        Map.entry("run", "rn"), Map.entry("rus", "ru"), Map.entry("sag", "sg"),
        Map.entry("san", "sa"), Map.entry("sin", "si"), Map.entry("slk", "sk"),
        Map.entry("slv", "sl"), Map.entry("sme", "se"), Map.entry("smo", "sm"),
        Map.entry("sna", "sn"), Map.entry("snd", "sd"), Map.entry("som", "so"),
        Map.entry("sot", "st"), Map.entry("spa", "es"), Map.entry("sqi", "sq"),
        Map.entry("srd", "sc"), Map.entry("srp", "sr"), Map.entry("ssw", "ss"),
        Map.entry("sun", "su"), Map.entry("swa", "sw"), Map.entry("swe", "sv"),
        Map.entry("tah", "ty"), Map.entry("tam", "ta"), Map.entry("tat", "tt"),
        Map.entry("tel", "te"), Map.entry("tgk", "tg"), Map.entry("tgl", "tl"),
        Map.entry("tha", "th"), Map.entry("tir", "ti"), Map.entry("ton", "to"),
        Map.entry("tsn", "tn"), Map.entry("tso", "ts"), Map.entry("tuk", "tk"),
        Map.entry("tur", "tr"), Map.entry("twi", "tw"), Map.entry("uig", "ug"),
        Map.entry("ukr", "uk"), Map.entry("urd", "ur"), Map.entry("uzb", "uz"),
        Map.entry("ven", "ve"), Map.entry("vie", "vi"), Map.entry("vol", "vo"),
        Map.entry("wln", "wa"), Map.entry("wol", "wo"), Map.entry("xho", "xh"),
        Map.entry("yid", "yi"), Map.entry("yor", "yo"), Map.entry("zha", "za"),
        Map.entry("zul", "zu"),
        // Legacy / alternate bibliographic codes still seen in ffprobe tags
        Map.entry("alb", "sq"), Map.entry("arm", "hy"), Map.entry("baq", "eu"),
        Map.entry("bur", "my"), Map.entry("chi", "zh"), Map.entry("cze", "cs"),
        Map.entry("dut", "nl"), Map.entry("fre", "fr"), Map.entry("geo", "ka"),
        Map.entry("ger", "de"), Map.entry("gre", "el"), Map.entry("ice", "is"),
        Map.entry("mac", "mk"), Map.entry("mao", "mi"), Map.entry("may", "ms"),
        Map.entry("per", "fa"), Map.entry("rum", "ro"), Map.entry("slo", "sk"),
        Map.entry("tib", "bo"), Map.entry("wel", "cy"),
        // JMedia-specific codes
        Map.entry("spl", "es")
    );

    private static class VariantConfig {
        final String name;
        final int height;
        final int bandwidth;
        final boolean useHardware;

        VariantConfig(String name, int height, int bandwidth, boolean useHardware) {
            this.name = name;
            this.height = height;
            this.bandwidth = bandwidth;
            this.useHardware = useHardware;
        }
    }

    private List<VariantConfig> determineVariants(Video video, int qualityHeight) {
        int sourceHeight = 1080;
        try {
            if (video.resolution != null && video.resolution.contains("x")) {
                sourceHeight = Integer.parseInt(video.resolution.split("x")[1]);
            }
        } catch (Exception ignored) {}

        if (qualityHeight > 0) {
            int h = Math.min(qualityHeight, sourceHeight);
            return Collections.singletonList(new VariantConfig(VIDEO_VARIANT, h, 3000000, true));
        }

        // Default: cap at 720p, or use native resolution if lower.
        // Higher qualities (1080p, 4K) are only used when the user
        // explicitly selects them from the quality menu.
        int defaultHeight = Math.min(720, sourceHeight);
        return Collections.singletonList(new VariantConfig(VIDEO_VARIANT, defaultHeight, 1000000, true));
    }

    @Inject VideoService videoService;
    @Inject SettingsService settingsService;
    @Inject FFmpegDiscoveryService ffmpegDiscoveryService;
    @Inject GpuScheduler gpuScheduler;
    @Inject XtreamSessionService xtreamSessionService;
    @Inject SubtitleTrackService subtitleTrackService;
    @Inject PgsOcrService pgsOcrService;

    private final Map<String, HlsSession> activeSessions = new ConcurrentHashMap<>();
    private Path hlsBasePath;

    public HlsSession createSession(Long videoId, double startSeconds, Long profileId, String deviceToken) throws IOException {
        return createSession(videoId, startSeconds, profileId, null, null, deviceToken);
    }

    public HlsSession createSession(Long videoId, double startSeconds, Long profileId, Integer preferredAudioTrackIndex, String deviceToken) throws IOException {
        return createSession(videoId, startSeconds, profileId, preferredAudioTrackIndex, null, deviceToken);
    }

    @Transactional
    public HlsSession createSession(Long videoId, double startSeconds, Long profileId, Integer preferredAudioTrackIndex, Integer qualityHeight, String deviceToken) throws IOException {
        String safeDeviceToken = (deviceToken != null && !deviceToken.isBlank()) ? deviceToken : "unknown";
        // Include profileId so different profiles never share a key; append a
        // random nonce so two anonymous clients playing the same video cannot collide.
        String profilePart = profileId != null ? String.valueOf(profileId) : "anon";
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String sessionId = "vid-" + videoId + "-" + safeDeviceToken + "-" + profilePart + "-" + nonce;

        // The random nonce keeps the sessionId unique (Xtream/external clients depend on the
        // vid-{videoId}-{device}-{profile}-{nonce} format), so a plain map lookup can never find
        // a prior session. Instead, deterministically scan for an existing session for the same
        // video+device+profile and destroy it first — otherwise every re-create spawns a duplicate
        // transcode and orphans the previous FFmpeg process.
        HlsSession existing = findSessionByVideoDeviceProfile(videoId, safeDeviceToken, profileId);
        if (existing != null) {
            LOG.info("Destroying existing HLS session {} for same video/device/profile re-creation", existing.sessionId);
            endXtreamLinkedSession(existing, "disconnected");
            destroySession(existing.sessionId);
        }

        Video video = videoService.findById(videoId);
        if (video == null) throw new IOException("Video not found: " + videoId);

        // Re-sync subtitle tracks before snapshotting them into the HLS session so IPTV/Xtream
        // clients playing the master playlist see freshly discovered subtitles (external sidecars,
        // AI-generated, Subs/ subfolders). Discovery is best-effort: if it fails, keep whatever was
        // already persisted and stream on — never block session creation on subtitle discovery.
        if (video.path != null && !video.path.isBlank()) {
            try {
                subtitleTrackService.refreshSubtitleTracks(video);
            } catch (Exception e) {
                LOG.warn("Subtitle discovery failed for video {} ({}); continuing with persisted subtitle tracks", videoId, video.title, e);
            }
        }

        Path sessionDir = getHlsBasePath().resolve(sessionId).toAbsolutePath();
        Files.createDirectories(sessionDir);
        cleanupSessionDirectory(sessionDir);
        List<AudioTrack> audioTracks = video.audioTracks != null ? new ArrayList<>(video.audioTracks) : new ArrayList<>();
        List<SubtitleTrack> subtitleTracks = video.subtitleTracks != null ? new ArrayList<>(video.subtitleTracks) : new ArrayList<>();
        HlsSession session = new HlsSession(sessionId, video, audioTracks, subtitleTracks, sessionDir, startSeconds);
        session.deviceToken = safeDeviceToken;
        session.profileId = profileId;
        
        if (qualityHeight != null && qualityHeight > 0) {
            session.qualityHeight = qualityHeight;
            LOG.info("HLS session created with quality height: {}p", qualityHeight);
        }
        
        // Set preferred audio track if specified
        if (preferredAudioTrackIndex != null && preferredAudioTrackIndex >= 0) {
            session.setPreferredAudioTrackIndex(preferredAudioTrackIndex);
            LOG.info("HLS session created with preferred audio track index: {}", preferredAudioTrackIndex);
        }
        
        // Evict oldest session if at capacity so a new request is never rejected
        if (activeSessions.size() >= MAX_ACTIVE_SESSIONS) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, HlsSession> e : activeSessions.entrySet()) {
                if (e.getValue().createdAt < oldestTime) {
                    oldestTime = e.getValue().createdAt;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                LOG.info("Evicting oldest HLS session {} to make room (cap={})", oldestKey, MAX_ACTIVE_SESSIONS);
                destroySession(oldestKey);
            }
        }

        int qh = (qualityHeight != null && qualityHeight > 0) ? qualityHeight : 0;
        List<VariantConfig> variants = determineVariants(video, qh);
        session.variants = variants;

        activeSessions.put(sessionId, session);

        for (VariantConfig variant : variants) {
            startVariantEncoder(session, variant, profileId);
        }
        return session;
    }

    private HlsSession findSessionByVideoDeviceProfile(Long videoId, String deviceToken, Long profileId) {
        for (HlsSession session : activeSessions.values()) {
            if (matchesVideoDeviceProfile(session, videoId, deviceToken, profileId)) {
                return session;
            }
        }
        return null;
    }

    private boolean matchesVideoDeviceProfile(HlsSession session, Long videoId, String deviceToken, Long profileId) {
        if (session == null || session.video == null || videoId == null) return false;
        if (!videoId.equals(session.video.id)) return false;
        String sessionDevice = session.deviceToken != null ? session.deviceToken : "unknown";
        if (!sessionDevice.equals(deviceToken)) return false;
        Long sessionProfile = session.profileId != null ? session.profileId : -1L;
        Long targetProfile = profileId != null ? profileId : -1L;
        return sessionProfile.equals(targetProfile);
    }

    private void startVariantEncoder(HlsSession session, VariantConfig variant, Long profileId) {
        if (!variant.useHardware) {
            try {
                launchAndMonitorVariantEncoder(session, variant, profileId, false);
            } catch (IOException e) {
                LOG.error("Failed to start SW encoder for session {} variant {}: {}", session.sessionId, variant.name, e.getMessage());
            }
            return;
        }
        startHwEncoder(session, variant, profileId);
    }

    private void launchAndMonitorVariantEncoder(HlsSession session, VariantConfig variant, Long profileId, boolean useHardware) throws IOException {
        Process process = startVariantEncoderProcess(session, variant, profileId, useHardware);

        // Check for early crash (first 2 seconds)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            String err = readProcessOutput(process);
            LOG.debug("HLS encoder for session {} variant {} exited early (code {}): {}", session.sessionId, variant.name, exitCode, err);
            if (exitCode != 0) {
                if (useHardware && isHardwareError(err)) {
                    throw new IOException("HW encoder failed early with exit code " + exitCode);
                }
                throw new IOException("FFmpeg exited with code " + exitCode + ": " + err);
            }
        }

        session.addProcess(variant.name, process);

        if (!process.isAlive()) {
            LOG.info("HLS encoder for session {} variant {} finished normally, stream complete", session.sessionId, variant.name);
            finalizePlaylist(session, variant.name);
            return;
        }

        startEncoderMonitor(session, variant, profileId, process, useHardware);
        LOG.info("Started HLS encoder for session {} variant {} ({}acceleration)", session.sessionId, variant.name, useHardware ? "HW " : "software ");
    }

    // ── HW encoder: clean restart with background retry loop ─────────────

    private void startHwEncoder(HlsSession session, VariantConfig variant, Long profileId) {
        if (session.stopped) return;
        String vName = variant.name;
        LOG.info("Starting HW encoder for session {} variant {}", session.sessionId, vName);

        cleanVariantFiles(session, vName);

        try {
            Process process = startVariantEncoderProcess(session, variant, profileId, true);

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                String output = readProcessOutput(process);
                session.removeProcess(vName);
                session.lastRestartTimes.put(vName, System.currentTimeMillis());

                // Exit code 0 means the encoder finished the whole stream (VOD
                // end-of-file): finalize the playlist and stop — do NOT restart.
                if (exitCode == 0) {
                    LOG.info("HLS encoder {} for session {} finished normally, stream complete", vName, session.sessionId);
                    finalizePlaylist(session, vName);
                    return;
                }

                int attempt = session.getRestartCount(vName) + 1;
                session.incrementRestartCount(vName);
                if (attempt >= HW_ENCODER_MAX_RETRIES) {
                    LOG.warn("HW encoder for {} exited early (code {}), {} consecutive failures, falling back to software. Last output: {}", vName, exitCode, attempt, truncateForLog(output));
                    fallBackToSoftwareEncoder(session, variant, profileId);
                } else {
                    LOG.warn("HW encoder for {} exited early (code {}), will retry in 10s (attempt {}/{}). Output: {}", vName, exitCode, attempt, HW_ENCODER_MAX_RETRIES, truncateForLog(output));
                    scheduleHwRetry(session, variant, profileId);
                }
                return;
            }

            session.addProcess(vName, process);
            startHwMonitor(session, variant, profileId, process);
        } catch (IOException e) {
            LOG.error("Failed to start HW encoder for {}: {}", vName, e.getMessage());
            scheduleHwRetry(session, variant, profileId);
        }
    }

    private void fallBackToSoftwareEncoder(HlsSession session, VariantConfig variant, Long profileId) {
        LOG.warn("Falling back to software encoder for session {} variant {}", session.sessionId, variant.name);
        cleanVariantFiles(session, variant.name);
        try {
            launchAndMonitorVariantEncoder(session, variant, profileId, false);
        } catch (IOException e) {
            LOG.error("Failed to start software encoder for session {} variant {}: {}", session.sessionId, variant.name, e.getMessage());
        }
    }

    private String truncateForLog(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) + "..." : t;
    }

    private void endXtreamLinkedSession(HlsSession session, String reason) {
        if (session != null && session.deviceToken != null) {
            xtreamSessionService.endByDeviceToken(session.deviceToken, reason);
        }
    }

    /** Appends #EXT-X-ENDLIST so HLS clients stop at the end of a completed stream. */
    private void finalizePlaylist(HlsSession session, String variantName) {
        try {
            Path playlistFile = session.sessionDir.resolve(variantName + ".m3u8");
            if (!Files.exists(playlistFile)) {
                return;
            }
            String content = Files.readString(playlistFile);
            if (!content.contains("#EXT-X-ENDLIST")) {
                Files.writeString(playlistFile, content + "#EXT-X-ENDLIST\n");
                LOG.info("Finalized HLS playlist for session {} variant {} (#EXT-X-ENDLIST added)", session.sessionId, variantName);
            }
        } catch (IOException e) {
            LOG.warn("Failed to finalize playlist for session {} variant {}: {}", session.sessionId, variantName, e.getMessage());
        }
        // A clean EOF means the Xtream stream finished; endSession is idempotent so
        // multiple variants calling this are harmless.
        endXtreamLinkedSession(session, "completed");
    }

    private void startHwMonitor(HlsSession session, VariantConfig variant, Long profileId, Process process) {
        String vName = variant.name;
        Thread monitor = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug("[ffmpeg {}] {}", vName, line);
                }
            } catch (IOException e) {
                LOG.warn("Error reading ffmpeg output for {}: {}", vName, e.getMessage());
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int exitCode = process.exitValue();
            session.removeProcess(vName);
            session.lastRestartTimes.put(vName, System.currentTimeMillis());

            // Exit code 0 means the encoder finished the whole stream (VOD
            // end-of-file): finalize the playlist and stop — do NOT restart.
            if (exitCode == 0) {
                LOG.info("HLS encoder {} for session {} finished normally, stream complete", vName, session.sessionId);
                finalizePlaylist(session, vName);
                return;
            }

            int attempt = session.getRestartCount(vName) + 1;
            session.incrementRestartCount(vName);
            if (attempt >= HW_ENCODER_MAX_RETRIES) {
                LOG.warn("HW encoder {} exited (code {}), {} consecutive failures, falling back to software", vName, exitCode, attempt);
                fallBackToSoftwareEncoder(session, variant, profileId);
            } else {
                LOG.warn("HW encoder {} exited (code {}), will retry in 10s (attempt {}/{})", vName, exitCode, attempt, HW_ENCODER_MAX_RETRIES);
                scheduleHwRetry(session, variant, profileId);
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }

    private void scheduleHwRetry(HlsSession session, VariantConfig variant, Long profileId) {
        if (session.stopped) return;
        session.lastRestartTimes.put(variant.name, System.currentTimeMillis());
        hwRetryExecutor.schedule(() -> {
            if (session.stopped) return;
            startHwEncoder(session, variant, profileId);
        }, 10, TimeUnit.SECONDS);
    }

    private void cleanVariantFiles(HlsSession session, String variantName) {
        Path dir = session.sessionDir;
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, variantName + "_*.m4s")) {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            LOG.warn("Failed to clean segment files for {}: {}", variantName, e.getMessage());
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, variantName + "_*.ts")) {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            LOG.warn("Failed to clean TS segment files for {}: {}", variantName, e.getMessage());
        }
        try {
            Files.deleteIfExists(dir.resolve(variantName + "_init.mp4"));
        } catch (IOException e) {
            LOG.warn("Failed to clean init file for {}: {}", variantName, e.getMessage());
        }
        try {
            Files.deleteIfExists(dir.resolve(variantName + ".m3u8"));
        } catch (IOException e) {
            LOG.warn("Failed to clean playlist for {}: {}", variantName, e.getMessage());
        }
    }

    private Process startVariantEncoderProcess(HlsSession session, VariantConfig variant, Long profileId, boolean useHardware) throws IOException {
        String resolvedPath = resolveVideoPath(session.video.path);
        String ffmpegPath = ffmpegDiscoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg executable not found");
        }

        // Determine encoder first to match decoder output format
        String hwEncoder = "libx264";
        if (useHardware) {
            String detected = ffmpegDiscoveryService.detectHardwareEncoder();
            if (!"libx264".equals(detected)) {
                hwEncoder = detected;
            }
        }

        // ── Fast path: direct-stream copy for compatible H.264+AAC/AC3 sources ──
        if (isEligibleForCopyMode(session, variant)) {
            List<String> copyCommand = new ArrayList<>();
            copyCommand.add(ffmpegPath);
            copyCommand.add("-v"); copyCommand.add("error");
            copyCommand.add("-hide_banner");
            copyCommand.add("-ss"); copyCommand.add("0");
            copyCommand.add("-i"); copyCommand.add(resolvedPath);
            copyCommand.add("-map"); copyCommand.add("0:v:0");
            copyCommand.add("-c:v"); copyCommand.add("copy");
            if (session.audioTracks.isEmpty()) {
                copyCommand.add("-map"); copyCommand.add("0:a?");
            } else if (session.audioTracks.size() == 1) {
                // trackIndex is ffprobe's global stream index; 0:a:N is audio-relative and fails when audio isn't stream 0
                copyCommand.add("-map"); copyCommand.add("0:" + session.audioTracks.get(0).trackIndex);
            } else {
                copyCommand.add("-map"); copyCommand.add("0:a?");
            }
            copyCommand.add("-c:a"); copyCommand.add("copy");
            copyCommand.add("-f"); copyCommand.add("hls");
            copyCommand.add("-hls_time"); copyCommand.add("6");
            copyCommand.add("-hls_list_size"); copyCommand.add("0");
            if (USE_FMP4_HLS) {
                // No split_by_time: with -c:v copy, time splits cut mid-GOP and serve
                // dependent segments (the master declares INDEPENDENT-SEGMENTS).
                copyCommand.add("-hls_flags"); copyCommand.add("append_list+omit_endlist");
                copyCommand.add("-hls_segment_type"); copyCommand.add("fmp4");
                copyCommand.add("-hls_fmp4_init_filename"); copyCommand.add(variant.name + "_init.mp4");
                copyCommand.add("-hls_segment_filename");
                copyCommand.add(variant.name + "_%04d.m4s");
            } else {
                copyCommand.add("-hls_flags"); copyCommand.add("append_list+omit_endlist");
                copyCommand.add("-hls_segment_filename");
                copyCommand.add(variant.name + "_%05d.ts");
            }
            copyCommand.add(variant.name + ".m3u8");

            LOG.info("[HLS] session={} variant={} encoder=copy height={} hw=false audio=copy",
                session.sessionId, variant.name, variant.height);
            LOG.info("Starting HLS copy-mode encoder for session {} variant {}: {}", session.sessionId, variant.name, String.join(" ", copyCommand));

            ProcessBuilder pb = new ProcessBuilder(copyCommand);
            pb.directory(session.sessionDir.toFile());
            pb.redirectErrorStream(true);
            // Copy mode still needs the per-track WebVTT segmenters for the SUBTITLES renditions.
            createSubtitleStreams(session);
            return pb.start();
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // HW decoding (must be placed before -i)
        // Multi-GPU: acquire one capability-filtered lease per variant encoder.
        // AV1 jobs only match AV1-capable GPUs (e.g. Arc A380 over older iGPU);
        // H.264 jobs balance across all capable GPUs by live load.
        String hwDecoder = null;
        GpuScheduler.GpuLease gpuLease = null;
        if (useHardware) {
            hwDecoder = ffmpegDiscoveryService.getHardwareDecoder(session.video.videoCodec);
            if (hwDecoder != null && !"libx264".equals(hwEncoder)) {
                gpuLease = gpuScheduler.acquire(session.video.videoCodec, hwEncoder);
            }
            if (hwDecoder != null) {
                LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hwDecoder, session.video.videoCodec);
                if (hwDecoder.contains("cuvid")) {
                    command.add("-hwaccel"); command.add("cuda");
                    if (hwEncoder.contains("nvenc")) {
                        command.add("-hwaccel_output_format"); command.add("cuda");
                    }
                    String index = gpuLease != null && gpuLease.gpu().deviceIndex() >= 0
                        ? String.valueOf(gpuLease.gpu().deviceIndex())
                        : ffmpegDiscoveryService.getBestNvidiaDeviceIndex();
                    if (index != null) {
                        command.add("-hwaccel_device"); command.add(index);
                    }
                } else if (hwDecoder.contains("videotoolbox")) {
                    command.add("-hwaccel"); command.add("videotoolbox");
                } else if (hwDecoder.contains("qsv")) {
                    command.add("-hwaccel"); command.add("qsv");
                    if (hwEncoder.contains("qsv")) {
                        command.add("-hwaccel_output_format"); command.add("qsv");
                    }
                    String device = gpuLease != null && gpuLease.gpu().devicePath() != null
                        ? gpuLease.gpu().devicePath()
                        : ffmpegDiscoveryService.getBestQsvDevicePath();
                    if (device != null) {
                        command.add("-qsv_device"); command.add(device);
                    }
                } else if (hwDecoder.contains("vaapi")) {
                    command.add("-hwaccel"); command.add("vaapi");
                    if (hwEncoder.contains("vaapi")) {
                        command.add("-hwaccel_output_format"); command.add("vaapi");
                    }
                    String device = gpuLease != null && gpuLease.gpu().devicePath() != null
                        ? gpuLease.gpu().devicePath()
                        : ffmpegDiscoveryService.getBestVaaPiDevicePath();
                    if (device != null) {
                        command.add("-hwaccel_device"); command.add(device);
                    }
                } else if (hwDecoder.contains("amf")) {
                    command.add("-hwaccel"); command.add("amf");
                    if (hwEncoder.contains("amf")) {
                        command.add("-hwaccel_output_format"); command.add("amf");
                    }
                    GpuDetectionService.GpuInfo amfGpu = gpuLease != null
                        ? gpuLease.gpu()
                        : ffmpegDiscoveryService.getBestAmfGpu();
                    if (amfGpu != null && amfGpu.deviceIndex() >= 0) {
                        command.add("-hwaccel_device"); command.add(String.valueOf(amfGpu.deviceIndex()));
                    }
                } else if (hwDecoder.contains("d3d11va")) {
                    command.add("-hwaccel"); command.add("d3d11va");
                    if (hwEncoder != null && hwEncoder.contains("d3d11va")) {
                        command.add("-hwaccel_output_format"); command.add("d3d11");
                    }
                } else if (hwDecoder.contains("dxva2")) {
                    command.add("-hwaccel"); command.add("dxva2");
                }
            }
        }

        // True when decode frames stay on the device (-hwaccel_output_format matched
        // the encoder vendor): no auto-inserted SOFTWARE filter may touch them, so a
        // bare -pix_fmt would crash with "Impossible to convert ... src: cuda".
        boolean hwFramesOnDevice = useHardware && hwDecoder != null
                && ((hwDecoder.contains("cuvid") && hwEncoder.contains("nvenc"))
                    || (hwDecoder.contains("qsv") && hwEncoder.contains("qsv"))
                    || (hwDecoder.contains("vaapi") && hwEncoder.contains("vaapi"))
                    || (hwDecoder.contains("amf") && hwEncoder.contains("amf"))
                    || (hwDecoder.contains("d3d11va") && hwEncoder != null && hwEncoder.contains("d3d11va")));

        // Always start HLS encoding from the beginning of the file.
        // The player handles seeking to the correct position natively (via HLS seek).
        // Starting from an offset produces a sub-clip which causes bufferSeekOverHole errors
        // and confuses the player with mismatched stream duration vs real duration.
        command.add("-ss");
        command.add("0");
        command.add("-i");
        command.add(resolvedPath);

        // Audio handling
        if (session.audioTracks.isEmpty()) {
            command.add("-map");
            command.add("0:a?");
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
            command.add("-ac");
            command.add("2");
        } else if (session.audioTracks.size() == 1) {
            AudioTrack track = session.audioTracks.get(0);
            command.add("-map");
            command.add("0:" + track.trackIndex);
            command.add("-c:a");
            if (isCopyableCodec(track.codec)) {
                command.add("copy");
            } else {
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                command.add("-ac");
                command.add("2");
            }
        } else {
            command.add("-an");
            createAudioStreams(session);
        }

        // Subtitle renditions always run as separate WebVTT processes: tvOS
        // cannot select TS-embedded subtitles and only supports separate
        // WebVTT, so unlike audio there is no single-track mux-in case.
        createSubtitleStreams(session);

        // Video encoding
        command.add("-map");
        command.add("0:v:0");
        command.add("-c:v");

        if (useHardware && !"libx264".equals(hwEncoder)) {
            LOG.info("Using hardware encoder for HLS: {}", hwEncoder);
            command.add(hwEncoder);
            if (hwEncoder.contains("amf")) {
                command.add("-preset"); command.add("speed");
                command.add("-usage"); command.add("transcoding");
                command.add("-quality"); command.add("quality");
            } else {
                command.add("-preset"); command.add("fast");
            }
            if (hwEncoder.contains("nvenc")) {
                command.add("-rc"); command.add("vbr");
                command.add("-cq"); command.add("23");
            } else if (hwEncoder.contains("amf")) {
                // AMF uses -quality and -usage, not -rc/-cq
            } else if (hwEncoder.contains("qsv")) {
                command.add("-global_quality"); command.add("23");
            } else if (hwEncoder.contains("videotoolbox")) {
                command.add("-quality"); command.add("70");
            } else if (hwEncoder.contains("vaapi")) {
                command.add("-rc_mode"); command.add("CQP");
                command.add("-qp"); command.add("23");
            }
            if (hwEncoder.equals("libx264")) {
                command.add("-pix_fmt"); command.add("yuv420p");
            } else if (hwEncoder.contains("h264")) {
                boolean addedGpuFmtFilter = false;
                if (hwFramesOnDevice && buildScaleFilter(hwDecoder, hwEncoder, variant.height, session.video.resolution) == null) {
                    String fmtFilter = buildFormatFilter(hwDecoder, hwEncoder, session.video.resolution);
                    if (fmtFilter != null) {
                        command.add("-vf"); command.add(fmtFilter);
                        addedGpuFmtFilter = true;
                    } else {
                        command.add("-vf"); command.add("hwdownload");
                        addedGpuFmtFilter = true;
                    }
                }
                if (!addedGpuFmtFilter) {
                    command.add("-pix_fmt"); command.add("nv12");
                }
            }
        } else {
            command.add("libx264");
            command.add("-preset"); command.add("ultrafast");
            command.add("-crf"); command.add("23");
            command.add("-pix_fmt"); command.add("yuv420p");
        }

        // Scale video to variant height (preserving aspect ratio, no upscaling)
        if (variant.height > 0) {
            int sourceH = 1080;
            try {
                if (session.video.resolution != null && session.video.resolution.contains("x")) {
                    sourceH = Integer.parseInt(session.video.resolution.split("x")[1]);
                }
            } catch (Exception ignored) {}
            if (variant.height < sourceH) {
                String scaleFilter = buildScaleFilter(hwDecoder, hwEncoder, variant.height, session.video.resolution);
                if (scaleFilter != null) {
                    command.add("-vf");
                    command.add(scaleFilter);
                }
            }
        }

        // Force an IDR every segment duration so time-based splits land on keyframes
        // and every segment is independently decodable (see copy path note above).
        command.add("-force_key_frames"); command.add("expr:gte(t,n_forced*6)");

        // HLS output args
        command.add("-f"); command.add("hls");
        command.add("-hls_time"); command.add("6");
        command.add("-hls_list_size"); command.add("0");
        if (USE_FMP4_HLS) {
            command.add("-hls_flags"); command.add("append_list+omit_endlist+split_by_time");
            command.add("-hls_segment_type"); command.add("fmp4");
            command.add("-hls_fmp4_init_filename"); command.add(variant.name + "_init.mp4");
            command.add("-hls_segment_filename");
            command.add(variant.name + "_%04d.m4s");
        } else {
            command.add("-hls_flags"); command.add("append_list+omit_endlist+split_by_time");
            command.add("-hls_segment_filename");
            command.add(variant.name + "_%05d.ts");
        }
        command.add(variant.name + ".m3u8");
        
        String hlsEncoder = useHardware && !"libx264".equals(hwEncoder) ? hwEncoder : "libx264";
        LOG.info("[HLS] session={} variant={} encoder={} height={} hw={} audio={}",
            session.sessionId, variant.name, hlsEncoder, variant.height, useHardware,
            command.contains("copy") ? "copy" : "aac");

        LOG.info("Starting HLS encoder for session {} variant {}: {}", session.sessionId, variant.name, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(session.sessionDir.toFile());
        pb.redirectErrorStream(true);
        Process started = pb.start();
        if (gpuLease != null) {
            final GpuScheduler.GpuLease leaseToRelease = gpuLease;
            started.onExit().thenRun(() -> gpuScheduler.release(leaseToRelease));
        }
        return started;
    }

    private void startEncoderMonitor(HlsSession session, VariantConfig variant, Long profileId, Process process, boolean useHardware) {
        String vName = variant.name;
        Thread monitor = new Thread(() -> {
            Process currentProcess = process;
            boolean currentUseHardware = useHardware;

            while (true) {
                try {
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                            LOG.debug("[ffmpeg {}] {}", vName, line);

                            if (isRealTimeFatalError(line)) {
                                output.append("[Fatal HW error] ").append(line.trim()).append('\n');
                                LOG.warn("Fatal encoder error detected for {}: {}", vName, line.trim());
                                currentProcess.destroyForcibly();
                                break;
                            }
                        }
                    }

                    currentProcess.waitFor();
                    int exitCode = currentProcess.exitValue();

                    if (session.stopped) {
                        LOG.debug("HLS session {} stopped, not retrying encoder {}", session.sessionId, vName);
                        break;
                    }

                    if (exitCode == 0) {
                        LOG.info("HLS encoder {} for session {} finished normally, stream complete", vName, session.sessionId);
                        // Encoder completed normally — finalize playlist and do NOT retry
                        finalizePlaylist(session, vName);
                        break;
                    }

                    int attempt = session.getRestartCount(vName);
                    long backoffMs = Math.min(5000 * (1L << Math.min(attempt, 5)), 60000L); // 5s, 10s, 20s, 40s, 60s max

                    LOG.warn("HLS encoder {} died (exit {}, retry {}/∞), restarting in {}ms", vName, exitCode, attempt + 1, backoffMs);
                    session.removeProcess(vName);
                    session.lastRestartTimes.put(vName, System.currentTimeMillis());

                    long restartDeadline = System.currentTimeMillis() + backoffMs;
                    while (System.currentTimeMillis() < restartDeadline) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    // Fall back to software after the first hardware failure
                    if (currentUseHardware && isHardwareError(output.toString())) {
                        LOG.warn("HLS encoder {} failed with hardware error, switching to software", vName);
                        currentUseHardware = false;
                    }

                    // Clean old segments before restarting to prevent sequence overlap
                    cleanVariantFiles(session, vName);

                    try {
                        currentProcess = startVariantEncoderProcess(session, variant, profileId, currentUseHardware);
                        session.addProcess(vName, currentProcess);
                        session.incrementRestartCount(vName);
                        LOG.info("Restarted HLS encoder {} ({})", vName, currentUseHardware ? "HW" : "SW");

                        // Monitor loop will read the new process's output on next iteration
                    } catch (IOException e) {
                        LOG.error("Failed to restart HLS encoder {}: {}", vName, e.getMessage());
                        break;
                    }

                    // Continue loop to monitor the new process
                } catch (IOException e) {
                    LOG.warn("Error monitoring HLS encoder {}: {}", vName, e.getMessage());
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }

    private String extractEncoderFromProcess(Process process) {
        try {
            String info = process.info().commandLine().orElse("");
            if (info.contains("h264_nvenc")) return "h264_nvenc";
            if (info.contains("hevc_nvenc")) return "hevc_nvenc";
            if (info.contains("h264_qsv")) return "h264_qsv";
            if (info.contains("hevc_qsv")) return "hevc_qsv";
            if (info.contains("h264_amf")) return "h264_amf";
            if (info.contains("hevc_amf")) return "hevc_amf";
            if (info.contains("h264_vaapi")) return "h264_vaapi";
            if (info.contains("hevc_vaapi")) return "hevc_vaapi";
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isHardwareError(String output) {
        if (output == null || output.isEmpty()) return false;
        String lower = output.toLowerCase();
        return lower.contains("nvenc") || lower.contains("amf") || lower.contains("qsv") ||
               lower.contains("vaapi") || lower.contains("videotoolbox") || lower.contains("cuvid") ||
               lower.contains("cuda") || lower.contains("gpu") || lower.contains("driver") ||
               lower.contains("hardware acceleration failed");
    }

    private boolean isRealTimeFatalError(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase();
        return lower.contains("no capable devices found") ||
               lower.contains("failed (exit=-542398533)") ||
               lower.contains("mfxstatus") ||
               lower.contains("cannot open mfx") ||
               lower.contains("hwaccel failed") ||
               lower.contains("device failed");
    }

    private String readProcessOutput(Process process) {
        try (InputStream is = process.getInputStream()) {
            return new String(is.readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }

    private void createAudioStreams(HlsSession session) {
        String resolvedPath = resolveVideoPath(session.video.path);
        for (AudioTrack track : session.audioTracks) {
            try {
                String audioName = "audio_" + track.trackIndex;
                List<String> command = new ArrayList<>();
                command.add(ffmpegDiscoveryService.findFFmpegExecutable());
                // Audio encoder always starts from 0 (same as video — HLS seek is handled natively)
                command.add("-ss");
                command.add("0");
                command.add("-i");
                command.add(resolvedPath);
                command.add("-map");
                command.add("0:" + track.trackIndex);
                command.add("-c:a");
                if (isCopyableCodec(track.codec)) {
                    command.add("copy");
                } else {
                    command.add("aac");
                    command.add("-b:a");
                    command.add("128k");
                    command.add("-ac");
                    command.add("2");
                }
                command.add("-f");
                command.add("hls");
                command.add("-hls_time");
                command.add("4");
                command.add("-hls_list_size");
                command.add("0");
                command.add("-hls_flags");
                command.add("append_list+omit_endlist+split_by_time");
                command.add("-hls_segment_type");
                command.add("fmp4");
                command.add("-hls_fmp4_init_filename");
                command.add(audioName + "_init.mp4");
                command.add("-hls_segment_filename");
                command.add(audioName + "_%04d.m4s");
                command.add(audioName + ".m3u8");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(session.sessionDir.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                session.addProcess(audioName, process);
                session.audioPlaylistNames.add(audioName);
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            LOG.debug("[ffmpeg {}] {}", audioName, line);
                        }
                    } catch (IOException e) {
                        LOG.warn("Error reading ffmpeg output: {}", e.getMessage());
                    }
                }).start();
                new Thread(() -> {
                    try {
                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            LOG.warn("Audio encoder for track {} exited with code {}", track.trackIndex, exitCode);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "audio-monitor-" + audioName).start();
                LOG.info("Started audio stream {} for session {}", audioName, session.sessionId);
            } catch (Exception e) {
                LOG.error("Failed to start audio stream for track {}", track.trackIndex, e);
            }
        }
    }

    private void createSubtitleStreams(HlsSession session) {
        List<SubtitleTrack> servable = servableSubtitleTracks(session);
        if (servable.isEmpty()) {
            return;
        }
        String resolvedPath = resolveVideoPath(session.video.path);
        int n = 0;
        for (SubtitleTrack track : servable) {
            String subName = subtitlePlaylistName(n++);
            String inputPath = resolvedPath;
            String mapSpec = "0:" + track.trackIndex;
            if (track.trackIndex == null) {
                inputPath = track.fullPath;
                mapSpec = "0:0";
            }
            // PGS bitmap tracks cannot be converted to WebVTT by ffmpeg directly: resolve the
            // OCR'd WebVTT (PgsOcrService, cached per track) and segment that file instead.
            if (isPgsTrack(track)) {
                try {
                    String vtt = pgsOcrService.getOrCreateWebVTT(track);
                    Path vttSource = session.sessionDir.resolve("pgs_src_" + subName + ".vtt");
                    Files.writeString(vttSource, vtt);
                    inputPath = vttSource.toString();
                    mapSpec = "0:0";
                } catch (Exception e) {
                    LOG.error("PGS OCR failed for track {} in session {}: {}", track.trackIndex, session.sessionId, e.getMessage());
                    continue;
                }
            }
            try {
                List<String> command = new ArrayList<>();
                command.add(ffmpegDiscoveryService.findFFmpegExecutable());
                // Subtitle encoder always starts from 0 (same as video/audio — HLS seek is handled natively)
                command.add("-ss");
                command.add("0");
                command.add("-i");
                command.add(inputPath);
                command.add("-map");
                command.add(mapSpec);
                command.add("-c:s");
                command.add("webvtt");
                command.add("-f");
                command.add("segment");
                command.add("-segment_time");
                command.add("4");
                command.add("-segment_list_size");
                command.add("0");
                command.add("-segment_list_type");
                command.add("m3u8");
                command.add("-segment_format");
                command.add("webvtt");
                command.add("-segment_list");
                command.add(subName + ".m3u8");
                command.add(subName + "_%04d.vtt");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(session.sessionDir.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                session.addProcess(subName, process);
                final String subLabel = subName;
                final Integer subTrackIndex = track.trackIndex;
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            LOG.debug("[ffmpeg {}] {}", subLabel, line);
                        }
                    } catch (IOException e) {
                        LOG.warn("Error reading ffmpeg output: {}", e.getMessage());
                    }
                }).start();
                new Thread(() -> {
                    try {
                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            LOG.warn("Subtitle encoder for track {} exited with code {}", subTrackIndex, exitCode);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "subtitle-monitor-" + subName).start();
                LOG.info("Started subtitle stream {} for session {}", subName, session.sessionId);
            } catch (Exception e) {
                LOG.error("Failed to start subtitle stream for session {}", session.sessionId, e);
            }
        }
    }

    private List<SubtitleTrack> servableSubtitleTracks(HlsSession session) {
        List<SubtitleTrack> result = new ArrayList<>();
        if (session.subtitleTracks == null) {
            return result;
        }
        for (SubtitleTrack track : session.subtitleTracks) {
            if (track == null) {
                continue;
            }
            boolean hasSource = track.trackIndex != null || (track.fullPath != null && !track.fullPath.isBlank());
            if (!hasSource) {
                LOG.warn("Skipping subtitle track with no stream index or file for session {}", session.sessionId);
                continue;
            }
            // PGS bitmap tracks are servable: PgsOcrService converts them to WebVTT and
            // createSubtitleStreams segments that VTT. Nothing is burned into the video.
            if (isPgsTrack(track)) {
                result.add(track);
                continue;
            }
            // External sidecar / AI-generated / NLLB tracks carry a text format but no codec
            // (ffprobe only reports codecs for embedded streams); embedded text tracks carry a
            // codec. Accept either so every text track is exposed to IPTV clients.
            if (!isTextSubtitleCodec(track.codec) && !isTextSubtitleFormat(track.format)) {
                LOG.warn("Skipping non-text subtitle track {} (codec={}, format={}) for session {}: bitmap subs cannot convert to WebVTT",
                        track.trackIndex, track.codec, track.format, session.sessionId);
                continue;
            }
            result.add(track);
        }
        return result;
    }

    private static String subtitlePlaylistName(int index) {
        return "sub_" + index;
    }

    private boolean isTextSubtitleCodec(String codec) {
        if (codec == null) return false;
        String c = codec.toLowerCase(java.util.Locale.ROOT);
        return c.contains("subrip") || c.contains("srt") || c.contains("ass") || c.contains("ssa")
                || c.contains("mov_text") || c.contains("webvtt") || c.equals("vtt") || c.contains("ttml");
    }

    private boolean isTextSubtitleFormat(String format) {
        if (format == null) return false;
        String f = format.toLowerCase(java.util.Locale.ROOT);
        return f.equals("srt") || f.equals("vtt") || f.equals("ass") || f.equals("ssa") || f.equals("subrip");
    }

    private boolean isPgsTrack(SubtitleTrack track) {
        return PgsOcrService.isPgsCodec(track.codec) || "pgs".equals(track.format);
    }

    private static String normalizeLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return "und";
        }
        String code = languageCode.trim().toLowerCase(java.util.Locale.ROOT);
        int dash = code.indexOf('-');
        if (dash > 0) {
            code = code.substring(0, dash);
        }
        if (code.length() == 2) {
            return code;
        }
        if (code.length() == 3) {
            String mapped = ISO_639_2_TO_1.get(code);
            return mapped != null ? mapped : "und";
        }
        return "und";
    }

    private boolean isCopyableCodec(String codec) {
        if (codec == null) return false;
        String lower = codec.toLowerCase();
        return lower.contains("aac") || lower.contains("mp3")
            || lower.contains("ac3") || lower.contains("eac3") || lower.contains("ec-3")
            || lower.contains("dts") || lower.contains("dca");
    }

    private boolean isEligibleForCopyMode(HlsSession session, VariantConfig variant) {
        // Video codec must be H.264 or HEVC for copy-mode eligibility
        String codec = session.video.videoCodec;
        if (codec == null) return false;
        String lower = codec.toLowerCase();
        boolean isH264 = lower.contains("h264") || lower.contains("avc");
        boolean isHevc = lower.contains("hevc") || lower.contains("h265");
        if (!isH264 && !isHevc) return false;

        // All audio tracks must use copyable codecs
        if (session.audioTracks != null) {
            for (AudioTrack track : session.audioTracks) {
                if (!isCopyableCodec(track.codec)) return false;
            }
        }

        // No downscale needed — variant height must be >= source height
        if (variant.height > 0 && session.video.resolution != null && session.video.resolution.contains("x")) {
            try {
                int sourceH = Integer.parseInt(session.video.resolution.split("x")[1]);
                if (variant.height < sourceH) return false;
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Builds the scale filter string based on decoder and encoder vendor matching.
     * Uses vendor-matched zero-copy pipelines where possible, falls back to CPU.
     */
    private String buildScaleFilter(String hardwareDecoder, String videoEncoder, int qualityHeight, String resolution) {
        if (qualityHeight <= 0) return null;
        
        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}
        double aspect = (double) w / h;
        int targetH = qualityHeight;
        int targetW = (int) Math.round(targetH * aspect);
        if (targetW % 2 != 0) targetW--;
        if (targetH % 2 != 0) targetH--;

        boolean decoderIsCuda = hardwareDecoder != null && hardwareDecoder.contains("cuvid");
        boolean decoderIsQsv = hardwareDecoder != null && hardwareDecoder.contains("qsv");
        boolean decoderIsVaapi = hardwareDecoder != null && hardwareDecoder.contains("vaapi");
        boolean decoderIsAmf = hardwareDecoder != null && hardwareDecoder.contains("amf");
        boolean decoderIsVideoToolbox = hardwareDecoder != null && hardwareDecoder.contains("videotoolbox");

        boolean encoderIsNvenc = videoEncoder != null && videoEncoder.contains("nvenc");
        boolean encoderIsQsv = videoEncoder != null && videoEncoder.contains("qsv");
        boolean encoderIsVaapi = videoEncoder != null && videoEncoder.contains("vaapi");
        boolean encoderIsAmf = videoEncoder != null && videoEncoder.contains("amf");
        boolean encoderIsVideoToolbox = videoEncoder != null && videoEncoder.contains("videotoolbox");

        // Vendor-matched zero-copy pipelines
        if (decoderIsCuda && encoderIsNvenc) {
            return "scale_cuda=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH + ":format=nv12";
        } else if (decoderIsVideoToolbox && encoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Cross-vendor fallback: download from GPU to CPU, then software scale
        if (decoderIsCuda) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsQsv) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsVaapi) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsAmf) {
            return "scale=" + targetW + ":" + targetH;
        } else if (decoderIsVideoToolbox) {
            return "scale=" + targetW + ":" + targetH;
        }

        // Software decode path
        return "scale=" + targetW + ":" + targetH;
    }

    private String buildFormatFilter(String hardwareDecoder, String videoEncoder, String resolution) {
        if (hardwareDecoder == null || videoEncoder == null) return null;

        int w = 1920, h = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            }
        } catch (Exception ignored) {}

        boolean decoderIsCuda = hardwareDecoder.contains("cuvid");
        boolean encoderIsNvenc = videoEncoder.contains("nvenc");
        boolean decoderIsQsv = hardwareDecoder.contains("qsv");
        boolean encoderIsQsv = videoEncoder.contains("qsv");
        boolean decoderIsVaapi = hardwareDecoder.contains("vaapi");
        boolean encoderIsVaapi = videoEncoder.contains("vaapi");
        boolean decoderIsAmf = hardwareDecoder.contains("amf");
        boolean encoderIsAmf = videoEncoder.contains("amf");

        if (decoderIsCuda && encoderIsNvenc)
            return "scale_cuda=" + w + ":" + h + ":format=nv12";
        if (decoderIsQsv && encoderIsQsv)
            return "scale_qsv=" + w + ":" + h + ":format=nv12";
        if (decoderIsVaapi && encoderIsVaapi)
            return "scale_vaapi=" + w + ":" + h + ":format=nv12";
        if (decoderIsAmf && encoderIsAmf)
            return "scale_amf=" + w + ":" + h + ":format=nv12";

        return null;
    }

    private String deriveCodecsString(Video video) {
        String videoCodecStr = "avc1.64001f";
        String audioCodecStr = "mp4a.40.2";

        if (video != null && video.videoCodec != null) {
            String vc = video.videoCodec.toLowerCase();
            if (vc.contains("hevc") || vc.contains("h265")) {
                String level = deriveHvcLevel(video.resolution);
                videoCodecStr = "hvc1.1.4.L" + level + ".B0";
            } else if (vc.contains("h264") || vc.contains("avc")) {
                videoCodecStr = deriveAvc1Codec(video);
            }
        }

        if (video != null && video.audioCodec != null) {
            String ac = video.audioCodec.toLowerCase();
            if (ac.contains("mp3")) {
                audioCodecStr = "mp4a.40.34";
            }
        }

        return videoCodecStr + "," + audioCodecStr;
    }

    private String deriveAvc1Codec(Video video) {
        String profile = "6400";
        if (video.videoProfile != null) {
            String vp = video.videoProfile.toLowerCase();
            if (vp.contains("baseline")) {
                profile = "4200";
            } else if (vp.contains("main")) {
                profile = "4D00";
            }
        }

        String level = "1F";
        if (video.resolution != null) {
            try {
                String[] parts = video.resolution.split("x");
                int height = Integer.parseInt(parts[1]);
                if (height > 2160) level = "34";
                else if (height > 1080) level = "32";
                else if (height > 720) level = "28";
                else if (height > 480) level = "1F";
                else if (height > 360) level = "1C";
                else level = "0F";
            } catch (Exception ignored) {}
        }

        return "avc1." + profile + level;
    }

    private String deriveHvcLevel(String resolution) {
        if (resolution != null) {
            try {
                String[] parts = resolution.split("x");
                int height = Integer.parseInt(parts[1]);
                if (height > 2160) return "180";
                else if (height > 1080) return "150";
                else if (height > 720) return "123";
                else return "93";
            } catch (Exception ignored) {}
        }
        return "93";
    }

    private String deriveResolutionString(Video video) {
        if (video != null && video.resolution != null && !video.resolution.isBlank()) {
            return video.resolution;
        }
        return "1280x720";
    }

    private String resolveVideoPath(String videoPath) {
        java.nio.file.Path vPath = java.nio.file.Paths.get(videoPath);
        if (vPath.isAbsolute()) {
            return vPath.toString();
        }
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isEmpty()) {
                return java.nio.file.Paths.get(libraryPath, videoPath).toString();
            }
        } catch (Exception e) {
            LOG.warn("Could not resolve video library path for {}: {}", videoPath, e.getMessage());
        }
        return videoPath;
    }

    public String getMasterPlaylist(String sessionId) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        session.markAccessed();
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:").append(USE_FMP4_HLS ? 7 : 3).append("\n");
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        String codecs = deriveCodecsString(session.video);
        int fps = session.video.frameRate != null && session.video.frameRate > 0 ? session.video.frameRate : 30;
        List<SubtitleTrack> servableSubs = servableSubtitleTracks(session);
        String subsAttr = servableSubs.isEmpty() ? "" : ",SUBTITLES=\"subs\"";
        
        if (session.audioTracks.size() > 1) {
            Integer preferredIndex = session.getPreferredAudioTrackIndex();
            for (int i = 0; i < session.audioTracks.size(); i++) {
                AudioTrack track = session.audioTracks.get(i);
                String audioName = "audio_" + track.trackIndex;
                boolean preferred = preferredIndex != null && preferredIndex >= 0
                        && preferredIndex.equals(track.trackIndex);
                boolean isDefault = preferred || track.isDefault;
                if (preferred) {
                    LOG.info("Marking preferred audio track {} (index {}) as DEFAULT in master playlist for session {}", track.displayName, track.trackIndex, session.sessionId);
                }
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"" + track.displayName + "\",LANGUAGE=\"" + (track.languageCode != null ? track.languageCode : "und") + "\",AUTOSELECT=" + (isDefault ? "YES" : "NO") + ",DEFAULT=" + (isDefault ? "YES" : "NO") + ",URI=\"/api/hls/playlist/" + session.sessionId + "/" + audioName + ".m3u8\"\n");
            }
        }

        if (!servableSubs.isEmpty()) {
            int subIndex = 0;
            for (SubtitleTrack track : servableSubs) {
                String subName = subtitlePlaylistName(subIndex++);
                String lang = normalizeLanguageCode(track.languageCode);
                String name = (track.displayName != null && !track.displayName.isBlank()) ? track.displayName
                        : ((track.languageName != null && !track.languageName.isBlank()) ? track.languageName : "Subtitle " + subIndex);
                name = name.replace("\"", "'");
                sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"" + name + "\",LANGUAGE=\"" + lang
                        + "\",AUTOSELECT=" + (track.isDefault ? "YES" : "NO")
                        + ",DEFAULT=" + (track.isDefault ? "YES" : "NO")
                        + ",FORCED=" + (track.isForced ? "YES" : "NO")
                        + ",URI=\"/api/hls/playlist/" + session.sessionId + "/" + subName + ".m3u8\"\n");
            }
        }
        
        List<VariantConfig> variants = session.variants;
        if (variants == null || variants.isEmpty()) {
            String resolution = deriveResolutionString(session.video);
            if (session.audioTracks.size() > 1) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=" + resolution + ",CODECS=\"" + codecs + "\",AUDIO=\"audio\"" + subsAttr + ",FRAME-RATE=" + fps + ".0\n");
            } else {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=" + resolution + ",CODECS=\"" + codecs + "\"" + subsAttr + ",FRAME-RATE=" + fps + ".0\n");
            }
            sb.append("/api/hls/playlist/" + session.sessionId + "/" + VIDEO_VARIANT + ".m3u8\n");
        } else {
            int w = 1920, h = 1080;
            try {
                if (session.video.resolution != null && session.video.resolution.contains("x")) {
                    String[] p = session.video.resolution.split("x");
                    w = Integer.parseInt(p[0]);
                    h = Integer.parseInt(p[1]);
                }
            } catch (Exception ignored) {}
            double aspect = (double) w / h;
            
            for (VariantConfig v : variants) {
                int vW = (int) Math.round(v.height * aspect);
                if (vW % 2 != 0) vW--;
                int vH = v.height;
                if (vH % 2 != 0) vH--;
                String audioAttr = (session.audioTracks.size() > 1) ? ",AUDIO=\"audio\"" : "";
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=" + v.bandwidth + ",RESOLUTION=" + vW + "x" + vH + ",CODECS=\"" + codecs + "\"" + audioAttr + subsAttr + ",FRAME-RATE=" + fps + ".0\n");
                sb.append("/api/hls/playlist/" + session.sessionId + "/" + v.name + ".m3u8\n");
            }
        }
        return sb.toString();
    }

    public String getMediaPlaylist(String sessionId, String variantName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        session.markAccessed();
        Path playlistFile = session.sessionDir.resolve(variantName + ".m3u8");
        if (!Files.exists(playlistFile)) {
            return buildPartialPlaylist(session, variantName, Collections.emptySet());
        }
        try {
            String content = Files.readString(playlistFile);
            content = rewriteRelativeSegmentPaths(content, session.sessionId, variantName);
            if (!content.contains("#EXT-X-ENDLIST")) {
                Set<String> alreadyListed = parseSegmentNames(content);
                String partial = buildPartialPlaylist(session, variantName, alreadyListed);
                if (!partial.isEmpty()) {
                    return content + partial;
                }
                return content;
            }
            return content;
        } catch (IOException e) {
            LOG.warn("Error reading playlist {}: {}", playlistFile, e.getMessage());
            return buildPartialPlaylist(session, variantName, Collections.emptySet());
        }
    }

    private Set<String> parseSegmentNames(String playlist) {
        Set<String> names = new HashSet<>();
        for (String line : playlist.split("\n")) {
            String trimmed = line.trim();
            if ((trimmed.endsWith(".ts") || trimmed.endsWith(".m4s") || trimmed.endsWith(".vtt")) && !trimmed.startsWith("#")) {
                String name = trimmed.contains("/") ?
                    trimmed.substring(trimmed.lastIndexOf('/') + 1) : trimmed;
                names.add(name);
            }
        }
        return names;
    }

    private String rewriteRelativeSegmentPaths(String playlist, String sessionId, String variantName) {
        String prefix = "/api/hls/media/" + sessionId + "/" + variantName + "/";
        String[] lines = playlist.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ((trimmed.endsWith(".ts") || trimmed.endsWith(".m4s") || trimmed.endsWith(".vtt")) && !trimmed.startsWith("/") && !trimmed.startsWith("#")) {
                sb.append(prefix).append(trimmed);
                } else if (trimmed.startsWith("#EXT-X-MAP:URI=\"")) {
                    int idx = trimmed.indexOf("\"", 16);
                    if (idx != -1) {
                        String uri = trimmed.substring(16, idx);
                        int lastSlash = Math.max(uri.lastIndexOf('\\'), uri.lastIndexOf('/'));
                        String filename = uri.substring(lastSlash + 1);
                        sb.append("#EXT-X-MAP:URI=\"").append(prefix).append(filename).append("\"");
                    } else {
                        sb.append(lines[i]);
                    }
            } else {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String buildPartialPlaylist(HlsSession session, String variantName, Set<String> alreadyListed) {
        StringBuilder sb = new StringBuilder();

        String segmentExt = variantName.startsWith("sub_") ? ".vtt" : (USE_FMP4_HLS ? ".m4s" : ".ts");
        File[] segments = session.sessionDir.toFile().listFiles(
            (dir, name) -> name.startsWith(variantName + "_") && name.endsWith(segmentExt)
        );

        if (segments == null || segments.length == 0) {
            LOG.debug("No segments found for {} in {}", variantName, session.sessionDir);
            if (alreadyListed.isEmpty()) {
                sb.append("#EXTM3U\n");
                sb.append("#EXT-X-VERSION:").append(USE_FMP4_HLS ? 7 : 3).append("\n");
                sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
                sb.append("#EXT-X-TARGETDURATION:6\n");
                sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
            }
            return sb.toString();
        }

        Arrays.sort(segments, Comparator.comparing(File::getName));

        // Parse EXTINF durations from FFmpeg playlist if it exists
        Map<String, Double> segmentDurations = parseSegmentDurations(session, variantName);
        double targetDuration = 6.0;
        if (!segmentDurations.isEmpty()) {
            targetDuration = segmentDurations.values().stream().max(Double::compare).orElse(6.0);
        }

        boolean standalone = alreadyListed.isEmpty();
        if (standalone) {
            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:").append(USE_FMP4_HLS ? 7 : 3).append("\n");
            sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
            sb.append("#EXT-X-TARGETDURATION:").append((int)Math.ceil(targetDuration)).append("\n");
            if (USE_FMP4_HLS && !variantName.startsWith("sub_")) {
                sb.append("#EXT-X-MAP:URI=\"/api/hls/media/" + session.sessionId + "/" + variantName + "/init.mp4\"\n");
            }
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(parseSegmentNumber(segments[0].getName())).append("\n");
            if (session.lastRestartTimes.containsKey(variantName) && 
                (System.currentTimeMillis() - session.lastRestartTimes.get(variantName)) < 120000) {
                sb.append("#EXT-X-DISCONTINUITY\n");
            }
        }

        for (File seg : segments) {
            String segName = seg.getName();
            if (alreadyListed.contains(segName)) continue;
            double duration = segmentDurations.getOrDefault(segName, targetDuration);
            sb.append(String.format("#EXTINF:%.3f,\n", duration));
            sb.append("/api/hls/media/" + session.sessionId + "/" + variantName + "/" + segName).append("\n");
        }

        return sb.toString();
    }

    private Map<String, Double> parseSegmentDurations(HlsSession session, String variantName) {
        Map<String, Double> durations = new HashMap<>();
        Path playlistFile = session.sessionDir.resolve(variantName + ".m3u8");
        if (!Files.exists(playlistFile)) {
            return durations;
        }
        try {
            List<String> lines = Files.readAllLines(playlistFile);
            String lastSegmentName = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("#EXTINF:")) {
                    try {
                        String durationStr = line.substring(8, line.indexOf(',')).trim();
                        double duration = Double.parseDouble(durationStr);
                        // Next non-empty, non-comment line should be the segment
                        for (int j = i + 1; j < lines.size(); j++) {
                            String segLine = lines.get(j).trim();
                            if (!segLine.isEmpty() && !segLine.startsWith("#")) {
                                String segName = segLine.contains("/") ? 
                                    segLine.substring(segLine.lastIndexOf('/') + 1) : segLine;
                                durations.put(segName, duration);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to parse segment durations from {}: {}", playlistFile, e.getMessage());
        }
        return durations;
    }

    private int parseSegmentNumber(String filename) {
        try {
            String numPart = filename.replaceAll(".*_(\\d+)\\.(ts|m4s)", "$1");
            return Integer.parseInt(numPart);
        } catch (Exception e) {
            return 0;
        }
    }

    public File getSegment(String sessionId, String variantName, String segmentName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        session.markAccessed();
        File segment = session.sessionDir.resolve(segmentName).toFile();
        return segment.exists() ? segment : null;
    }

    public Path getSegmentPath(String sessionId, String variantName, String segmentName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        session.markAccessed();
        return session.sessionDir.resolve(segmentName);
    }

    public Path getInitSegmentPath(String sessionId, String variantName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        session.markAccessed();
        return session.sessionDir.resolve(variantName + "_init.mp4");
    }

    private synchronized Path getHlsBasePath() {
        if (hlsBasePath == null) {
            try {
                String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (libraryPath != null && !libraryPath.isEmpty()) {
                    hlsBasePath = java.nio.file.Paths.get(libraryPath, "hls").toAbsolutePath();
                } else {
                    hlsBasePath = Path.of(System.getProperty("user.dir")).resolve("sessions").resolve("hls").toAbsolutePath();
                }
                Files.createDirectories(hlsBasePath);
            } catch (IOException e) {
                hlsBasePath = Path.of(System.getProperty("java.io.tmpdir"), "jmedia-hls").toAbsolutePath();
            }
        }
        return hlsBasePath;
    }

    public void destroySession(String sessionId) {
        HlsSession session = activeSessions.remove(sessionId);
        if (session != null) {
            session.stop();
            endXtreamLinkedSession(session, "disconnected");
            try {
                if (session.sessionDir != null) {
                    deleteDirectory(session.sessionDir);
                }
            } catch (Exception e) {
                LOG.warn("Failed to delete HLS session directory {}: {}", session.sessionDir, e.getMessage());
            }
            LOG.info("Destroyed HLS session {}", sessionId);
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            LOG.warn("Failed to delete directory {}: {}", dir, e.getMessage());
        }
    }

    private static final long SESSION_IDLE_TTL_MS = 60 * 1000L; // 60s — stop segment generation ~1 min after the player closes
    private final ScheduledExecutorService hwRetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hls-hw-retry");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService sessionCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hls-session-cleanup");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        sessionCleanupExecutor.scheduleAtFixedRate(this::cleanupAbandonedSessions, 20, 20, TimeUnit.SECONDS);
        LOG.info("HlsService initialized with periodic session cleanup (TTL: {} min)", SESSION_IDLE_TTL_MS / 60000);
    }

    @PreDestroy
    public void shutdown() {
        hwRetryExecutor.shutdownNow();
        sessionCleanupExecutor.shutdownNow();
        activeSessions.values().forEach(s -> {
            endXtreamLinkedSession(s, "disconnected");
            s.stop();
        });
        activeSessions.clear();
        LOG.info("HlsService shutdown complete");
    }

    private void cleanupAbandonedSessions() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, HlsSession> entry : activeSessions.entrySet()) {
            HlsSession session = entry.getValue();
            boolean shouldRemove = false;
            
            // Add grace period: don't clean up if restarted less than 2 minutes ago
            // Check this BEFORE allProcessesDead to prevent cleanup during restart window
            boolean recentlyRestarted = session.lastRestartTimes.values().stream().anyMatch(t -> (now - t) < 120000L);
            
            if (recentlyRestarted) {
                LOG.debug("HLS session {} recently restarted, skipping cleanup for now.", session.sessionId);
            } else if (session.processes.values().stream().noneMatch(Process::isAlive)) {
                LOG.debug("HLS session {} has no alive processes, marking for cleanup", session.sessionId);
                shouldRemove = true;
            } else if ((now - session.lastAccessed) > SESSION_IDLE_TTL_MS) {
                LOG.info("HLS session {} idle for {} minutes, marking for cleanup", 
                    session.sessionId, (now - session.lastAccessed) / 60000);
                shouldRemove = true;
            }
            
            if (shouldRemove) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (String sessionId : toRemove) {
            HlsSession session = activeSessions.get(sessionId);
            endXtreamLinkedSession(session, "timeout");
            destroySession(sessionId);
        }
        
        if (!toRemove.isEmpty()) {
            LOG.info("Cleaned up {} abandoned HLS sessions", toRemove.size());
        }
    }

    public static class HlsSession {
        public final String sessionId;
        public final Video video;
        public final List<AudioTrack> audioTracks;
        public final List<SubtitleTrack> subtitleTracks;
        public final Path sessionDir;
        public final double startSeconds;
        public final long createdAt;
        public final List<String> audioPlaylistNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile long lastAccessed;
        private final Map<String, Process> processes = new ConcurrentHashMap<>();
        private final Map<String, Integer> restartAttempts = new ConcurrentHashMap<>();
        private final Map<String, Long> lastRestartTimes = new ConcurrentHashMap<>();
        private final Map<String, Integer> lastMediaSequences = new ConcurrentHashMap<>();
        private Integer preferredAudioTrackIndex = null;

        public volatile boolean stopped = false;

        public volatile String deviceToken;

        public Long profileId;

        public int qualityHeight = 0;
        public List<VariantConfig> variants = null;

        public HlsSession(String id, Video v, List<AudioTrack> tracks, List<SubtitleTrack> subs, Path d, double s) {
            sessionId = id;
            video = v;
            audioTracks = tracks;
            subtitleTracks = subs;
            sessionDir = d;
            startSeconds = s;
            createdAt = System.currentTimeMillis();
            lastAccessed = System.currentTimeMillis();
        }

        public void markAccessed() {
            lastAccessed = System.currentTimeMillis();
        }

        public void addProcess(String variantName, Process process) {
            processes.put(variantName, process);
        }

        public void removeProcess(String variantName) {
            Process p = processes.remove(variantName);
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception e) {}
            }
        }

        public void stop() {
            stopped = true;
            processes.values().forEach(p -> {
                try { p.destroyForcibly(); } catch (Exception e) {}
            });
            processes.clear();
        }

        public void setPreferredAudioTrackIndex(Integer trackIndex) {
            this.preferredAudioTrackIndex = trackIndex;
        }

        public Integer getPreferredAudioTrackIndex() {
            return preferredAudioTrackIndex;
        }

        public int getRestartCount(String variantName) {
            return restartAttempts.getOrDefault(variantName, 0);
        }

        public void incrementRestartCount(String variantName) {
            restartAttempts.merge(variantName, 1, Integer::sum);
        }
    }

    private void cleanupSessionDirectory(Path sessionDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "*.{ts,m4s,m3u8,mp4,vtt}")) {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            LOG.warn("Failed to clean old segment files for session {}: {}", sessionDir.getFileName(), e.getMessage());
        }
    }

    public static class SessionInfo {
        public final String sessionId;
        public final String playlistUrl;
        public SessionInfo(String id, String url) {
            sessionId = id;
            playlistUrl = url;
        }
    }
}
