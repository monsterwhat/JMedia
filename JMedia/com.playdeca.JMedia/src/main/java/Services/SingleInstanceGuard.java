package Services;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single-instance guard — prevents concurrent JVMs from opening the same H2
 * {@code ~/.jmedia/*.mv.db} files and corrupting the MVStore.
 *
 * <p>Ordering: this is the earliest {@link StartupEvent} observer
 * ({@code @Priority(1)}). All other StartupEvent observers in this codebase
 * (migrations, admin bootstrap, video import cleanup) have no explicit priority
 * and therefore run after prioritized observers; Agroal pools themselves are
 * lazy until first {@link jakarta.persistence.EntityManager} use, which only
 * happens inside those later observers. By holding {@code app.lock} first we
 * win the race before any JPA/Hibernate/Agroal code touches the H2 files.
 *
 * <p>Crash recovery: file locks are held by the OS kernel, not persisted on
 * disk — when the owning process dies (kill, crash, power loss) the OS
 * releases the lock immediately. No stale {@code app.lock} state survives;
 * the file itself may remain but is unlocked, so the next launch succeeds.
 *
 * <p>Dev-mode restarts: {@code quarkus:dev} live-reload reuses the same JVM.
 * The lock is held by the same OS process, so a reload must not treat itself
 * as a second instance. We keep the lock in static fields and short-circuit
 * acquisition when already held in this JVM (OverlappingFileLockException
 * inside one JVM vs. {@code tryLock()==null} across JVMs).
 */
@ApplicationScoped
public class SingleInstanceGuard {

    private static final Logger LOG = LoggerFactory.getLogger(SingleInstanceGuard.class);

    static final String MESSAGE =
            "JMedia is already running — close the existing instance (check the system tray) before starting a new one";

    // Kept in statics so a quarkus:dev reload (same JVM, new CDI bean instance)
    // sees the existing lock and does not self-reject. Volatile for safe publish.
    private static volatile RandomAccessFile rafHolder;
    private static volatile FileChannel channelHolder;
    private static volatile FileLock lockHolder;
    private static volatile Path lockPathHolder;

    // Instance flag to know if this bean instance was the one that acquired
    // (prevents double-release accounting issues across reloads).
    private volatile boolean acquiredByThisInstance = false;

    /**
     * High-precedence StartupEvent observer — must run before any observer that
     * touches JPA/pool/H2. Numeric priority 1 is the lowest explicit value in
     * this codebase (all others default to 0 / no annotation so ordered after),
     * guaranteeing this guard wins the race. Throwing here aborts Quarkus boot;
     * Quarkus treats an exception from a StartupEvent observer as startup failure
     * and exits non-zero rather than limping on.
     */
    void onStart(@Observes @Priority(1) StartupEvent ev) {
        // Same-JVM fast path: dev-mode reload sees existing static lock.
        FileLock existing = lockHolder;
        if (existing != null && existing.isValid()) {
            LOG.debug("Single-instance lock already held by this JVM (dev-mode reload); reusing {}", lockPathHolder);
            return;
        }

        synchronized (SingleInstanceGuard.class) {
            // Double-check after acquiring monitor
            existing = lockHolder;
            if (existing != null && existing.isValid()) {
                LOG.debug("Single-instance lock already held by this JVM (dev-mode reload); reusing {}", lockPathHolder);
                return;
            }

            Path lockPath = lockPathHolder;
            if (lockPath == null) {
                lockPath = Paths.get(System.getProperty("user.home"), ".jmedia", "app.lock");
                lockPathHolder = lockPath;
            }

            try {
                Path parent = lockPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                LOG.error("Failed to create lock file parent directory {}: {}", lockPath.getParent(), e.getMessage(), e);
                throw new RuntimeException("Failed to create JMedia lock directory: " + e.getMessage(), e);
            }

            File file = lockPath.toFile();
            RandomAccessFile raf = null;
            FileChannel channel = null;
            FileLock lock = null;
            try {
                raf = new RandomAccessFile(file, "rw");
                channel = raf.getChannel();
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException overlap) {
                    // Same-JVM overlap: this JVM already holds the lock (e.g. a
                    // second bean instance during quarkus:dev reload before the
                    // old bean's @PreDestroy ran). Treat as held-by-self, not as
                    // a competing process — do not abort startup.
                    FileLock held = lockHolder;
                    if (held != null && held.isValid()) {
                        LOG.debug("Single-instance lock overlapping in same JVM — reusing existing lock {}", lockPath, overlap);
                        // Close the just-opened duplicate channel/raf; keep original
                        try {
                            if (channel != null) channel.close();
                        } catch (IOException ce) {
                            LOG.warn("Failed to close duplicate lock channel during overlap handling: {}", ce.getMessage(), ce);
                        }
                        try {
                            if (raf != null) raf.close();
                        } catch (IOException re) {
                            LOG.warn("Failed to close duplicate lock file during overlap handling: {}", re.getMessage(), re);
                        }
                        return;
                    }
                    // Overlap but no valid holder — treat as contention
                    LOG.error(MESSAGE + " (lock file: {})", lockPath);
                    System.err.println(MESSAGE);
                    System.err.println("Lock file: " + lockPath);
                    try { if (channel != null) channel.close(); } catch (IOException ce) {
                        LOG.warn("Failed to close lock channel after contention: {}", ce.getMessage(), ce);
                    }
                    try { if (raf != null) raf.close(); } catch (IOException re) {
                        LOG.warn("Failed to close lock file after contention: {}", re.getMessage(), re);
                    }
                    throw new RuntimeException(MESSAGE);
                }

                if (lock == null) {
                    // Cross-process contention: another JVM holds the lock
                    String msg = MESSAGE + " (lock file: " + lockPath + ")";
                    LOG.error(msg);
                    System.err.println(MESSAGE);
                    System.err.println("Lock file: " + lockPath);
                    try { if (channel != null) channel.close(); } catch (IOException ce) {
                        LOG.warn("Failed to close lock channel after contention: {}", ce.getMessage(), ce);
                    }
                    try { if (raf != null) raf.close(); } catch (IOException re) {
                        LOG.warn("Failed to close lock file after contention: {}", re.getMessage(), re);
                    }
                    throw new RuntimeException(MESSAGE);
                }

                // Success — retain references for process lifetime so GC/finalizer
                // never releases the lock prematurely.
                rafHolder = raf;
                channelHolder = channel;
                lockHolder = lock;
                acquiredByThisInstance = true;
                LOG.info("Single-instance lock acquired: {}", lockPath);

            } catch (IOException e) {
                LOG.error("Failed to acquire single-instance lock {}: {}", lockPath, e.getMessage(), e);
                if (channel != null) {
                    try { channel.close(); } catch (IOException ce) {
                        LOG.warn("Failed to close lock channel after IOException: {}", ce.getMessage(), ce);
                    }
                }
                if (raf != null) {
                    try { raf.close(); } catch (IOException re) {
                        LOG.warn("Failed to close lock file after IOException: {}", re.getMessage(), re);
                    }
                }
                throw new RuntimeException("Failed to acquire JMedia single-instance lock: " + e.getMessage(), e);
            }
        }
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        releaseLock("ShutdownEvent");
    }

    @PreDestroy
    void preDestroy() {
        releaseLock("PreDestroy");
    }

    private void releaseLock(String trigger) {
        // Only the instance that acquired should clear statics; other reload
        // instances just no-op. Synchronize to avoid races with concurrent onStart.
        synchronized (SingleInstanceGuard.class) {
            if (!acquiredByThisInstance) {
                return;
            }
            FileLock l = lockHolder;
            FileChannel ch = channelHolder;
            RandomAccessFile raf = rafHolder;

            // Clear statics first so a concurrent reload's onStart can re-acquire
            lockHolder = null;
            channelHolder = null;
            rafHolder = null;
            lockPathHolder = lockPathHolder; // keep path for logging; not cleared
            acquiredByThisInstance = false;

            if (l != null) {
                try {
                    if (l.isValid()) l.release();
                } catch (IOException e) {
                    LOG.warn("Failed to release single-instance lock ({}): {}", trigger, e.getMessage(), e);
                }
            }
            if (ch != null) {
                try { ch.close(); } catch (IOException e) {
                    LOG.warn("Failed to close single-instance lock channel ({}): {}", trigger, e.getMessage(), e);
                }
            }
            if (raf != null) {
                try { raf.close(); } catch (IOException e) {
                    LOG.warn("Failed to close single-instance lock file ({}): {}", trigger, e.getMessage(), e);
                }
            }
            LOG.info("Single-instance lock released ({})", trigger);
        }
    }
}
