package Services;

import Models.Music.PlaybackHistory;
import Models.Settings.Profile;
import Models.Music.Song;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class PlaybackHistoryService {

    @Inject
    @PersistenceUnit("music")
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

        // Deduplication: check if there's a recent history record (within 5 minutes) for the same song and profile
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        PlaybackHistory existingHistory = PlaybackHistory.find(
            "song = ?1 AND profileId = ?2 AND playedAt >= ?3",
            song, profileId, fiveMinutesAgo
        ).firstResult();

        if (existingHistory != null) {
            // Update timestamp of existing record instead of creating new one
            existingHistory.playedAt = LocalDateTime.now();
            em.merge(existingHistory);
            return;
        }

        Song managedSong = em.merge(song);
        PlaybackHistory history = new PlaybackHistory();
        history.song = managedSong;
        history.playedAt = LocalDateTime.now();
        history.profileId = profileId;
        history.persist();
    }

    @Transactional
    public void clearHistory(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile with ID " + profileId + " not found.");
        }
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.profileId = :profileId")
                .setParameter("profileId", profileId)
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
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId AND ph.profileId = :profileId")
                .setParameter("songId", songId)
                .setParameter("profileId", profileId)
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<PlaybackHistory> getHistory(int page, int pageSize, Long profileId) {
        return getHistory(page, pageSize, profileId, "");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<PlaybackHistory> getHistory(int page, int pageSize, Long profileId, String search) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return List.of();
        }
        
        String query = "SELECT ph FROM PlaybackHistory ph WHERE ph.profileId = :profileId";
        if (search != null && !search.isBlank()) {
            query += " AND (LOWER(ph.song.title) LIKE LOWER(:search) OR LOWER(ph.song.artist) LIKE LOWER(:search))";
        }
        query += " ORDER BY ph.playedAt DESC";
        
        var q = em.createQuery(query, PlaybackHistory.class)
                .setParameter("profileId", profileId);
        
        if (search != null && !search.isBlank()) {
            q.setParameter("search", "%" + search + "%");
        }
        
        return q.setFirstResult((page - 1) * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Long> getRecentlyPlayedSongIds(int count, Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return List.of();
        }
        return em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph WHERE ph.profileId = :profileId ORDER BY ph.playedAt DESC", Long.class)
                .setParameter("profileId", profileId)
                .setMaxResults(count)
                .getResultList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long getHistoryCount(Long profileId) {
        return getHistoryCount(profileId, "");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long getHistoryCount(Long profileId, String search) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return 0;
        }
        
        String query = "SELECT COUNT(ph) FROM PlaybackHistory ph WHERE ph.profileId = :profileId";
        if (search != null && !search.isBlank()) {
            query += " AND (LOWER(ph.song.title) LIKE LOWER(:search) OR LOWER(ph.song.artist) LIKE LOWER(:search))";
        }
        
        var q = em.createQuery(query, Long.class)
                .setParameter("profileId", profileId);
        
        if (search != null && !search.isBlank()) {
            q.setParameter("search", "%" + search + "%");
        }
        
        return q.getSingleResult();
    }

    /**
     * Replaces a song reference with another song in playback history
     * This preserves history when duplicates are deleted
     */
    @Transactional
    public void replaceSongInHistory(Long oldSongId, Long newSongId) {
        if (oldSongId == null || newSongId == null) {
            return;
        }

        Song newSong = em.find(Song.class, newSongId);
        if (newSong == null) {
            return;
        }

        List<PlaybackHistory> historyEntries = em.createQuery(
            "SELECT ph FROM PlaybackHistory ph WHERE ph.song.id = :oldSongId", PlaybackHistory.class)
            .setParameter("oldSongId", oldSongId)
            .getResultList();

        for (PlaybackHistory history : historyEntries) {
            history.song = newSong;
            em.merge(history);
        }
    }
}
