package Services;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class VideoScanExecutor {

    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "VideoScanExecutor");
        t.setDaemon(true);
        return t;
    });

    public void submit(Runnable task) {
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
