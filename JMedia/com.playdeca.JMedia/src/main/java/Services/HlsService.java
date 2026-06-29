package Services;

import Models.AudioTrack;
import Models.Video;
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
        String sessionId = "vid-" + videoId + "-" + safeDeviceToken;

        // Destroy existing session before creating a new one to kill orphaned FFmpeg processes
        HlsSession existing = activeSessions.get(sessionId);
        if (existing != null) {
            LOG.info("Destroying existing HLS session {} for re-creation", sessionId);
            existing.stop();
            activeSessions.remove(sessionId);
        }

        Video video = videoService.findById(videoId);
        if (video == null) throw new IOException("Video not found: " + videoId);
        Path sessionDir = getHlsBasePath().resolve(sessionId).toAbsolutePath();
        Files.createDirectories(sessionDir);
        cleanupSessionDirectory(sessionDir);
        List<AudioTrack> audioTracks = video.audioTracks != null ? new ArrayList<>(video.audioTracks) : new ArrayList<>();
        HlsSession session = new HlsSession(sessionId, video, audioTracks, sessionDir, startSeconds);
        
        if (qualityHeight != null && qualityHeight > 0) {
            session.qualityHeight = qualityHeight;
            LOG.info("HLS session created with quality height: {}p", qualityHeight);
        }
        
        // Set preferred audio track if specified
        if (preferredAudioTrackIndex != null && preferredAudioTrackIndex >= 0) {
            session.setPreferredAudioTrackIndex(preferredAudioTrackIndex);
            LOG.info("HLS session created with preferred audio track index: {}", preferredAudioTrackIndex);
        }
        
        activeSessions.put(sessionId, session);
        int qh = (qualityHeight != null && qualityHeight > 0) ? qualityHeight : 0;
        List<VariantConfig> variants = determineVariants(video, qh);
        session.variants = variants;
        for (VariantConfig variant : variants) {
            startVariantEncoder(session, variant, profileId);
        }
        return session;
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
            LOG.warn("HLS encoder for session {} variant {} exited unexpectedly with code 0", session.sessionId, variant.name);
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
                LOG.warn("HW encoder for {} exited early (code {}), will retry in 10s", vName, exitCode);
                session.removeProcess(vName);
                session.lastRestartTimes.put(vName, System.currentTimeMillis());
                scheduleHwRetry(session, variant, profileId);
                return;
            }

            session.addProcess(vName, process);
            startHwMonitor(session, variant, profileId, process);
        } catch (IOException e) {
            LOG.error("Failed to start HW encoder for {}: {}", vName, e.getMessage());
            scheduleHwRetry(session, variant, profileId);
        }
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
            LOG.warn("HW encoder {} exited (code {}), scheduling retry in 10s", vName, exitCode);
            session.removeProcess(vName);
            session.lastRestartTimes.put(vName, System.currentTimeMillis());
            scheduleHwRetry(session, variant, profileId);
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
                copyCommand.add("-map"); copyCommand.add("0:a:" + session.audioTracks.get(0).trackIndex);
            } else {
                copyCommand.add("-map"); copyCommand.add("0:a?");
            }
            copyCommand.add("-c:a"); copyCommand.add("copy");
            copyCommand.add("-f"); copyCommand.add("hls");
            copyCommand.add("-hls_time"); copyCommand.add("6");
            copyCommand.add("-hls_list_size"); copyCommand.add("0");
            if (USE_FMP4_HLS) {
                copyCommand.add("-hls_flags"); copyCommand.add("append_list+omit_endlist+split_by_time");
                copyCommand.add("-hls_segment_type"); copyCommand.add("fmp4");
                copyCommand.add("-hls_fmp4_init_filename"); copyCommand.add(variant.name + "_init.mp4");
                copyCommand.add("-hls_segment_filename");
                copyCommand.add(variant.name + "_%04d.m4s");
            } else {
                copyCommand.add("-hls_flags"); copyCommand.add("append_list+omit_endlist+split_by_time");
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
            return pb.start();
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // HW decoding (must be placed before -i)
        String hwDecoder = null;
        if (useHardware) {
            hwDecoder = ffmpegDiscoveryService.getHardwareDecoder(session.video.videoCodec);
            if (hwDecoder != null) {
                LOG.info("Using hardware-accelerated decoding: {} for codec: {}", hwDecoder, session.video.videoCodec);
                if (hwDecoder.contains("cuvid")) {
                    command.add("-hwaccel"); command.add("cuda");
                    if (hwEncoder.contains("nvenc")) {
                        command.add("-hwaccel_output_format"); command.add("cuda");
                    }
                    String index = ffmpegDiscoveryService.getBestNvidiaDeviceIndex();
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
                    String device = ffmpegDiscoveryService.getBestQsvDevicePath();
                    if (device != null) {
                        command.add("-qsv_device"); command.add(device);
                    }
                } else if (hwDecoder.contains("vaapi")) {
                    command.add("-hwaccel"); command.add("vaapi");
                    if (hwEncoder.contains("vaapi")) {
                        command.add("-hwaccel_output_format"); command.add("vaapi");
                    }
                    String device = ffmpegDiscoveryService.getBestVaaPiDevicePath();
                    if (device != null) {
                        command.add("-hwaccel_device"); command.add(device);
                    }
                } else if (hwDecoder.contains("amf")) {
                    command.add("-hwaccel"); command.add("amf");
                    if (hwEncoder.contains("amf")) {
                        command.add("-hwaccel_output_format"); command.add("amf");
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
            command.add("0:a:" + track.trackIndex);
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
        return pb.start();
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
                        LOG.debug("HLS encoder {} exited normally (code 0)", vName);
                        // Encoder completed normally — no retry needed
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
                    if (currentProcess != null && currentProcess.isAlive()) {
                        // Check for initial segment growth
                        File[] segments = session.sessionDir.toFile().listFiles(
                            (dir, name) -> name.startsWith(vName + "_") && (name.endsWith(".ts") || name.endsWith(".m4s"))
                        );
                        if (segments != null && segments.length > 0) {
                            // Segment found - check for restart and discontinuity
                            Integer currentSequence = parseSegmentNumber(segments[segments.length - 1].getName());
                            Long lastRestartTime = session.lastRestartTimes.getOrDefault(vName, 0L);
                            Integer lastSequence = session.lastMediaSequences.getOrDefault(vName, 0);

                            if (currentSequence > 0 && currentSequence != lastSequence + 1) {
                                // Sequence reset detected
                                LOG.warn("Sequence reset detected for {}: last={}, current={}. Adding DISCONTINUITY.", vName, lastSequence, currentSequence);
                                // This information isn't directly used in buildPartialPlaylist yet, needs integration
                            }
                            
                            session.lastMediaSequences.put(vName, currentSequence);
                            session.lastRestartTimes.put(vName, System.currentTimeMillis()); // Reset time on segment found
                            break; // Encoder is alive and producing segments
                        }
                    }
                    }

                    // Fall back to software after the first hardware failure
                    if (currentUseHardware && isHardwareError(output.toString())) {
                        LOG.warn("HLS encoder {} failed with hardware error, switching to software", vName);
                        currentUseHardware = false;
                    }

                    try {
                        currentProcess = startVariantEncoderProcess(session, variant, profileId, currentUseHardware);
                        session.addProcess(vName, currentProcess);
                        session.incrementRestartCount(vName);
                        LOG.info("Restarted HLS encoder {} ({})", vName, currentUseHardware ? "HW" : "SW");

                        // Read output from the new process
                        Process newProcess = currentProcess;
                        new Thread(() -> {
                            try (BufferedReader r = new BufferedReader(new InputStreamReader(newProcess.getInputStream()))) {
                                String l;
                                while ((l = r.readLine()) != null) {
                                    LOG.debug("[ffmpeg {}] {}", vName, l);
                                }
                            } catch (IOException e) {
                                LOG.warn("Error reading ffmpeg output: {}", e.getMessage());
                            }
                        }).start();
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
                command.add("0:a:" + track.trackIndex);
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
                LOG.info("Started audio stream {} for session {}", audioName, session.sessionId);
            } catch (Exception e) {
                LOG.error("Failed to start audio stream for track {}", track.trackIndex, e);
            }
        }
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
            return "scale_cuda=" + targetW + ":" + targetH;
        } else if (decoderIsQsv && encoderIsQsv) {
            return "scale_qsv=" + targetW + ":" + targetH;
        } else if (decoderIsVaapi && encoderIsVaapi) {
            return "scale_vaapi=" + targetW + ":" + targetH;
        } else if (decoderIsAmf && encoderIsAmf) {
            return "scale_amf=" + targetW + ":" + targetH;
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
        
        if (session.audioTracks.size() > 1) {
            for (int i = 0; i < session.audioTracks.size(); i++) {
                AudioTrack track = session.audioTracks.get(i);
                String audioName = "audio_" + track.trackIndex;
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"" + track.displayName + "\",LANGUAGE=\"" + (track.languageCode != null ? track.languageCode : "und") + "\",AUTOSELECT=" + (track.isDefault ? "YES" : "NO") + ",DEFAULT=" + (track.isDefault ? "YES" : "NO") + ",URI=\"/api/hls/playlist/" + session.sessionId + "/" + audioName + ".m3u8\"\n");
            }
        }
        
        List<VariantConfig> variants = session.variants;
        if (variants == null || variants.isEmpty()) {
            String resolution = deriveResolutionString(session.video);
            if (session.audioTracks.size() > 1) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=" + resolution + ",CODECS=\"" + codecs + "\",AUDIO=\"audio\",FRAME-RATE=" + fps + ".0\n");
            } else {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=" + resolution + ",CODECS=\"" + codecs + "\",FRAME-RATE=" + fps + ".0\n");
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
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=" + v.bandwidth + ",RESOLUTION=" + vW + "x" + vH + ",CODECS=\"" + codecs + "\"" + audioAttr + ",FRAME-RATE=" + fps + ".0\n");
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
            if ((trimmed.endsWith(".ts") || trimmed.endsWith(".m4s")) && !trimmed.startsWith("#")) {
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
            if ((trimmed.endsWith(".ts") || trimmed.endsWith(".m4s")) && !trimmed.startsWith("/") && !trimmed.startsWith("#")) {
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

        String segmentExt = USE_FMP4_HLS ? ".m4s" : ".ts";
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
            if (USE_FMP4_HLS) {
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
        try {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            LOG.warn("Failed to delete directory {}: {}", dir, e.getMessage());
        }
    }

    private static final long SESSION_IDLE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
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
        // Schedule periodic cleanup of abandoned sessions
        sessionCleanupExecutor.scheduleAtFixedRate(this::cleanupAbandonedSessions, 1, 1, TimeUnit.MINUTES);
        LOG.info("HlsService initialized with periodic session cleanup (TTL: {} min)", SESSION_IDLE_TTL_MS / 60000);
    }

    @PreDestroy
    public void shutdown() {
        hwRetryExecutor.shutdownNow();
        sessionCleanupExecutor.shutdownNow();
        activeSessions.values().forEach(HlsSession::stop);
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
        public final Path sessionDir;
        public final double startSeconds;
        public final List<String> audioPlaylistNames = new ArrayList<>();
        public long lastAccessed;
        private final Map<String, Process> processes = new ConcurrentHashMap<>();
        private final Map<String, Integer> restartAttempts = new ConcurrentHashMap<>();
        private final Map<String, Long> lastRestartTimes = new ConcurrentHashMap<>();
        private final Map<String, Integer> lastMediaSequences = new ConcurrentHashMap<>();
        private Integer preferredAudioTrackIndex = null;

        public volatile boolean stopped = false;

        public int qualityHeight = 0;
        public List<VariantConfig> variants = null;

        public HlsSession(String id, Video v, List<AudioTrack> tracks, Path d, double s) {
            sessionId = id;
            video = v;
            audioTracks = tracks;
            sessionDir = d;
            startSeconds = s;
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "*.{ts,m4s,m3u8,mp4}")) {
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
