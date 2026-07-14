package Services;

import Models.LiveChannel;
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
}
