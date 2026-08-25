package Services;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup janitor for "ghost" child processes left behind by a previous,
 * non-gracefully-exited run (IDE force-stop, terminal close, taskkill /F, crash).
 * Windows does not kill child processes when the parent JVM dies, so an orphaned
 * ffmpeg/whisper/yt-dlp keeps encoding and burning CPU until killed manually.
 *
 * Matching is deliberately conservative: only processes whose executable is one
 * of the tools this app spawns AND whose command line carries a JMedia-specific
 * invocation signature (HLS segmenting, fMP4-to-pipe transcoding, thumbnail
 * frame extraction, raw-PCM analysis pipes, whisper, yt-dlp, spotdl, parakeet)
 * are killed. Everything killed is logged at WARN.
 */
@ApplicationScoped
public class OrphanProcessReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanProcessReaper.class);

    private static final Pattern TOOL_NAMES = Pattern.compile(
        "(?i)^(ffmpeg|ffprobe|python|python3|py|spotdl|yt-dlp)(\\.exe)?$");

    // Command-line signatures of processes this app spawns.
    private static final List<Pattern> SIGNATURES = List.of(
        // HLS segmenter: ... -f hls ... <session>/variant_N.m3u8
        Pattern.compile("(?i).*\\-f\\s+hls\\s+.*\\.m3u8.*"),
        // On-the-fly stream transcode: -movflags frag_keyframe... pipe:1
        Pattern.compile("(?i).*frag_keyframe.*pipe:1.*"),
        // Gap transcode: -output_ts_offset <start> ... cache-*.mp4
        Pattern.compile("(?i).*\\-output_ts_offset\\s+[0-9.\\-]+\\s+.*cache\\-.*\\.mp4.*"),
        // Thumbnail / storyboard frame extract: -frames:v 1 ... libwebp
        Pattern.compile("(?i).*\\-frames:v\\s+1\\s+.*libwebp.*"),
        // TarsosDSP raw-PCM analysis pipe: -f s16le/f32le/... pipe:1
        Pattern.compile("(?i).*\\-f\\s+(s16le|s16be|f32le|f32be|f24le|u8)\\s+.*pipe:1.*"),
        // PGS subtitle extraction
        Pattern.compile("(?i).*\\-f\\s+sup\\b.*"),
        // Whisper lyrics transcription
        Pattern.compile("(?i).*\\-m\\s+whisper\\b.*"),
        // yt-dlp download
        Pattern.compile("(?i).*\\-m\\s+yt_dlp\\b.*"),
        // spotdl download (module invocation)
        Pattern.compile("(?i).*\\-m\\s+spotdl\\b.*"),
        // spotdl download (standalone binary)
        Pattern.compile("(?i).*spotdl(\\.exe)?\\s+.*\\-\\-output\\s+.*"),
        // Parakeet AI subtitle transcription
        Pattern.compile("(?i).*run_parakeet\\.py.*")
    );

    void onStartup(@Observes StartupEvent event) {
        try {
            List<ProcessInfo> candidates = isWindows() ? queryWindows() : queryPosix();
            int killed = 0;
            for (ProcessInfo pi : candidates) {
                if (pi.commandLine() == null || pi.commandLine().isBlank()) {
                    continue;
                }
                if (!TOOL_NAMES.matcher(pi.name()).matches()) {
                    continue;
                }
                if (matchesSignature(pi.commandLine())) {
                    killTree(pi.pid());
                    killed++;
                    LOG.warn("OrphanProcessReaper killed stale {} (pid={}): {}",
                            pi.name(), pi.pid(), truncate(pi.commandLine()));
                }
            }
            if (killed > 0) {
                LOG.warn("OrphanProcessReaper terminated {} leftover process(es) from a previous run", killed);
            }
        } catch (Exception e) {
            LOG.warn("OrphanProcessReaper scan failed: {}", e.getMessage());
        }
    }

    private boolean matchesSignature(String commandLine) {
        return SIGNATURES.stream().anyMatch(p -> p.matcher(commandLine).matches());
    }

    private List<ProcessInfo> queryWindows() throws Exception {
        List<ProcessInfo> result = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(ffmpeg|ffprobe|python|python3|py|spotdl|yt-dlp)(\\.exe)?$' -and $_.CommandLine } | ForEach-Object { \"$($_.ProcessId)`t$($_.Name)`t$($_.CommandLine)\" }"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int firstTab = line.indexOf('\t');
                int secondTab = line.indexOf('\t', firstTab + 1);
                if (firstTab <= 0 || secondTab <= firstTab) continue;
                try {
                    long pid = Long.parseLong(line.substring(0, firstTab));
                    result.add(new ProcessInfo(pid, line.substring(firstTab + 1, secondTab), line.substring(secondTab + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return result;
    }

    private List<ProcessInfo> queryPosix() throws Exception {
        List<ProcessInfo> result = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("ps", "-eo", "pid=,comm=,args=");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int firstSpace = line.indexOf(' ');
                int secondSpace = line.indexOf(' ', firstSpace + 1);
                if (firstSpace <= 0 || secondSpace <= firstSpace) continue;
                try {
                    long pid = Long.parseLong(line.substring(0, firstSpace));
                    result.add(new ProcessInfo(pid, line.substring(firstSpace + 1, secondSpace), line.substring(secondSpace + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return result;
    }

    private void killTree(long pid) {
        try {
            if (isWindows()) {
                ProcessBuilder pb = new ProcessBuilder("taskkill.exe", "/F", "/T", "/PID", String.valueOf(pid));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(5, TimeUnit.SECONDS);
                p.destroyForcibly();
            } else {
                ProcessHandle.of(pid).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.destroyForcibly();
                });
            }
        } catch (Exception e) {
            LOG.debug("Failed to kill pid {}: {}", pid, e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private record ProcessInfo(long pid, String name, String commandLine) {
    }
}