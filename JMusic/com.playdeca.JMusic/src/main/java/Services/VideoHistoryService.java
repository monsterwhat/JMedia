package Services;

import Models.Profile;
import Models.Video;
import Models.VideoHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class VideoHistoryService {

    @Inject
    EntityManager em;
    
    @Inject
    SettingsService settingsService;

    private boolean isMainProfileActive() {
        Profile activeProfile = settingsService.getActiveProfile();
        return activeProfile != null && activeProfile.isMainProfile;
    }

    @Transactional
    public void add(Video video) {
        if (video == null) {
            return;
        }
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return;
        }
        
        Video managedVideo = em.merge(video);
        VideoHistory history = new VideoHistory();
        history.video = managedVideo;
        history.playedAt = LocalDateTime.now();
        history.profile = activeProfile;
        history.persist();
    }

    @Transactional
    public void clearHistory() {
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM VideoHistory").executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.profile = :profile")
                    .setParameter("profile", activeProfile)
                    .executeUpdate();
        }
    }

    @Transactional
    public void deleteByVideoId(Long videoId) {
        if (videoId == null) {
            return;
        }
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.video.id = :videoId")
                    .setParameter("videoId", videoId)
                    .executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.video.id = :videoId AND vh.profile = :profile")
                    .setParameter("videoId", videoId)
                    .setParameter("profile", activeProfile)
                    .executeUpdate();
        }
    }

    public List<VideoHistory> getHistory(int page, int pageSize) {
        if (isMainProfileActive()) {
            return em.createQuery("SELECT vh FROM VideoHistory vh ORDER BY vh.playedAt DESC", VideoHistory.class)
                    .setFirstResult((page - 1) * pageSize)
                    .setMaxResults(pageSize)
                    .getResultList();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return List.of();
            return em.createQuery("SELECT vh FROM VideoHistory vh WHERE vh.profile = :profile ORDER BY vh.playedAt DESC", VideoHistory.class)
                    .setParameter("profile", activeProfile)
                    .setFirstResult((page - 1) * pageSize)
                    .setMaxResults(pageSize)
                    .getResultList();
        }
    }

    public List<Long> getRecentlyPlayedVideoIds(int count) {
        if (isMainProfileActive()) {
            return em.createQuery("SELECT vh.video.id FROM VideoHistory vh ORDER BY vh.playedAt DESC", Long.class)
                .setMaxResults(count)
                .getResultList();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return List.of();
            return em.createQuery("SELECT vh.video.id FROM VideoHistory vh WHERE vh.profile = :profile ORDER BY vh.playedAt DESC", Long.class)
                    .setParameter("profile", activeProfile)
                    .setMaxResults(count)
                    .getResultList();
        }
    }
}
