package Controllers;

import Models.VideoState;
import Services.VideoStateService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class VideoPersistenceController {

    private static final long MIN_SAVE_INTERVAL_MS = 1000;
    private long lastSaveTime = 0;

    @Inject
    VideoStateService stateService;

    public VideoPersistenceController() {
    }

    @PreDestroy
    public void onShutdown() {
        System.out.println("[VideoPersistenceManager] Shutdown: forcing final persist...");
        // TODO: Ensure any pending state is saved on shutdown if necessary
        // This might involve getting the current VideoState and calling stateService.saveState(state)
        // However, the main VideoController will handle state changes, and
        // it's likely better to save there during shutdown events.
    }

    public VideoState loadState() {
        return stateService.getOrCreateState();
    }

    public synchronized void persist(VideoState state, boolean force) {
        long now = System.currentTimeMillis();

        // If not forced, apply throttling
        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return;
        }
        stateService.saveState(state);
        lastSaveTime = now;
    }

    public void maybePersist(VideoState state) {
        persist(state, false);
    }
}
