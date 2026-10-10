package Services;

import Models.Video.LiveChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class LiveChannelService {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateStreamStatus(Long channelId, String status) {
        LiveChannel.update(
                "streamStatus = ?1, lastChecked = CURRENT_TIMESTAMP WHERE id = ?2",
                status, channelId
        );
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
