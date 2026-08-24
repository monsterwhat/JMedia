package Services;

import Models.Settings.Settings;
import Utils.PoolSizeResolver;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class StreamCheckExecutor {

    private static final int THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;

    @Inject
    SettingsService settingsService;

    private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();

    private int resolveThreads() {
        try {
            Settings s = settingsService.getSettingsOrNull();
            Integer configured = s != null ? s.getStreamCheckThreads() : null;
            return PoolSizeResolver.resolve(configured, autoSize());
        } catch (Exception e) {
            return autoSize();
        }
    }

    private static int autoSize() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    public boolean isDisabled() {
        return resolveThreads() <= 0;
    }

    public synchronized ExecutorService getExecutor() {
        ExecutorService p = executorRef.get();
        if (p == null) {
            int size = resolveThreads();
            if (size <= 0) {
                throw new IllegalStateException("Stream checking is disabled in system settings (streamCheckThreads)");
            }
            p = Executors.newFixedThreadPool(size, r -> {
                Thread t = new Thread(r, "StreamCheckWorker");
                t.setDaemon(true);
                t.setPriority(THREAD_PRIORITY);
                return t;
            });
            executorRef.set(p);
        }
        return p;
    }

    public void reconfigure() {
        ExecutorService old = executorRef.getAndSet(null);
        if (old != null) {
            old.shutdown();
            try {
                if (!old.awaitTermination(10, TimeUnit.SECONDS)) {
                    old.shutdownNow();
                }
            } catch (InterruptedException e) {
                old.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        reconfigure();
    }
}
