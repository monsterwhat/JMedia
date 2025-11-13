package Services;

import Models.PlaybackHistory;
import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class PlaybackHistoryService {

    @Inject
    Provider<EntityManager> emProvider;

    @Transactional
    public void add(Song song) {
        if (song == null) {
            return; // Do not add to history if song is null
        }
        // Ensure the song is a managed entity within the current persistence context
        EntityManager em = emProvider.get();
        Song managedSong = em.merge(song);
        PlaybackHistory history = new PlaybackHistory();
        history.song = managedSong;
        history.playedAt = LocalDateTime.now();
        history.persist();
    } 

    @Transactional
    public void clearHistory() {
        EntityManager em = emProvider.get();
        em.createQuery("DELETE FROM PlaybackHistory").executeUpdate();
    }

    @Transactional
    public void deleteBySongId(Long songId) {
        if (songId == null) {
            return;
        }
        EntityManager em = emProvider.get();
        em.createQuery("DELETE FROM PlaybackHistory ph WHERE ph.song.id = :songId")
                .setParameter("songId", songId)
                .executeUpdate();
    }

    public List<Long> getRecentlyPlayedSongIds(int count) {
        EntityManager em = emProvider.get();
        return em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph ORDER BY ph.playedAt DESC", Long.class)
                .setMaxResults(count)
                .getResultList();
    }
}
