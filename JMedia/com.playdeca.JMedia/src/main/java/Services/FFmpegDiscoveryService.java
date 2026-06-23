package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class FFmpegDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(FFmpegDiscoveryService.class);

    @Inject
    GpuDetectionService gpuDetectionService;

    private String ffmpegPath;
    private String ffprobePath;
    private String mkvmergePath;
    private String hardwareEncoder;
    private List<String> availableHardwareEncoders;

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String resolveViaWhere(String tool) {
        if (!isWindows()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("where", tool);
            Process process = pb.start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("where.exe {} failed: {}", tool, e.getMessage());
        }
        return null;
    }

    private List<String> findInDirectory(File dir, String targetFileName) {
        List<String> results = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return results;
        try (Stream<Path> stream = Files.walk(dir.toPath(), 6, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.getFileName().toString().equalsIgnoreCase(targetFileName))
                  .filter(p -> p.toFile().isFile())
                  .map(Path::toString)
                  .forEach(results::add);
        } catch (Exception e) {
            LOG.warn("Error searching {} for {}: {}", dir, targetFileName, e.getMessage());
        }
        return results;
    }

    private boolean probeExecutable(String path, String... args) {
        try {
            String[] cmd = new String[1 + args.length];
            cmd[0] = path;
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.debug("Probe timed out for: {}", path);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("Probe failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    public synchronized String findFFmpegExecutable() {
        if (ffmpegPath != null) {
            return ffmpegPath;
        }

        // 1. bare name PATH lookups
        if (probeExecutable("ffmpeg", "-version")) {
            ffmpegPath = "ffmpeg";
            return ffmpegPath;
        }
        if (probeExecutable("ffmpeg.exe", "-version")) {
            ffmpegPath = "ffmpeg.exe";
            return ffmpegPath;
        }

        // 2. Windows: use where.exe to resolve from system PATH
        String wherePath = resolveViaWhere("ffmpeg");
        if (wherePath != null && probeExecutable(wherePath, "-version")) {
            ffmpegPath = wherePath;
            return ffmpegPath;
        }

        // 3. hardcoded common install paths
        String[] hardcoded = {
            "C:\\ProgramData\\chocolatey\\lib\\ffmpeg\\tools\\ffmpeg.exe",
            "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe",
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files\\FFmpeg\\bin\\ffmpeg.exe",
            "/usr/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/opt/homebrew/bin/ffmpeg"
        };
        for (String p : hardcoded) {
            if (new File(p).exists() && probeExecutable(p, "-version")) {
                ffmpegPath = p;
                return ffmpegPath;
            }
        }

        // 4. Windows: scan chocolatey lib directory for actual ffmpeg binary
        if (isWindows()) {
            File chocoLib = new File("C:\\ProgramData\\chocolatey\\lib\\ffmpeg");
            if (chocoLib.isDirectory()) {
                List<String> found = findInDirectory(chocoLib, "ffmpeg.exe");
                for (String candidate : found) {
                    if (probeExecutable(candidate, "-version")) {
                        ffmpegPath = candidate;
                        return ffmpegPath;
                    }
                }
            }
        }

        LOG.warn("FFmpeg not found after all detection attempts");
        return null;
    }

    public synchronized String findFFprobeExecutable() {
        if (ffprobePath != null) {
            return ffprobePath;
        }

        // derive from ffmpeg path if already cached
        if (ffmpegPath != null) {
            String derived = ffmpegPath.endsWith(".exe")
                ? ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe")
                : ffmpegPath.replace("ffmpeg", "ffprobe");
            if (new File(derived).exists() && probeExecutable(derived, "-version")) {
                ffprobePath = derived;
                return ffprobePath;
            }
        }

        // 1. bare name PATH lookups
        if (probeExecutable("ffprobe", "-version")) {
            ffprobePath = "ffprobe";
            return ffprobePath;
        }
        if (probeExecutable("ffprobe.exe", "-version")) {
            ffprobePath = "ffprobe.exe";
            return ffprobePath;
        }

        // 2. Windows: use where.exe to resolve from system PATH
        String wherePath = resolveViaWhere("ffprobe");
        if (wherePath != null && probeExecutable(wherePath, "-version")) {
            ffprobePath = wherePath;
            return ffprobePath;
        }

        // 3. hardcoded common install paths
        String[] hardcoded = {
            "C:\\ProgramData\\chocolatey\\lib\\ffmpeg\\tools\\ffprobe.exe",
            "C:\\ProgramData\\chocolatey\\bin\\ffprobe.exe",
            "C:\\ffmpeg\\bin\\ffprobe.exe",
            "C:\\Program Files\\FFmpeg\\bin\\ffprobe.exe",
            "/usr/bin/ffprobe",
            "/usr/local/bin/ffprobe",
            "/opt/homebrew/bin/ffprobe"
        };
        for (String p : hardcoded) {
            if (new File(p).exists() && probeExecutable(p, "-version")) {
                ffprobePath = p;
                return ffprobePath;
            }
        }

        // 4. Windows: scan chocolatey lib directory for actual ffprobe binary
        if (isWindows()) {
            File chocoLib = new File("C:\\ProgramData\\chocolatey\\lib\\ffmpeg");
            if (chocoLib.isDirectory()) {
                List<String> found = findInDirectory(chocoLib, "ffprobe.exe");
                for (String candidate : found) {
                    if (probeExecutable(candidate, "-version")) {
                        ffprobePath = candidate;
                        return ffprobePath;
                    }
                }
            }
        }

        LOG.warn("FFprobe not found after all detection attempts");
        return null;
    }

    public synchronized String findMkvmerge() {
        if (mkvmergePath != null) {
            return mkvmergePath;
        }

        // 1. bare name PATH lookups
        if (probeExecutable("mkvmerge", "--version")) {
            mkvmergePath = "mkvmerge";
            return mkvmergePath;
        }
        if (probeExecutable("mkvmerge.exe", "--version")) {
            mkvmergePath = "mkvmerge.exe";
            return mkvmergePath;
        }

        // 2. Windows: use where.exe to resolve from system PATH
        String wherePath = resolveViaWhere("mkvmerge");
        if (wherePath != null && probeExecutable(wherePath, "--version")) {
            mkvmergePath = wherePath;
            return mkvmergePath;
        }

        // 3. hardcoded common install paths
        String[] hardcoded = {
            "C:\\ProgramData\\chocolatey\\lib\\mkvtoolnix\\tools\\mkvmerge.exe",
            "C:\\ProgramData\\chocolatey\\bin\\mkvmerge.exe",
            "C:\\mkvtoolnix\\mkvmerge.exe",
            "C:\\Program Files\\MKVToolNix\\mkvmerge.exe",
            "/usr/bin/mkvmerge",
            "/usr/local/bin/mkvmerge",
            "/opt/homebrew/bin/mkvmerge"
        };
        for (String p : hardcoded) {
            if (new File(p).exists() && probeExecutable(p, "--version")) {
                mkvmergePath = p;
                return mkvmergePath;
            }
        }

        // 4. Windows: scan chocolatey lib directory for actual mkvmerge binary
        if (isWindows()) {
            File chocoLib = new File("C:\\ProgramData\\chocolatey\\lib\\mkvtoolnix");
            if (chocoLib.isDirectory()) {
                List<String> found = findInDirectory(chocoLib, "mkvmerge.exe");
                for (String candidate : found) {
                    if (probeExecutable(candidate, "--version")) {
                        mkvmergePath = candidate;
                        return mkvmergePath;
                    }
                }
            }
        }

        LOG.warn("mkvmerge not found after all detection attempts");
        return null;
    }

    /**
     * Returns all available hardware encoders detected from ffmpeg, in priority order.
     * Result is cached after the first invocation. Filters by runtime usability.
     */
    public synchronized List<String> getAvailableHardwareEncoders() {
        if (availableHardwareEncoders != null) {
            return availableHardwareEncoders;
        }

        availableHardwareEncoders = new ArrayList<>();
        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) {
            return availableHardwareEncoders;
        }

        List<String> priorityEncoders = List.of(
            "h264_nvenc", "hevc_nvenc",
            "h264_videotoolbox", "hevc_videotoolbox",
            "h264_amf", "hevc_amf",
            "h264_qsv", "hevc_qsv",
            "h264_vaapi", "hevc_vaapi",
            "h264_v4l2m2m", "h264_omx"
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-encoders");
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            String errorOutput = new String(process.getErrorStream().readAllBytes());
            process.waitFor();
            String allOutput = output + errorOutput;

            for (String encoder : priorityEncoders) {
                if (allOutput.contains(encoder) && isEncoderUsable(encoder)) {
                    availableHardwareEncoders.add(encoder);
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.warn("Failed to query ffmpeg encoders: {}", e.getMessage());
        }

        return availableHardwareEncoders;
    }

    public synchronized String detectHardwareEncoder() {
        if (hardwareEncoder != null) {
            return hardwareEncoder;
        }

        List<String> encoders = getAvailableHardwareEncoders();
        if (!encoders.isEmpty()) {
            hardwareEncoder = encoders.get(0);
        } else {
            hardwareEncoder = "libx264";
        }
        return hardwareEncoder;
    }

    public String getBestNvidiaDeviceIndex() {
        return gpuDetectionService.getBestGpuSelection().nvidia()
                .map(g -> String.valueOf(g.deviceIndex()))
                .orElse(null);
    }

    public String getBestQsvDevicePath() {
        return gpuDetectionService.getBestGpuSelection().intel()
                .map(GpuDetectionService.GpuInfo::devicePath)
                .orElse(null);
    }

    public String getBestVaaPiDevicePath() {
        return gpuDetectionService.getBestGpuSelection().amd()
                .map(GpuDetectionService.GpuInfo::devicePath)
                .orElse(null);
    }

    public GpuDetectionService.GpuInfo getBestAmfGpu() {
        return gpuDetectionService.getBestGpuSelection().amd().orElse(null);
    }

    public GpuDetectionService.BestGpuSelection getBestGpuSelection() {
        return gpuDetectionService.getBestGpuSelection();
    }

    private java.util.Set<String> supportedDecoders;
    private final java.util.Set<String> probedUsableHwaccels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> probedFailedHwaccels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private String decoderToHwaccelType(String decoder) {
        if (decoder.contains("cuvid")) return "cuda";
        if (decoder.contains("vaapi")) return "vaapi";
        if (decoder.contains("qsv")) return "qsv";
        if (decoder.contains("videotoolbox")) return "videotoolbox";
        if (decoder.contains("amf")) return "amf";
        if (decoder.contains("v4l2m2m")) return "v4l2m2m";
        return null;
    }

    /**
     * Probes whether a hardware acceleration device type is actually usable at runtime
     * by attempting to initialize it via FFmpeg. Caches results to avoid repeated probes.
     */
    private boolean isHwaccelUsable(String hwaccelType) {
        if (probedUsableHwaccels.contains(hwaccelType)) return true;
        if (probedFailedHwaccels.contains(hwaccelType)) return false;

        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) return false;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg, "-v", "error",
                "-init_hw_device", hwaccelType,
                "-f", "null", "-"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            boolean success = finished && p.exitValue() == 0;

            if (success) {
                probedUsableHwaccels.add(hwaccelType);
                LOG.debug("Hardware device '{}' is usable", hwaccelType);
            } else {
                probedFailedHwaccels.add(hwaccelType);
                LOG.debug("Hardware device '{}' not usable: {}", hwaccelType, output.trim().replace('\n', ' '));
            }
            return success;
        } catch (Exception e) {
            probedFailedHwaccels.add(hwaccelType);
            LOG.debug("Failed to probe hardware device '{}': {}", hwaccelType, e.getMessage());
            return false;
        }
    }

    private boolean decoderIsUsable(String decoder) {
        String hwaccelType = decoderToHwaccelType(decoder);
        if (hwaccelType == null) return false;
        return isHwaccelUsable(hwaccelType);
    }

    public String getHardwareDecoder(String codec) {
        if (supportedDecoders == null) {
            supportedDecoders = new java.util.HashSet<>();
            String ffmpeg = findFFmpegExecutable();
            if (ffmpeg != null) {
                try {
                    Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-decoders").start();
                    java.util.Scanner s = new java.util.Scanner(p.getInputStream());
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.contains("nvenc") || line.contains("qsv") || line.contains("vaapi") || 
                            line.contains("cuvid") || line.contains("v4l2m2m") || line.contains("amf") ||
                            line.contains("videotoolbox")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) supportedDecoders.add(parts[1]);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        
        if (codec == null) return null;
        String lowerCodec = codec.toLowerCase();
        boolean isH264 = lowerCodec.contains("h264") || lowerCodec.contains("avc");
        boolean isHEVC = lowerCodec.contains("hevc") || lowerCodec.contains("h265");
        boolean isVP9 = lowerCodec.contains("vp9");
        boolean isAV1 = lowerCodec.contains("av1");
        
        if (isH264) {
            if (supportedDecoders.contains("h264_cuvid") && decoderIsUsable("h264_cuvid")) return "h264_cuvid";
            if (supportedDecoders.contains("h264_videotoolbox") && decoderIsUsable("h264_videotoolbox")) return "h264_videotoolbox";
            if (supportedDecoders.contains("h264_qsv") && decoderIsUsable("h264_qsv")) return "h264_qsv";
            if (supportedDecoders.contains("h264_amf") && decoderIsUsable("h264_amf")) return "h264_amf";
            if (supportedDecoders.contains("h264_vaapi") && decoderIsUsable("h264_vaapi")) return "h264_vaapi";
            if (supportedDecoders.contains("h264_v4l2m2m") && decoderIsUsable("h264_v4l2m2m")) return "h264_v4l2m2m";
        } else if (isHEVC) {
            if (supportedDecoders.contains("hevc_cuvid") && decoderIsUsable("hevc_cuvid")) return "hevc_cuvid";
            if (supportedDecoders.contains("hevc_videotoolbox") && decoderIsUsable("hevc_videotoolbox")) return "hevc_videotoolbox";
            if (supportedDecoders.contains("hevc_qsv") && decoderIsUsable("hevc_qsv")) return "hevc_qsv";
            if (supportedDecoders.contains("hevc_amf") && decoderIsUsable("hevc_amf")) return "hevc_amf";
            if (supportedDecoders.contains("hevc_vaapi") && decoderIsUsable("hevc_vaapi")) return "hevc_vaapi";
            if (supportedDecoders.contains("hevc_v4l2m2m") && decoderIsUsable("hevc_v4l2m2m")) return "hevc_v4l2m2m";
        } else if (isVP9) {
            if (supportedDecoders.contains("vp9_cuvid") && decoderIsUsable("vp9_cuvid")) return "vp9_cuvid";
            if (supportedDecoders.contains("vp9_qsv") && decoderIsUsable("vp9_qsv")) return "vp9_qsv";
            if (supportedDecoders.contains("vp9_vaapi") && decoderIsUsable("vp9_vaapi")) return "vp9_vaapi";
        } else if (isAV1) {
            if (supportedDecoders.contains("av1_cuvid") && decoderIsUsable("av1_cuvid")) return "av1_cuvid";
            if (supportedDecoders.contains("av1_qsv") && decoderIsUsable("av1_qsv")) return "av1_qsv";
            if (supportedDecoders.contains("av1_vaapi") && decoderIsUsable("av1_vaapi")) return "av1_vaapi";
        }
        return null;
    }

    private final java.util.Set<String> probedUsableEncoders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> probedFailedEncoders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, java.util.List<Long>> encoderFailureTimestamps = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long ENCODER_FAILURE_WINDOW_MS = 300_000; // 5 minutes
    private static final int ENCODER_MAX_FAILURES = 5;

    private boolean isEncoderUsable(String encoder) {
        if (probedUsableEncoders.contains(encoder)) return true;
        if (probedFailedEncoders.contains(encoder)) return false;
        
        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) return false;
        
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg, "-v", "error", "-hide_banner",
                "-f", "lavfi", "-i", "testsrc=duration=0.1:size=320x240:rate=1",
                "-c:v", encoder, "-frames:v", "1", "-f", "null", "-"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            boolean success = finished && p.exitValue() == 0;
            
            if (success) {
                probedUsableEncoders.add(encoder);
                LOG.debug("Encoder '{}' verified usable", encoder);
            } else {
                probedFailedEncoders.add(encoder);
                LOG.debug("Encoder '{}' not usable (exit={})", encoder, finished ? p.exitValue() : -1);
            }
            return success;
        } catch (Exception e) {
            probedFailedEncoders.add(encoder);
            LOG.debug("Encoder probe failed for '{}': {}", encoder, e.getMessage());
            return false;
        }
    }

    public synchronized void invalidateEncoder(String encoder) {
        probedFailedEncoders.add(encoder);
        probedUsableEncoders.remove(encoder);
        if (availableHardwareEncoders != null) {
            availableHardwareEncoders.remove(encoder);
        }
        if (hardwareEncoder != null && hardwareEncoder.equals(encoder)) {
            hardwareEncoder = null;
        }
        LOG.warn("Encoder '{}' invalidated due to runtime failure", encoder);
    }

    /**
     * Records an encoder failure and invalidates it if the threshold
     * (5 failures within a rolling 5-minute window) is reached.
     */
    public void recordEncoderFailure(String encoder) {
        long now = System.currentTimeMillis();
        java.util.List<Long> failures = encoderFailureTimestamps.computeIfAbsent(encoder, k -> new java.util.ArrayList<>());
        synchronized (failures) {
            failures.add(now);
            failures.removeIf(t -> now - t > ENCODER_FAILURE_WINDOW_MS);
            LOG.warn("Encoder '{}' failure {}/{} in the last 5 minutes", encoder, failures.size(), ENCODER_MAX_FAILURES);
            if (failures.size() >= ENCODER_MAX_FAILURES) {
                invalidateEncoder(encoder);
                failures.clear();
            }
        }
    }

    public java.util.Set<String> getInvalidatedEncoders() {
        return java.util.Collections.unmodifiableSet(probedFailedEncoders);
    }
}
