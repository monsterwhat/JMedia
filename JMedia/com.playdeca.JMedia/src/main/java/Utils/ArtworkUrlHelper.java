package Utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central helper for versioned artwork URLs. Every artwork file replacement
 * yields a new URL via {@code ?v=<lastModified>} so browsers honor the
 * long {@code max-age=86400} yet never serve stale bytes after a refetch.
 * <p>
 * Keeps HTTP caching effective (immutable versioned URLs) without lowering
 * max-age or adding must-revalidate. Preserve existing query params (append
 * with {@code &v=} when {@code ?} already present).
 */
public final class ArtworkUrlHelper {

    private static final Logger LOG = LoggerFactory.getLogger(ArtworkUrlHelper.class);

    private ArtworkUrlHelper() {}

    /**
     * Append {@code ?v=<millis>} or {@code &v=<millis>} to a base URL.
     * Returns base unchanged when {@code lastModified <= 0}.
     */
    public static String withVersion(String baseUrl, long lastModified) {
        if (baseUrl == null || baseUrl.isBlank() || lastModified <= 0) {
            return baseUrl;
        }
        char sep = baseUrl.contains("?") ? '&' : '?';
        return baseUrl + sep + "v=" + lastModified;
    }

    /**
     * Resolve lastModified millis for a DB-stored path string.
     * Returns 0 when path blank, file missing, or unreadable.
     */
    public static long lastModifiedForPath(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            return 0;
        }
        try {
            Path p = Paths.get(dbPath);
            if (!Files.isRegularFile(p)) {
                return 0;
            }
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            LOG.debug("ArtworkUrlHelper: could not stat {}: {}", dbPath, e.getMessage());
            return 0;
        }
    }

    public static long lastModifiedForFile(File f) {
        if (f == null || !f.isFile()) return 0;
        try {
            return f.lastModified();
        } catch (Exception e) {
            LOG.debug("ArtworkUrlHelper: could not stat File {}: {}", f, e.getMessage());
            return 0;
        }
    }

    public static long lastModifiedForNioPath(Path p) {
        if (p == null) return 0;
        try {
            if (!Files.isRegularFile(p)) return 0;
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            LOG.debug("ArtworkUrlHelper: could not stat Path {}: {}", p, e.getMessage());
            return 0;
        }
    }

    /**
     * Versioned URL from a DB path + base. Resolves lastModified server-side.
     */
    public static String versionedFromPath(String baseUrl, String dbPath) {
        long lm = lastModifiedForPath(dbPath);
        return withVersion(baseUrl, lm);
    }

    public static String versionedFromFile(String baseUrl, File file) {
        long lm = lastModifiedForFile(file);
        return withVersion(baseUrl, lm);
    }

    public static String versionedFromNioPath(String baseUrl, Path path) {
        long lm = lastModifiedForNioPath(path);
        return withVersion(baseUrl, lm);
    }
}
