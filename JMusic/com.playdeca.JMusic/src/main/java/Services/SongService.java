package Services;

import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class SongService {

    private static final Logger LOGGER = Logger.getLogger(SongService.class.getName());

    @PersistenceContext
    private EntityManager em;

    @Inject
    PlaylistService playlistService;

    @Inject
    PlaybackHistoryService playbackHistoryService;

    @Inject
    ImportService importService; // Inject ImportService

    @Inject
    SettingsService settingsService; // Inject SettingsService

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
            // First, delete all associated playback history entries across all profiles
            playbackHistoryService.deleteBySongIdForAllProfiles(song.id);
            // Then, remove the song from all playlists
            playlistService.removeSongFromAllPlaylists(song.id);
            
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
    public Song persistSongInNewTx(Song song) {
        if (song.id == null || em.find(Song.class, song.id) == null) {
            em.persist(song);
        } else {
            em.merge(song);
        }
        return song;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Song findByPathInNewTx(String path) {
        try {
            return em.createQuery("SELECT s FROM Song s WHERE s.path = :path", Song.class)
                    .setParameter("path", path)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    public record PaginatedSongs(List<Song> songs, long totalCount) {

    }

    public PaginatedSongs findAll(int page, int limit, String search, String sortBy, String sortDirection) {
        String baseQuery = "SELECT s FROM Song s";
        String whereClause = "";

        if (search != null && !search.isBlank()) {
            whereClause = " WHERE LOWER(s.title) LIKE :search OR "
                    + "LOWER(s.artist) LIKE :search OR "
                    + "LOWER(s.album) LIKE :search OR "
                    + "LOWER(s.albumArtist) LIKE :search OR "
                    + "LOWER(s.genre) LIKE :search";
        }

        // Build the ORDER BY clause dynamically
        String orderByClause = " ORDER BY ";
        switch (sortBy) {
            case "title":
                orderByClause += "s.title";
                break;
            case "artist":
                orderByClause += "s.artist";
                break;
            case "duration":
                orderByClause += "s.durationSeconds";
                break;
            case "dateAdded":
            default:
                orderByClause += "s.dateAdded";
                break;
        }

        if ("desc".equalsIgnoreCase(sortDirection)) {
            orderByClause += " DESC";
        } else {
            orderByClause += " ASC";
        }

        jakarta.persistence.TypedQuery<Song> query = em.createQuery(baseQuery + whereClause + orderByClause, Song.class);

        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search.toLowerCase() + "%");
        }

        List<Song> songs = query
                .setFirstResult((page - 1) * limit)
                .setMaxResults(limit)
                .getResultList();
        long totalCount = countAll(search);
        return new PaginatedSongs(songs, totalCount);
    }

    public long countAll(String search) {
        String countQuery = "SELECT COUNT(s) FROM Song s";
        String whereClause = "";

        if (search != null && !search.isBlank()) {
            whereClause = " WHERE LOWER(s.title) LIKE :search OR "
                    + "LOWER(s.artist) LIKE :search OR "
                    + "LOWER(s.album) LIKE :search OR "
                    + "LOWER(s.albumArtist) LIKE :search OR "
                    + "LOWER(s.genre) LIKE :search";
        }

        jakarta.persistence.TypedQuery<Long> query = em.createQuery(countQuery + whereClause, Long.class);
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search.toLowerCase() + "%");
        }
        return query.getSingleResult();
    }

    public List<Song> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<Song> unorderedSongs = em.createQuery("SELECT s FROM Song s WHERE s.id IN :ids", Song.class)
                .setParameter("ids", ids)
                .getResultList();
        // Re-order based on the original ID list
        java.util.Map<Long, Song> songMap = unorderedSongs.stream().collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s));
        return ids.stream().map(songMap::get).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public Song findByTitleAndArtist(String title, String artist) {
        try {
            String trimmedTitle = title != null ? title.trim() : "";
            String trimmedArtist = artist != null ? artist.trim() : "";

            StringBuilder queryStrBuilder = new StringBuilder("SELECT s FROM Song s WHERE ");
            
            // Prioritize artist match
            queryStrBuilder.append("LOWER(s.artist) LIKE LOWER(:artistPattern)");
            queryStrBuilder.append(" AND LOWER(s.title) LIKE LOWER(:titlePattern)");
             
            jakarta.persistence.TypedQuery<Song> query = em.createQuery(queryStrBuilder.toString(), Song.class)
                    .setParameter("artistPattern", "%" + trimmedArtist + "%")
                    .setParameter("titlePattern", "%" + trimmedTitle + "%");

            List<Song> results = query.getResultList();
 
            if (results.isEmpty()) {
                return null;
            } else if (results.size() > 1) {
                // Log a warning if multiple songs match, but return the first one
                // This might indicate data duplication in the database
                System.out.println("WARNING: Multiple songs found for title '" + trimmedTitle + "' and artist '" + trimmedArtist + "'. Returning the first one.");
                return results.get(0);
            } else {
                return results.get(0);
            }
        } catch (Exception e) {
            // Catch any other potential exceptions during query execution
            System.err.println("Error in findByTitleAndArtist for title '" + title + "' and artist '" + artist + "': " + e.getMessage());
            return null;
        }
    }

    @Transactional
    public Song findByTitleArtistAndDuration(String title, String artist, int durationSeconds) {
        try {
            String trimmedTitle = title != null ? title.trim() : "";
            String trimmedArtist = artist != null ? artist.trim() : "";

            StringBuilder queryStrBuilder = new StringBuilder("SELECT s FROM Song s WHERE ");
            queryStrBuilder.append("LOWER(s.artist) LIKE LOWER(:artistPattern)");
            queryStrBuilder.append(" AND LOWER(s.title) LIKE LOWER(:titlePattern)");
            queryStrBuilder.append(" AND s.durationSeconds = :durationSeconds");

            jakarta.persistence.TypedQuery<Song> query = em.createQuery(queryStrBuilder.toString(), Song.class)
                    .setParameter("artistPattern", "%" + trimmedArtist + "%")
                    .setParameter("titlePattern", "%" + trimmedTitle + "%")
                    .setParameter("durationSeconds", durationSeconds);

            List<Song> results = query.getResultList();
            if (results.isEmpty()) {
                return null;
            } else if (results.size() > 1) {
                System.out.println("WARNING: Multiple songs found for title '" + trimmedTitle + "', artist '" + trimmedArtist + "' and duration '" + durationSeconds + "'. Returning the first one.");
                return results.get(0);
            } else {
                return results.get(0);
            }
        } catch (Exception e) {
            System.err.println("Error in findByTitleArtistAndDuration for title '" + title + "', artist '" + artist + "' and duration '" + durationSeconds + "': " + e.getMessage());
            return null;
        }
    }

    @Transactional
    public List<Song> findByRelativePaths(java.util.Set<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return em.createQuery("SELECT s FROM Song s WHERE s.path IN :paths", Song.class)
                .setParameter("paths", relativePaths)
                .getResultList();
    }

    public Song findRandomSongByGenre(String genre, Long excludeSongId, List<Long> songPoolIds) {

        if (genre == null || genre.isBlank() || songPoolIds == null || songPoolIds.isEmpty()) {
            return null;
        }
        List<Long> matchingIds = em.createQuery(
                "SELECT s.id FROM Song s WHERE s.id IN :songPoolIds AND LOWER(s.genre) = :genre AND s.id != :excludeSongId", Long.class)
                .setParameter("songPoolIds", songPoolIds)
                .setParameter("genre", genre.toLowerCase())
                .setParameter("excludeSongId", excludeSongId)
                .getResultList();
        if (matchingIds.isEmpty()) {
            return null;
        }
        java.util.Collections.shuffle(matchingIds);
        Long randomId = matchingIds.get(0);
        return find(randomId); // This fetches the single, full Song object
    }

    @Transactional
    public List<Song> findSongsAddedAfter(java.time.LocalDateTime dateTime) {
        return em.createQuery("SELECT s FROM Song s WHERE s.dateAdded > :dateTime", Song.class)
                .setParameter("dateTime", dateTime)
                .getResultList();
    }

    @Transactional
    public void rescanSong(Long id) {
        Song song = find(id);
        if (song == null) {
            throw new NotFoundException("Song with ID " + id + " not found.");
        }

        // Get the absolute path to the song file
        String libraryPath = settingsService.getOrCreateSettings().getLibraryPath();
        if (libraryPath == null || libraryPath.isBlank()) {
            LOGGER.log(Level.WARNING, "Music library path is not configured. Cannot rescan song: {0}", song.getPath());
            throw new IllegalStateException("Music library path is not configured.");
        }
        Path absolutePath = Paths.get(libraryPath, song.getPath());
        File songFile = absolutePath.toFile();

        if (!songFile.exists()) {
            LOGGER.log(Level.WARNING, "Song file not found for rescan: {0}", absolutePath);
            throw new NotFoundException("Song file not found at " + absolutePath);
        }

        try {
            // Re-extract metadata and update the existing song entity
            Song updatedSong = importService.extractMetadata(songFile, song.getPath());
            if (updatedSong != null) {
                // Preserve the original ID and dateAdded
                updatedSong.id = song.id;
                em.merge(updatedSong);
                LOGGER.log(Level.INFO, "Successfully rescanned and updated song: {0}", song.getTitle());
            } else {
                LOGGER.log(Level.WARNING, "Failed to extract metadata during rescan for song: {0}", song.getPath());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during song rescan for " + song.getPath(), e);
            throw new RuntimeException("Failed to rescan song: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteSong(Long id) {
        Song song = find(id);
        if (song == null) {
            throw new NotFoundException("Song with ID " + id + " not found.");
        }

        // Delete from database (this also handles playlists and history via the existing delete method)
        delete(song);

        // Delete physical file from disk
        String libraryPath = settingsService.getOrCreateSettings().getLibraryPath();
        if (libraryPath == null || libraryPath.isBlank()) {
            LOGGER.log(Level.WARNING, "Music library path is not configured. Cannot delete physical file for song: {0}", song.getPath());
            // We might still want to proceed with DB deletion if file path is missing,
            // but for now, let's throw an exception if the path is critical for deletion.
            throw new IllegalStateException("Music library path is not configured. Cannot delete physical file.");
        }

        Path absolutePath = Paths.get(libraryPath, song.getPath());
        try {
            if (Files.exists(absolutePath)) {
                Files.delete(absolutePath);
                LOGGER.log(Level.INFO, "Successfully deleted physical file: {0}", absolutePath);
            } else {
                LOGGER.log(Level.WARNING, "Physical file not found for deletion: {0}", absolutePath);
            }
        } catch (Exception e) {
            System.out.println("Error deleting physical file for song: " + absolutePath + " " + e);
            throw new RuntimeException("Failed to delete physical file: " + e.getMessage(), e);
        }
    }
}
