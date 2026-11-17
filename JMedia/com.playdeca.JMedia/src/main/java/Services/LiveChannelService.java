package Services;

import Models.Video.LiveChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class LiveChannelService {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateStreamStatus(Long channelId, String status) {
        String previous = LiveChannel.find("select streamStatus from LiveChannel where id = ?1", channelId)
                .project(String.class)
                .firstResult();
        LiveChannel.update(
                "streamStatus = ?1, lastChecked = CURRENT_TIMESTAMP WHERE id = ?2",
                status, channelId
        );
        // Only a visibility change alters the advertised live category list; do not
        // invalidate on every probe of a channel that stays up or stays down.
        boolean wasVisible = !"dead".equals(previous);
        boolean nowVisible = !"dead".equals(status);
        if (wasVisible != nowVisible) {
            LiveGroupService.invalidateCategories();
        }
    }

    /**
     * Bumps lastChecked without touching streamStatus — used when a probe is
     * inconclusive so the previous verdict survives.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void touchLastChecked(Long channelId) {
        LiveChannel.update("lastChecked = CURRENT_TIMESTAMP WHERE id = ?1", channelId);
    }
}
