package Services;

import Models.Music.Album;
import Models.Music.Artist;
import Models.Music.Song;
import Models.Music.SongLinks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One-off / maintenance work for the Album + Artist identity rows.
 *
 * <p>{@link #backfill} links every song that predates the entities (albumId or
 * artistId null) and is idempotent: it only touches unlinked rows, so it can be
 * re-run after an interrupted pass. {@link #cleanupEmpty} drops albums/artists
 * that no longer own a song, which is what keeps the grid free of ghosts after
 * tag edits or deleted files.</p>
 */
@ApplicationScoped
public class AlbumLinkService {

    private static final Logger LOGGER = Logger.getLogger(AlbumLinkService.class.getName());

    @PersistenceContext(unitName = "music")
    private EntityManager em;

    @Transactional
    public Map<String, Object> backfill(int batchSize) {
        int batch = Math.max(50, Math.min(batchSize, 1000));

        long total = em.createQuery("SELECT COUNT(s) FROM Song s", Long.class).getSingleResult();
        long linked = 0;
        int passes = 0;

        while (passes++ < 10_000) {
            List<Song> songs = em.createQuery(
                            "SELECT s FROM Song s WHERE s.albumId IS NULL OR s.artistId IS NULL ORDER BY s.id",
                            Song.class)
                    .setMaxResults(batch)
                    .getResultList();
            if (songs.isEmpty()) {
                break;
            }
            for (Song song : songs) {
                SongLinks.link(song, em);
            }
            em.flush();
            em.clear();
            linked += songs.size();
            if (linked % 2000 < batch) {
                LOGGER.info("[AlbumLink] backfill linked " + linked + " / " + total);
            }
        }

        long albums = em.createQuery("SELECT COUNT(a) FROM Album a", Long.class).getSingleResult();
        long artists = em.createQuery("SELECT COUNT(a) FROM Artist a", Long.class).getSingleResult();
        long unlinked = em.createQuery(
                "SELECT COUNT(s) FROM Song s WHERE s.albumId IS NULL OR s.artistId IS NULL", Long.class).getSingleResult();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("songs", total);
        result.put("linked", linked);
        result.put("albums", albums);
        result.put("artists", artists);
        result.put("unlinkedRemaining", unlinked);
        LOGGER.info("[AlbumLink] backfill done: " + result);
        return result;
    }

    @Transactional
    public Map<String, Object> cleanupEmpty() {
        int albums = em.createNativeQuery(
                        "DELETE FROM album WHERE id NOT IN (SELECT albumId FROM song WHERE albumId IS NOT NULL)")
                .executeUpdate();
        int artists = em.createNativeQuery(
                        "DELETE FROM artist WHERE id NOT IN (SELECT artistId FROM song WHERE artistId IS NOT NULL)")
                .executeUpdate();
        SongLinks.invalidate();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("albumsDeleted", albums);
        result.put("artistsDeleted", artists);
        LOGGER.info("[AlbumLink] cleanup removed " + albums + " album(s), " + artists + " artist(s)");
        return result;
    }
}
