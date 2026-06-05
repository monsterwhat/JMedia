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

@ApplicationScoped
public class FFmpegDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(FFmpegDiscoveryService.class);

    private String ffmpegPath;
    private String ffprobePath;
    private String mkvmergePath;
    private String hardwareEncoder;

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

    public synchronized String detectHardwareEncoder() {
        if (hardwareEncoder != null) {
            return hardwareEncoder;
        }

        String ffmpeg = findFFmpegExecutable();
        if (ffmpeg == null) {
            hardwareEncoder = "libx264";
            return hardwareEncoder;
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
                if (allOutput.contains(encoder)) {
                    hardwareEncoder = encoder;
                    return hardwareEncoder;
                }
            }
        } catch (IOException | InterruptedException e) {
            // Fall through to default
        }

        hardwareEncoder = "libx264";
        return hardwareEncoder;
    }

    private java.util.Set<String> supportedDecoders;

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
            if (supportedDecoders.contains("h264_cuvid")) return "h264_cuvid";
            if (supportedDecoders.contains("h264_videotoolbox")) return "h264_videotoolbox";
            if (supportedDecoders.contains("h264_qsv")) return "h264_qsv";
            if (supportedDecoders.contains("h264_vaapi")) return "h264_vaapi";
            if (supportedDecoders.contains("h264_v4l2m2m")) return "h264_v4l2m2m";
        } else if (isHEVC) {
            if (supportedDecoders.contains("hevc_cuvid")) return "hevc_cuvid";
            if (supportedDecoders.contains("hevc_videotoolbox")) return "hevc_videotoolbox";
            if (supportedDecoders.contains("hevc_qsv")) return "hevc_qsv";
            if (supportedDecoders.contains("hevc_vaapi")) return "hevc_vaapi";
            if (supportedDecoders.contains("hevc_v4l2m2m")) return "hevc_v4l2m2m";
        } else if (isVP9) {
            if (supportedDecoders.contains("vp9_cuvid")) return "vp9_cuvid";
            if (supportedDecoders.contains("vp9_qsv")) return "vp9_qsv";
            if (supportedDecoders.contains("vp9_vaapi")) return "vp9_vaapi";
        } else if (isAV1) {
            if (supportedDecoders.contains("av1_cuvid")) return "av1_cuvid";
            if (supportedDecoders.contains("av1_qsv")) return "av1_qsv";
            if (supportedDecoders.contains("av1_vaapi")) return "av1_vaapi";
        }
        return null;
    }
}
