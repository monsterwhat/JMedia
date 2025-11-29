package Services;

import Models.PlaybackHistory;
import Models.Profile;
import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class PlaybackHistoryService {

    @Inject
    EntityManager em;
    
    @Inject
    SettingsService settingsService;

    private boolean isMainProfileActive() {
        Profile activeProfile = settingsService.getActiveProfile();
        return activeProfile != null && activeProfile.isMainProfile;
    }

    @Transactional
    public void add(Song song) {
        if (song == null) {
            return;
        }
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return;
        }
        
        Song managedSong = em.merge(song);
        PlaybackHistory history = new PlaybackHistory();
        history.song = managedSong;
        history.playedAt = LocalDateTime.now();
        history.profile = activeProfile;
        history.persist();
    }

    @Transactional
    public void clearHistory() {
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM PlaybackHistory").executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.profile = :profile")
                    .setParameter("profile", activeProfile)
                    .executeUpdate();
        }
    }

    @Transactional
    public void deleteBySongId(Long songId) {
        if (songId == null) {
            return;
        }
        if (isMainProfileActive()) {
            em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId")
                    .setParameter("songId", songId)
                    .executeUpdate();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return;
            em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId AND ph.profile = :profile")
                    .setParameter("songId", songId)
                    .setParameter("profile", activeProfile)
                    .executeUpdate();
        }
    }

    public List<PlaybackHistory> getHistory(int page, int pageSize) {
        if (isMainProfileActive()) {
            return em.createQuery("SELECT ph FROM PlaybackHistory ph ORDER BY ph.playedAt DESC", PlaybackHistory.class)
                    .setFirstResult((page - 1) * pageSize)
                    .setMaxResults(pageSize)
                    .getResultList();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return List.of();
            return em.createQuery("SELECT ph FROM PlaybackHistory ph WHERE ph.profile = :profile ORDER BY ph.playedAt DESC", PlaybackHistory.class)
                    .setParameter("profile", activeProfile)
                    .setFirstResult((page - 1) * pageSize)
                    .setMaxResults(pageSize)
                    .getResultList();
        }
    }

    public List<Long> getRecentlyPlayedSongIds(int count) {
        if (isMainProfileActive()) {
            return em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph ORDER BY ph.playedAt DESC", Long.class)
                .setMaxResults(count)
                .getResultList();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return List.of();
            return em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph WHERE ph.profile = :profile ORDER BY ph.playedAt DESC", Long.class)
                    .setParameter("profile", activeProfile)
                    .setMaxResults(count)
                    .getResultList();
        }
    }
}
