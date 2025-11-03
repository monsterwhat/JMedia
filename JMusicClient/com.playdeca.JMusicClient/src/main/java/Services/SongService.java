package Services;

import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class SongService {

    @PersistenceContext
    private EntityManager em;

    @Inject
    PlaylistService playlistService;

    @Transactional
    public void save(Song song) {
        if (song.id == null || em.find(Song.class, song.id) == null) {
            em.persist(song);
        } else {
            em.merge(song);
        }
    }

    @Transactional
    public void clearAllSongs() {
        playlistService.clearAllPlaylistSongs();
        em.createQuery("DELETE FROM Song").executeUpdate();
    }

    @Transactional
    public void delete(Song song) {
        if (song != null) {
            Song managed = em.contains(song) ? song : em.merge(song);
            em.remove(managed);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Song findSongInNewTx(Long id) {
        return em.find(Song.class, id);
    }

    public Song find(Long id) {
        return em.find(Song.class, id);
    }

    @Transactional
    public List<Song> findAll() {
        return em.createQuery("SELECT s FROM Song s", Song.class)
                .getResultList();
    }

    public Song findByPath(String path) {
        try {
            return em.createQuery("SELECT s FROM Song s WHERE s.path = :path", Song.class)
                    .setParameter("path", path)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void persistSongInNewTx(Song song) {
        if (song.id == null || em.find(Song.class, song.id) == null) {
            em.persist(song);
        } else {
            em.merge(song);
        }
    }
}
