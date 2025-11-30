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
    ProfileService profileService;


    @Transactional
    public void add(Song song, Long profileId) {
        if (song == null) {
            return;
        }
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile with ID " + profileId + " not found.");
        }
        
        Song managedSong = em.merge(song);
        PlaybackHistory history = new PlaybackHistory();
        history.song = managedSong;
        history.playedAt = LocalDateTime.now();
        history.profile = profile;
        history.persist();
    }

    @Transactional
    public void clearHistory(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile with ID " + profileId + " not found.");
        }
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.profile = :profile")
                .setParameter("profile", profile)
                .executeUpdate();
    }

    @Transactional
    public void clearHistoryForAllProfiles() {
        em.createQuery("DELETE FROM PlaybackHistory").executeUpdate();
    }

    @Transactional
    public void deleteBySongId(Long songId, Long profileId) {
        if (songId == null) {
            return;
        }
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile with ID " + profileId + " not found.");
        }
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId AND ph.profile = :profile")
                .setParameter("songId", songId)
                .setParameter("profile", profile)
                .executeUpdate();
    }

    @Transactional
    public void deleteBySongIdForAllProfiles(Long songId) {
        if (songId == null) {
            return;
        }
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId")
                .setParameter("songId", songId)
                .executeUpdate();
    }

    public List<PlaybackHistory> getHistory(int page, int pageSize, Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return List.of();
        }
        return em.createQuery("SELECT ph FROM PlaybackHistory ph WHERE ph.profile = :profile ORDER BY ph.playedAt DESC", PlaybackHistory.class)
                .setParameter("profile", profile)
                .setFirstResult((page - 1) * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    public List<Long> getRecentlyPlayedSongIds(int count, Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return List.of();
        }
        return em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph WHERE ph.profile = :profile ORDER BY ph.playedAt DESC", Long.class)
                .setParameter("profile", profile)
                .setMaxResults(count)
                .getResultList();
    }
}
