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
import io.quarkus.narayana.jta.QuarkusTransaction;

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

    /**
     * Link every song that predates the entities. Songs are read in batches, their
     * ids resolved through the album/artist cache, and the two columns written with
     * a bulk update: a bulk update bypasses @PreUpdate, so this pass cannot trigger
     * the per-song genre re-sync (which is what made a normal entity flush die on
     * the UK_SONG_GENRES_SONG_KEY index). Idempotent: only unlinked rows are read.
     */
    /**
     * Link every song that predates the entities. One transaction per batch keeps
     * this well inside the transaction timeout, and the ids are written with a
     * bulk update so @PreUpdate does not fire for 12k rows (a normal flush there
     * re-synced genres and collided on UK_SONG_GENRES_SONG_KEY). Resumable: only
     * unlinked rows are read, so an interrupted run continues where it stopped.
     */
    public Map<String, Object> backfill(int batchSize) {
        final int batch = Math.max(50, Math.min(batchSize, 1000));
        final long total = QuarkusTransaction.requiringNew()
                .call(() -> em.createQuery("SELECT COUNT(s) FROM Song s", Long.class).getSingleResult());
        long linked = 0;

        while (true) {
            final long[] processed = {0};
            final boolean more = QuarkusTransaction.requiringNew().call(() -> {
                List<Song> songs = em.createQuery(
                                "SELECT s FROM Song s WHERE s.albumId IS NULL OR s.artistId IS NULL ORDER BY s.id",
                                Song.class)
                        .setMaxResults(batch)
                        .getResultList();
                if (songs.isEmpty()) {
                    return false;
                }

                List<Object[]> pairs = new java.util.ArrayList<>(songs.size());
                for (Song song : songs) {
                    long[] ids = Models.Music.SongLinks.resolveIds(song.getAlbum(), song.getAlbumArtist(),
                            song.getArtworkPath(), song.getReleaseDate(), song.getArtist(), em);
                    pairs.add(new Object[]{song.id, ids[0], ids[1]});
                }
                em.flush();
                em.clear();

                for (Object[] pair : pairs) {
                    em.createQuery("UPDATE Song s SET s.albumId = :a, s.artistId = :r WHERE s.id = :id")
                            .setParameter("a", (Long) pair[1])
                            .setParameter("r", (Long) pair[2])
                            .setParameter("id", (Long) pair[0])
                            .executeUpdate();
                }
                em.flush();
                em.clear();
                processed[0] = songs.size();
                return true;
            });

            if (!more) {
                break;
            }
            linked += processed[0];
            if (linked % 2000 < batch) {
                LOGGER.info("[AlbumLink] backfill linked " + linked + " / " + total);
            }
        }

        final long linkedFinal = linked;
        Map<String, Object> result = QuarkusTransaction.requiringNew().call(() -> {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("songs", total);
            counts.put("linked", linkedFinal);
            counts.put("albums", em.createQuery("SELECT COUNT(a) FROM Album a", Long.class).getSingleResult());
            counts.put("artists", em.createQuery("SELECT COUNT(a) FROM Artist a", Long.class).getSingleResult());
            counts.put("unlinkedRemaining", em.createQuery(
                    "SELECT COUNT(s) FROM Song s WHERE s.albumId IS NULL OR s.artistId IS NULL", Long.class).getSingleResult());
            return counts;
        });
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
