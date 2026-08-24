package Services;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves which JavaScript runtime yt-dlp should use for YouTube extraction.
 *
 * <p>yt-dlp's pip distribution requires an external JS runtime for YouTube
 * challenge solving. Only Deno is auto-detected by yt-dlp itself; Node.js and
 * Bun must be passed explicitly via {@code --js-runtimes RUNTIME[:PATH]}.</p>
 *
 * <p>Resolution priority:</p>
 * <ol>
 *   <li>{@code deno} on PATH → append nothing (yt-dlp handles default Deno)</li>
 *   <li>{@code deno} at the known off-PATH install location → {@code --js-runtimes deno:<path>}</li>
 *   <li>{@code node} on PATH with version &gt;= 22 → {@code --js-runtimes node}</li>
 *   <li>{@code bun} on PATH with version in [1.2.11, 1.3.14] → {@code --js-runtimes bun}</li>
 *   <li>nothing found → empty args (caller may decide to install Deno)</li>
 * </ol>
 *
 * <p>Results are memoized for a short TTL because every probe spawns a
 * process and resolution runs once per download. Use {@link #reset()} to
 * invalidate after installing a runtime.</p>
 */
@ApplicationScoped
public class JsRuntimeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsRuntimeResolver.class);

    // Minimum/maximum Bun versions supported by yt-dlp-ejs (inclusive range)
    private static final int[] BUN_MIN_VERSION = {1, 2, 11};
    private static final int[] BUN_MAX_VERSION = {1, 3, 14};
    // Minimum Node.js major version supported by yt-dlp-ejs
    private static final int NODE_MIN_MAJOR = 22;

    // Probe results are expensive (process spawns); cache briefly
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    /**
     * The kind of JS runtime that was resolved.
     */
    public enum RuntimeType {
        DENO_ON_PATH, DENO_KNOWN_LOCATION, NODE, BUN, NONE
    }

    /**
     * Immutable resolution result: which runtime was chosen and the extra
     * arguments to append to the yt-dlp command line (empty when none needed).
     */
    public static class Resolution {
        private final RuntimeType type;
        private final String runtimeName;
        private final List<String> ytDlpArgs;

        Resolution(RuntimeType type, String runtimeName, List<String> ytDlpArgs) {
            this.type = type;
            this.runtimeName = runtimeName;
            this.ytDlpArgs = ytDlpArgs;
        }

        public RuntimeType getType() {
            return type;
        }

        public String getRuntimeName() {
            return runtimeName;
        }

        /**
         * @return the arguments to append to the yt-dlp command before the URL
         *         argument (never null, may be empty)
         */
        public List<String> getYtDlpArgs() {
            return new ArrayList<>(ytDlpArgs);
        }

        public boolean hasUsableRuntime() {
            return type != RuntimeType.NONE;
        }
    }

    private static final Resolution NONE_RESOLUTION =
            new Resolution(RuntimeType.NONE, "none", new ArrayList<>());

    private final AtomicReference<CachedResolution> cache = new AtomicReference<>();

    private static class CachedResolution {
        final long timestamp;
        final Resolution resolution;

        CachedResolution(long timestamp, Resolution resolution) {
            this.timestamp = timestamp;
            this.resolution = resolution;
        }
    }

    /**
     * Resolves the JS runtime for yt-dlp, using the cached result when fresh.
     *
     * @return the resolution (never null); {@link RuntimeType#NONE} when no
     *         usable runtime is available
     */
    public Resolution resolve() {
        CachedResolution cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            LOGGER.debug("Using cached JS runtime resolution: {}", cached.resolution.getRuntimeName());
            return cached.resolution;
        }

        Resolution resolution = doResolve();
        cache.set(new CachedResolution(now, resolution));
        LOGGER.debug("Resolved JS runtime for yt-dlp: {}", resolution.getRuntimeName());
        return resolution;
    }

    /**
     * @return true if any usable JS runtime was found
     */
    public boolean hasUsableRuntime() {
        return resolve().hasUsableRuntime();
    }

    /**
     * Invalidates the cached resolution so the next call re-probes the system.
     */
    public void reset() {
        cache.set(null);
        LOGGER.debug("JS runtime resolution cache cleared");
    }

    private Resolution doResolve() {
        // 1. deno on PATH -> yt-dlp auto-detects it, no flags needed
        try {
            if (isCommandOnPath("deno")) {
                return new Resolution(RuntimeType.DENO_ON_PATH, "deno", new ArrayList<>());
            }
        } catch (Exception e) {
            LOGGER.debug("Deno PATH probe failed: {}", e.getMessage());
        }

        // 2. deno at known off-PATH install location -> explicit path flag
        try {
            Path knownDeno = getKnownDenoPath();
            if (knownDeno != null && Files.exists(knownDeno)) {
                List<String> args = new ArrayList<>();
                args.add("--js-runtimes");
                args.add("deno:" + knownDeno.toAbsolutePath().toString());
                return new Resolution(RuntimeType.DENO_KNOWN_LOCATION, "deno (off-PATH)", args);
            }
        } catch (Exception e) {
            LOGGER.debug("Deno known-location probe failed: {}", e.getMessage());
        }

        // 3. node on PATH with version >= 22
        try {
            if (isCommandOnPath("node")) {
                Integer major = parseMajorVersion(getFirstVersionLine("node"));
                if (major != null && major >= NODE_MIN_MAJOR) {
                    List<String> args = new ArrayList<>();
                    args.add("--js-runtimes");
                    args.add("node");
                    return new Resolution(RuntimeType.NODE, "node " + major, args);
                }
                LOGGER.debug("Node.js found but version {} is below minimum {}", major, NODE_MIN_MAJOR);
            }
        } catch (Exception e) {
            LOGGER.debug("Node probe failed: {}", e.getMessage());
        }

        // 4. bun on PATH with version within the supported range
        try {
            if (isCommandOnPath("bun")) {
                int[] version = parseVersion(getFirstVersionLine("bun"));
                if (version != null && isVersionInRange(version, BUN_MIN_VERSION, BUN_MAX_VERSION)) {
                    List<String> args = new ArrayList<>();
                    args.add("--js-runtimes");
                    args.add("bun");
                    return new Resolution(RuntimeType.BUN, "bun " + formatVersion(version), args);
                }
                LOGGER.debug("Bun found but version {} is outside the supported range {} - {}",
                        version != null ? formatVersion(version) : "unknown",
                        formatVersion(BUN_MIN_VERSION), formatVersion(BUN_MAX_VERSION));
            }
        } catch (Exception e) {
            LOGGER.debug("Bun probe failed: {}", e.getMessage());
        }

        // 5. nothing usable found
        return NONE_RESOLUTION;
    }

    private boolean isCommandOnPath(String command) throws Exception {
        ProcessBuilder pb = isWindows()
                ? new ProcessBuilder("where", command)
                : new ProcessBuilder("which", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private String getFirstVersionLine(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command, "--version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String firstLine = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            firstLine = reader.readLine();
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command + " --version timed out");
        }
        if (process.exitValue() != 0 || firstLine == null) {
            throw new IllegalStateException(command + " --version failed with exit code " + process.exitValue());
        }
        return firstLine.trim();
    }

    /**
     * @return the absolute path of the well-known per-user Deno install
     *         location, or null when not applicable
     */
    private Path getKnownDenoPath() {
        String userHome = System.getProperty("user.home");
        String denoPath = isWindows()
                ? userHome + File.separator + ".deno" + File.separator + "bin" + File.separator + "deno.exe"
                : userHome + "/.deno/bin/deno";
        return Paths.get(denoPath);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Parses a version string like "v24.15.0" or "1.2.11" and returns the
     * major version number, or null when unparseable.
     */
    private Integer parseMajorVersion(String versionOutput) {
        int[] parts = parseVersion(versionOutput);
        return parts != null ? parts[0] : null;
    }

    /**
     * Parses a dotted version string like "v24.15.0" into numeric parts,
     * ignoring any leading "v" and trailing suffixes, or null when unparseable.
     */
    private int[] parseVersion(String versionOutput) {
        if (versionOutput == null) {
            return null;
        }
        String cleaned = versionOutput.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        // Strip any suffix after the dotted numbers (e.g. "1.3.14-beta.1")
        String[] dotParts = cleaned.split("\\.");
        List<Integer> nums = new ArrayList<>();
        for (String part : dotParts) {
            String digits = part;
            int dashIndex = digits.indexOf('-');
            if (dashIndex >= 0) {
                digits = digits.substring(0, dashIndex);
            }
            try {
                nums.add(Integer.parseInt(digits));
            } catch (NumberFormatException e) {
                break;
            }
        }
        if (nums.isEmpty()) {
            return null;
        }
        int[] result = new int[nums.size()];
        for (int i = 0; i < nums.size(); i++) {
            result[i] = nums.get(i);
        }
        return result;
    }

    /**
     * Compares a parsed version against an inclusive min/max range. Missing
     * components are treated as zero (e.g. "1.2" == 1.2.0).
     */
    private boolean isVersionInRange(int[] version, int[] min, int[] max) {
        return compareVersions(version, min) >= 0 && compareVersions(version, max) <= 0;
    }

    private int compareVersions(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private String formatVersion(int[] version) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < version.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(version[i]);
        }
        return sb.toString();
    }
}
