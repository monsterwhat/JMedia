package Services;

import Models.Video.MediaFile;
import Models.Settings.Profile;
import Models.Video.VideoHistory;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoHistoryService {

    private static final Logger LOGGER = Logger.getLogger(VideoHistoryService.class.getName());

    @Inject
    @PersistenceUnit("video")
    EntityManager em;
    
    @Inject
    SettingsService settingsService;

    private boolean isMainProfileActive() {
        Profile activeProfile = settingsService.getActiveProfile();
        return activeProfile != null && activeProfile.isMainProfile;
    }

    @Transactional
    public void add(Long mediaFileId) {
        if (mediaFileId == null) {
            return;
        }
        MediaFile mediaFile = MediaFile.findById(mediaFileId);
        if (mediaFile == null) {
            return;
        }

        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return;
        }

        // Deduplication: check if there's a recent history record (within 5 minutes) for the same mediaFile and profile
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        VideoHistory existingHistory = VideoHistory.find(
            "mediaFile = ?1 AND profileId = ?2 AND playedAt >= ?3",
            mediaFile, activeProfile.id, fiveMinutesAgo
        ).firstResult();

        if (existingHistory != null) {
            // Update timestamp of existing record instead of creating new one
            existingHistory.playedAt = LocalDateTime.now();
            em.merge(existingHistory);
            return;
        }

        VideoHistory history = new VideoHistory();
        history.mediaFile = mediaFile;
        history.playedAt = LocalDateTime.now();
        history.profileId = activeProfile.id;
        history.persist();
    }

    /**
     * Records history using a Video entity ID
     */
    @Transactional
    public void addFromVideoId(Long videoId) {
        if (videoId == null) return;
        
        Models.Video.Video video = Models.Video.Video.findById(videoId);
        if (video == null) {
            LOGGER.warning("Cannot record history: Video not found for ID " + videoId);
            return;
        }
        if (video.path == null) {
            LOGGER.warning("Cannot record history: Video " + videoId + " has null path");
            return;
        }
        
        MediaFile mediaFile = MediaFile.find("path", video.path).firstResult();
        if (mediaFile != null) {
            add(mediaFile.id);
        } else {
            LOGGER.warning("Cannot record history: No MediaFile found for path '" + video.path + "' (video ID " + videoId + ")");
        }
    }

    @Transactional
    public void clearHistory() {
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM VideoHistory").executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.profileId = :profileId")
                    .setParameter("profileId", activeProfile.id)
                    .executeUpdate();
        }
    }

    @Transactional
    public void deleteByMediaFileId(Long mediaFileId) {
        if (mediaFileId == null) {
            return;
        }
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.mediaFile.id = :mediaFileId")
                    .setParameter("mediaFileId", mediaFileId)
                    .executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM VideoHistory vh WHERE vh.mediaFile.id = :mediaFileId AND vh.profileId = :profileId")
                    .setParameter("mediaFileId", mediaFileId)
                    .setParameter("profileId", activeProfile.id)
                    .executeUpdate();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<VideoHistory> getHistory(int page, int pageSize) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();
        return em.createQuery("SELECT vh FROM VideoHistory vh WHERE vh.profileId = :profileId ORDER BY vh.playedAt DESC", VideoHistory.class)
                .setParameter("profileId", activeProfile.id)
                .setFirstResult((page - 1) * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> getRecentlyPlayedVideoIds(int count) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();
        return em.createQuery("SELECT vh.mediaFile.id FROM VideoHistory vh WHERE vh.profileId = :profileId ORDER BY vh.playedAt DESC", Long.class)
                .setParameter("profileId", activeProfile.id)
                .setMaxResults(count)
                .getResultList();
    }

    // ==================== TRENDING ALGORITHM METHODS ====================
    
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> getTrendingVideoIds(int daysBack, int count) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();
        
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysBack);
        return em.createQuery(
            "SELECT vh.mediaFile.id " +
            "FROM VideoHistory vh " +
            "WHERE vh.profileId = :profileId AND vh.playedAt >= :cutoff " +
            "GROUP BY vh.mediaFile.id " +
            "ORDER BY COUNT(vh) DESC", Long.class)
                .setParameter("profileId", activeProfile.id)
                .setParameter("cutoff", cutoff)
                .setMaxResults(count)
                .getResultList();
    }
    
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<Long, Integer> getPlayCountsForVideos(List<Long> videoIds, int daysBack) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return Map.of();
        
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysBack);
        List<Object[]> results = em.createQuery(
            "SELECT vh.mediaFile.id, COUNT(vh) as playCount " +
            "FROM VideoHistory vh " +
            "WHERE vh.profileId = :profileId AND vh.playedAt >= :cutoff AND vh.mediaFile.id IN :videoIds " +
            "GROUP BY vh.mediaFile.id", Object[].class)
                .setParameter("profileId", activeProfile.id)
                .setParameter("cutoff", cutoff)
                .setParameter("videoIds", videoIds)
                .getResultList();
        
        return results.stream()
                .collect(Collectors.toMap(
                    row -> (Long) row[0],
                    row -> ((Number) row[1]).intValue()
                ));
    }
    
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<VideoHistory> getAllHistory(int page, int pageSize) {
        return em.createQuery("SELECT vh FROM VideoHistory vh ORDER BY vh.playedAt DESC", VideoHistory.class)
                .setFirstResult((page - 1) * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }
}
