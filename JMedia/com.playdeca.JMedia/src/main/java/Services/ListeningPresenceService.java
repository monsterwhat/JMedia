package Services;

import API.WS.WebSocketManager;
import Controllers.PlaybackController;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ListeningPresenceService {

    private static final Logger LOG = LoggerFactory.getLogger(ListeningPresenceService.class);

    private static final long GRACE_MS = 15_000L;

    @Inject
    WebSocketManager webSocketManager;

    @Inject
    PlaybackController playbackController;

    private final Map<Long, Long> zeroSince = new ConcurrentHashMap<>();

    @Scheduled(every = "5s")
    public void enforceListenerPresence() {
        long now = System.currentTimeMillis();
        Set<Long> playing = playbackController.getPlayingProfileIds();
        for (Long profileId : playing) {
            int listeners = webSocketManager.getMusicSessionCount(profileId);
            if (listeners > 0) {
                zeroSince.remove(profileId);
                continue;
            }
            long since = zeroSince.computeIfAbsent(profileId, k -> now);
            if (now - since >= GRACE_MS) {
                zeroSince.remove(profileId);
                try {
                    playbackController.pausePlayback(profileId);
                    LOG.info("Auto-paused profile {} after {} ms with no music listeners", profileId, now - since);
                } catch (RuntimeException e) {
                    LOG.error("Failed to auto-pause profile " + profileId, e);
                }
            }
        }
        // forget profiles that stopped playing without listeners (avoid stale entries)
        zeroSince.keySet().removeIf(id -> !playbackController.getPlayingProfileIds().contains(id));
    }
}