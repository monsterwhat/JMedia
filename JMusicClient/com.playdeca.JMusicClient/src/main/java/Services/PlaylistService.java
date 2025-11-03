package Services;

import Models.Playlist;
import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class PlaylistService {

    @PersistenceContext
    private EntityManager em;

    @Inject
    SongService songService;

    @Transactional
    public void save(Playlist playlist) {
        if (playlist.id == null || em.find(Playlist.class, playlist.id) == null) {
            em.persist(playlist);
        } else {
            em.merge(playlist);
        }
    }

    @Transactional
    public void delete(Playlist playlist) {
        if (playlist != null) {
            Playlist managed = em.contains(playlist) ? playlist : em.merge(playlist);
            em.remove(managed);
        }
    }

    public Playlist find(Long id) {
        return em.find(Playlist.class, id);
    }

    public List<Playlist> findAll() {
        return em.createQuery("SELECT p FROM Playlist p", Playlist.class)
                 .getResultList();
    }

    @Transactional
    public void toggleSongInPlaylist(Long playlistId, Long songId) {
        Playlist playlist = find(playlistId);
        Song song = songService.find(songId);

        if (playlist != null && song != null) {
            if (playlist.getSongs().contains(song)) {
                playlist.getSongs().remove(song);
            } else {
                playlist.getSongs().add(song);
            }
            em.merge(playlist);
        }
    }
}
