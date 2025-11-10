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

            // Disassociate songs from the playlist before deleting the playlist
            for (Song song : managed.getSongs()) {
                em.merge(song); // Persist the change to the song
            }
            managed.getSongs().clear(); // Clear the collection to remove join table entries

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
            em.flush();
        }
    }

    @Transactional
    public void clearAllPlaylistSongs() {
        em.createNativeQuery("DELETE FROM playlist_song").executeUpdate();
    }

    public record PaginatedPlaylistSongs(List<Song> songs, long totalCount) {}

    public PaginatedPlaylistSongs findSongsByPlaylist(Long playlistId, int page, int limit) {
        List<Song> songs = em.createQuery("SELECT s FROM Playlist p JOIN p.songs s WHERE p.id = :playlistId ORDER BY s.dateAdded DESC", Song.class)
                .setParameter("playlistId", playlistId)
                .setFirstResult((page - 1) * limit)
                .setMaxResults(limit)
                .getResultList();
        long totalCount = countSongsByPlaylist(playlistId);
        return new PaginatedPlaylistSongs(songs, totalCount);
    }

    public long countSongsByPlaylist(Long playlistId) {
        return em.createQuery("SELECT COUNT(s) FROM Playlist p JOIN p.songs s WHERE p.id = :playlistId", Long.class)
                .setParameter("playlistId", playlistId)
                .getSingleResult();
    }

    public Playlist findWithSongs(Long id) {
        try {
            return em.createQuery("SELECT p FROM Playlist p LEFT JOIN FETCH p.songs WHERE p.id = :id", Playlist.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    public List<Playlist> findAllWithSongStatus(Long songId) {
        List<Playlist> playlists = findAll();
        if (songId == null) {
            return playlists;
        }
        List<Long> playlistIdsWithSong = em.createQuery(
                "SELECT p.id FROM Playlist p JOIN p.songs s WHERE s.id = :songId", Long.class)
                .setParameter("songId", songId)
                .getResultList();

        for (Playlist p : playlists) {
            if (playlistIdsWithSong.contains(p.id)) {
                p.setContainsSong(true);
            }
        }
        return playlists;
    }
}
