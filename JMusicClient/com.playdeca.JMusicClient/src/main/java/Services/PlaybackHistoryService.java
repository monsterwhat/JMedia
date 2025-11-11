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
import jakarta.persistence.PersistenceContext;

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
        // PlaybackHistory.deleteAll(); // Panache method, doesn't need EntityManager directly
        // However, if deleteAll() is causing issues, we can use EntityManager
        EntityManager em = emProvider.get();
        em.createQuery("DELETE FROM PlaybackHistory").executeUpdate();
    }

    public List<Long> getRecentlyPlayedSongIds(int limit) {
        EntityManager em = emProvider.get();
        List<Long> allPlayedIds = em.createQuery("SELECT ph.song.id FROM PlaybackHistory ph ORDER BY ph.playedAt DESC", Long.class)
                .getResultList();

        return allPlayedIds.stream()
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }
}