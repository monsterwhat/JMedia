package Controllers;

import Models.PlaybackState;
import Services.PlaybackStateService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PlaybackPersistenceController {

    private static final long MIN_SAVE_INTERVAL_MS = 1000;
    private long lastSaveTime = 0;

    @Inject
    PlaybackStateService stateService;

    public PlaybackPersistenceController() {
    }

    @PreDestroy
    public void onShutdown() {
        System.out.println("[PlaybackPersistenceManager] Shutdown: forcing final persist...");
    }

    public PlaybackState loadState(Long profileId) {
        return stateService.getOrCreateState(profileId);
    }

    public synchronized void persist(Long profileId, PlaybackState state, boolean force) {
        long now = System.currentTimeMillis();

        // If not forced, apply throttling
        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return;
        }
        stateService.saveState(profileId, state);
        lastSaveTime = now;
    }

    public void maybePersist(Long profileId, PlaybackState state) {
        persist(profileId, state, false);
    }
}
