package Controllers;

import Models.ProfileSessionState;
import Services.ProfileSessionStateService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class VideoPersistenceController {

    private static final long MIN_SAVE_INTERVAL_MS = 1000;
    private long lastSaveTime = 0;

    @Inject
    ProfileSessionStateService stateService;

    public VideoPersistenceController() {
    }

    @PreDestroy
    public void onShutdown() {
        System.out.println("[VideoPersistenceManager] Shutdown: forcing final persist...");
    }

    public ProfileSessionState loadState() {
        return stateService.getOrCreate();
    }

    public synchronized void persist(ProfileSessionState state, boolean force) {
        long now = System.currentTimeMillis();

        // If not forced, apply throttling
        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return;
        }
        stateService.save(state);
        lastSaveTime = now;
    }

    public void maybePersist(ProfileSessionState state) {
        persist(state, false);
    }
}
