package Services;

import Models.Playlist;
import Models.Profile;
import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

@ApplicationScoped
public class PlaylistService {

    @PersistenceContext
    private EntityManager em;

    @Inject
    SongService songService;

    @Inject
    SettingsService settingsService;

    private boolean isMainProfileActive() {
        Profile activeProfile = settingsService.getActiveProfile();
        return activeProfile != null && activeProfile.isMainProfile;
    }

    @Transactional
    public void save(Playlist playlist) {
        if (playlist.id == null) { // New playlist
            Profile activeProfile = settingsService.getActiveProfile();
            playlist.setProfile(activeProfile);
            em.persist(playlist);
        } else {
            Playlist existingPlaylist = em.find(Playlist.class, playlist.id);
            if (isMainProfileActive() || (existingPlaylist != null && existingPlaylist.getProfile().equals(settingsService.getActiveProfile()))) {
                em.merge(playlist);
            }
        }
    }

    @Transactional
    public void delete(Playlist playlist) {
        if (playlist != null) {
            // Main profile can delete any playlist. Other profiles can only delete their own.
            if (isMainProfileActive() || (playlist.getProfile() != null && playlist.getProfile().equals(settingsService.getActiveProfile()))) {
                Playlist managed = em.contains(playlist) ? playlist : em.merge(playlist);

                for (Song song : managed.getSongs()) {
                    em.merge(song);
                }
                managed.getSongs().clear();

                em.remove(managed);
            }
        }
    }

    public Playlist find(Long id) {
        Playlist playlist = em.find(Playlist.class, id);
        // Main profile can find any playlist. Others can only find their own.
        if (isMainProfileActive() || (playlist != null && playlist.getProfile() != null && playlist.getProfile().equals(settingsService.getActiveProfile()))) {
            return playlist;
        }
        return null;
    }

    public List<Playlist> findAll() {
        try {
            if (isMainProfileActive()) {
                return em.createQuery("SELECT p FROM Playlist p", Playlist.class).getResultList();
            } else {
                Profile activeProfile = settingsService.getActiveProfile();
                if (activeProfile == null) return List.of();
                return em.createQuery("SELECT p FROM Playlist p WHERE p.profile = :profile", Playlist.class)
                        .setParameter("profile", activeProfile)
                        .getResultList();
            }
        } catch (Exception e) {
            System.err.println("[ERROR] PlaylistService: Error in findAll: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error fetching all playlists", e);
        }
    }

    @Transactional
    public void toggleSongInPlaylist(Long playlistId, Long songId) {
        Playlist playlist = find(playlistId); // find is now access-aware
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
        // Only main profile can do this.
        if (isMainProfileActive()) {
            em.createNativeQuery("DELETE FROM playlist_song").executeUpdate();
        }
    }
    
    public record PaginatedPlaylistSongs(List<Song> songs, long totalCount) {

    }

    public PaginatedPlaylistSongs findSongsByPlaylist(Long playlistId, int page, int limit, String search, String sortBy, String sortDirection) {
        Playlist playlist = find(playlistId); // find is access-aware
        if (playlist == null) {
            return new PaginatedPlaylistSongs(List.of(), 0);
        }
        
        // The rest of the logic remains the same as the playlist is already verified.
        StringBuilder baseQuery = new StringBuilder("SELECT s FROM Playlist p JOIN p.songs s WHERE p.id = :playlistId");

        if (search != null && !search.isBlank()) {
            baseQuery.append(" AND (LOWER(s.title) LIKE :search OR LOWER(s.artist) LIKE :search OR LOWER(s.album) LIKE :search OR LOWER(s.albumArtist) LIKE :search OR LOWER(s.genre) LIKE :search)");
        }

        baseQuery.append(" ORDER BY ");
        switch (sortBy) {
            case "title": baseQuery.append("s.title"); break;
            case "artist": baseQuery.append("s.artist"); break;
            case "duration": baseQuery.append("s.durationSeconds"); break;
            case "dateAdded": default: baseQuery.append("s.dateAdded"); break;
        }

        if ("desc".equalsIgnoreCase(sortDirection)) {
            baseQuery.append(" DESC");
        } else {
            baseQuery.append(" ASC");
        }

        jakarta.persistence.TypedQuery<Song> songsQuery = em.createQuery(baseQuery.toString(), Song.class)
                .setParameter("playlistId", playlistId)
                .setFirstResult((page - 1) * limit)
                .setMaxResults(limit);

        if (search != null && !search.isBlank()) {
            songsQuery.setParameter("search", "%" + search.toLowerCase() + "%");
        }

        List<Song> songs = songsQuery.getResultList();
        long totalCount = countSongsByPlaylist(playlistId, search);
        return new PaginatedPlaylistSongs(songs, totalCount);
    }

    public long countSongsByPlaylist(Long playlistId, String search) {
        Playlist playlist = find(playlistId); // find is access-aware
        if (playlist == null) {
            return 0;
        }
        
        StringBuilder countQuery = new StringBuilder("SELECT COUNT(s) FROM Playlist p JOIN p.songs s WHERE p.id = :playlistId");

        if (search != null && !search.isBlank()) {
            countQuery.append(" AND (LOWER(s.title) LIKE :search OR LOWER(s.artist) LIKE :search OR LOWER(s.album) LIKE :search OR LOWER(s.albumArtist) LIKE :search OR LOWER(s.genre) LIKE :search)");
        }

        jakarta.persistence.TypedQuery<Long> query = em.createQuery(countQuery.toString(), Long.class)
                .setParameter("playlistId", playlistId);

        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search.toLowerCase() + "%");
        }
        return query.getSingleResult();
    }

    public Playlist findWithSongs(Long id) {
        try {
            Playlist playlist = em.createQuery("SELECT p FROM Playlist p LEFT JOIN FETCH p.songs WHERE p.id = :id", Playlist.class)
                    .setParameter("id", id)
                    .getSingleResult();
            if (isMainProfileActive() || (playlist != null && playlist.getProfile() != null && playlist.getProfile().equals(settingsService.getActiveProfile()))) {
                return playlist;
            }
            return null;
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    public List<Playlist> findAllWithSongStatus(Long songId) {
        List<Playlist> playlists = findAll(); // findAll is now access-aware
        if (songId == null) {
            return playlists;
        }
        
        List<Long> playlistIdsWithSong;
        if(isMainProfileActive()){
            playlistIdsWithSong = em.createQuery(
                "SELECT p.id FROM Playlist p JOIN p.songs s WHERE s.id = :songId", Long.class)
                .setParameter("songId", songId)
                .getResultList();
        } else {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return playlists;
            playlistIdsWithSong = em.createQuery(
                    "SELECT p.id FROM Playlist p JOIN p.songs s WHERE s.id = :songId AND p.profile = :profile", Long.class)
                    .setParameter("songId", songId)
                    .setParameter("profile", activeProfile)
                    .getResultList();
        }

        for (Playlist p : playlists) {
            if (playlistIdsWithSong.contains(p.id)) {
                p.setContainsSong(true);
            }
        }
        return playlists;
    }

    public Playlist findByName(String name) {
        try {
            if (isMainProfileActive()) {
                return em.createQuery("SELECT p FROM Playlist p WHERE p.name = :name", Playlist.class)
                        .setParameter("name", name)
                        .setMaxResults(1)
                        .getSingleResult();
            } else {
                Profile activeProfile = settingsService.getActiveProfile();
                 if (activeProfile == null) return null;
                return em.createQuery("SELECT p FROM Playlist p WHERE p.name = :name AND p.profile = :profile", Playlist.class)
                        .setParameter("name", name)
                        .setParameter("profile", activeProfile)
                        .getSingleResult();
            }
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    @Transactional
    public Playlist findOrCreatePlaylist(String name) {
        if (name == null || name.trim().isEmpty() || "null".equalsIgnoreCase(name.trim())) {
            return null;
        }
        Playlist playlist = findByName(name); // findByName is now access-aware
        if (playlist == null) {
            playlist = new Playlist();
            playlist.setName(name);
            save(playlist); // save will set the profile
        }
        return playlist;
    }

    @Transactional(TxType.REQUIRES_NEW)
    public Playlist findOrCreatePlaylistInNewTx(String name) {
        return findOrCreatePlaylist(name);
    }

    @Transactional
    public void addSongsToPlaylist(Playlist playlist, List<Song> songs) {
        if (playlist != null && songs != null && !songs.isEmpty()) {
            Playlist managedPlaylist = find(playlist.id); // find is access-aware
            if(managedPlaylist != null) {
                for (Song song : songs) {
                    if (!managedPlaylist.getSongs().contains(song)) {
                        managedPlaylist.getSongs().add(song);
                    }
                }
                em.merge(managedPlaylist);
            }
        }
    }

    @Transactional
    public void removeSongFromAllPlaylists(Long songId) {
        if (songId == null) {
            return;
        }
        
        if (isMainProfileActive()) {
            em.createNativeQuery("DELETE FROM playlist_song WHERE song_id = :songId")
                .setParameter("songId", songId)
                .executeUpdate();
        } else {
            List<Playlist> playlists = findAll(); // This is just the current profile's playlists
            for (Playlist playlist : playlists) {
                playlist.getSongs().removeIf(song -> song.id.equals(songId));
                em.merge(playlist);
            }
        }
    }
}
