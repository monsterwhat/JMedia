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
    private PlaybackState lastPersistedState = null; // Keep track of the last state we actually persisted

    @Inject
    PlaybackStateService stateService;

    public PlaybackPersistenceController() {
        // For CDI
    }

    @PreDestroy
    public void onShutdown() {
        System.out.println("[PlaybackPersistenceManager] Shutdown: forcing final persist...");
        // Ensure that the current in-memory state (if any) is persisted
        // This assumes the PlaybackController will call persist with its current state before shutdown
        // Or, we could potentially hold a reference to the current state if needed.
        // For now, rely on the PlaybackController's shutdown hook to call persist(true)
    }

    public PlaybackState loadState() {
        return stateService.getOrCreateState();
    }

    public synchronized void persist(PlaybackState state, boolean force) {
        long now = System.currentTimeMillis();

        // If not forced, apply throttling
        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return;
        }

        // Avoid persisting if the state object reference hasn't changed and it's not a forced save
        // A more robust check would be a deep comparison, but for now, this prevents redundant saves
        // if the PlaybackController passes the same object without modifications.
        if (!force && state == lastPersistedState) {
            return;
        }

        System.out.println("[PlaybackPersistenceManager] Persisting state.");
        stateService.saveState(state);
        lastPersistedState = state; // Store the reference to the last persisted state
        lastSaveTime = now;
    }

    public void maybePersist(PlaybackState state) {
        persist(state, false);
    }
}
