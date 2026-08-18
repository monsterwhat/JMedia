package Controllers;

import Models.Music.PlaybackState;
import Services.PlaybackStateService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PlaybackPersistenceController {

    private static final long MIN_SAVE_INTERVAL_MS = 1000;
    private final Map<Long, Long> lastSaveTime = new ConcurrentHashMap<>();

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
        Long lastSave = lastSaveTime.getOrDefault(profileId, 0L);
        if (!force && now - lastSave < MIN_SAVE_INTERVAL_MS) {
            return;
        }
        stateService.saveState(profileId, state);
        lastSaveTime.put(profileId, now);
    }

    public void maybePersist(Long profileId, PlaybackState state) {
        persist(profileId, state, false);
    }
}
