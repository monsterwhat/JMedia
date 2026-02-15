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
            // All playlists are global by default - both user-created and imported
            playlist.setIsGlobal(true);
            em.persist(playlist);
        } else {
            Playlist existingPlaylist = em.find(Playlist.class, playlist.id);
            if (isMainProfileActive() || 
                (existingPlaylist != null && (
                    existingPlaylist.getProfile().equals(settingsService.getActiveProfile()) || 
                    Boolean.TRUE.equals(existingPlaylist.getIsGlobal())
                ))) {
                em.merge(playlist);
            }
        }
    }

    @Transactional
    public void delete(Playlist playlist) {
        if (playlist != null) {
            // Main profile can delete any playlist. Other profiles can only delete their own (not global).
            if (isMainProfileActive() || 
                (playlist.getProfile() != null && 
                 playlist.getProfile().equals(settingsService.getActiveProfile()) && 
                 !Boolean.TRUE.equals(playlist.getIsGlobal()))) {
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
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile != null && activeProfile.isPlaylistHidden(id)) {
            return null;
        }
        return playlist;
    }

    public Playlist findByIdRegardlessOfHidden(Long id) {
        return em.find(Playlist.class, id);
    }

    public List<Playlist> findAll() {
        try {
            Profile activeProfile = settingsService.getActiveProfile();
            if (activeProfile == null) return List.of();
            
            List<Playlist> playlists = em.createQuery("SELECT p FROM Playlist p WHERE p.profile = :profile OR p.isGlobal = true", Playlist.class)
                    .setParameter("profile", activeProfile)
                    .getResultList();
            
            List<Long> hiddenIds = activeProfile.getHiddenPlaylistIds();
            if (hiddenIds != null && !hiddenIds.isEmpty()) {
                playlists = playlists.stream()
                        .filter(p -> !hiddenIds.contains(p.id))
                        .toList();
            }
            
            return playlists;
        } catch (Exception e) {
            System.err.println("[ERROR] PlaylistService: Error in findAll: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error fetching all playlists", e);
        }
    }

    public List<Playlist> findAllForProfile(Profile profile) {
        try {
            if (profile == null) return List.of();
            
            List<Playlist> playlists = em.createQuery("SELECT p FROM Playlist p WHERE p.profile = :profile OR p.isGlobal = true", Playlist.class)
                    .setParameter("profile", profile)
                    .getResultList();
            
            List<Long> hiddenIds = profile.getHiddenPlaylistIds();
            if (hiddenIds != null && !hiddenIds.isEmpty()) {
                playlists = playlists.stream()
                        .filter(p -> !hiddenIds.contains(p.id))
                        .toList();
            }
            
            return playlists;
        } catch (Exception e) {
            System.err.println("[ERROR] PlaylistService: Error in findAllForProfile: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error fetching playlists for profile", e);
        }
    }

    @Transactional
    public void toggleSongInPlaylist(Long playlistId, Long songId) {
        Playlist playlist = find(playlistId); // find is access-aware
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
        
        System.err.println("DEBUG: findSongsByPlaylist called with playlistId=" + playlistId + ", playlist found=" + (playlist != null) + ", name=" + (playlist != null ? playlist.getName() : "null"));
        
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
            
            // Check access permissions
            Profile activeProfile = settingsService.getActiveProfile();
            if (isMainProfileActive() || 
                (playlist != null && (
                    (playlist.getProfile() != null && playlist.getProfile().equals(activeProfile)) || 
                    Boolean.TRUE.equals(playlist.getIsGlobal())
                ))) {
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

    public Playlist findByName(String name) {
        try {
            Profile activeProfile = settingsService.getActiveProfile();
            if (isMainProfileActive()) {
                return em.createQuery("SELECT p FROM Playlist p WHERE p.name = :name", Playlist.class)
                        .setParameter("name", name)
                        .setMaxResults(1)
                        .getSingleResult();
            } else {
                return em.createQuery("SELECT p FROM Playlist p WHERE p.name = :name AND (p.profile = :profile OR p.isGlobal = true)", Playlist.class)
                        .setParameter("name", name)
                        .setParameter("profile", activeProfile)
                        .setMaxResults(1)
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
                 int initialSize = managedPlaylist.getSongs().size();
            int addedCount =0;
            
                for (Song song : songs) {
                    if (!managedPlaylist.getSongs().contains(song)) {
                        managedPlaylist.getSongs().add(song);
                        addedCount++;
                    }
                }
                em.merge(managedPlaylist);
                em.flush(); // Force write to database
            
            // Verify the final size
            Playlist verifyPlaylist = find(playlist.id);
            int finalSize = verifyPlaylist.getSongs().size();
            
            System.err.println("DEBUG: Playlist '" + playlist.getName() + "' - Initial: " + initialSize + 
                ", Added: " + addedCount + ", Expected: " + (initialSize + addedCount) + ", Actual: " + finalSize);
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
            List<Playlist> playlists = findAll(); // This gets user's playlists + global playlists
            for (Playlist playlist : playlists) {
                // Only remove from user's own playlists, not global ones
                if (playlist.getProfile() != null && playlist.getProfile().equals(settingsService.getActiveProfile())) {
                    playlist.getSongs().removeIf(song -> song.id.equals(songId));
                    em.merge(playlist);
                }
            }
        }
    }

    /**
     * Replaces a song with another song in all playlists where the old song appears
     * This preserves playlist structure when duplicates are deleted
     */
    @Transactional
    public void replaceSongInAllPlaylists(Long oldSongId, Long newSongId) {
        if (oldSongId == null || newSongId == null) {
            return;
        }

        Song newSong = songService.find(newSongId);
        if (newSong == null) {
            return;
        }

        List<Playlist> affectedPlaylists = em.createQuery(
            "SELECT p FROM Playlist p JOIN p.songs s WHERE s.id = :songId", Playlist.class)
            .setParameter("songId", oldSongId)
            .getResultList();

        for (Playlist playlist : affectedPlaylists) {
            // Remove the old song
            playlist.getSongs().removeIf(s -> s.id.equals(oldSongId));
            // Add the new song if it's not already there
            if (!playlist.getSongs().contains(newSong)) {
                playlist.getSongs().add(newSong);
            }
            em.merge(playlist);
        }
    }
}
