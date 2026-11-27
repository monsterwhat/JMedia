package Models.Music;

import jakarta.persistence.EntityManager;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link Album} / {@link Artist} rows a song belongs to and stamps
 * their ids on the song. Called from the write funnels (SongService.save,
 * SongService.persistSongInNewTx, sync merge) <em>before</em> the song itself is
 * persisted, so the ids are known when the row is written.
 *
 * Not a JPA lifecycle callback on purpose: creating the album needs a flush to
 * obtain its id, and flushing from inside a pre-insert event is re-entrant.
 */
public final class SongLinks {

    private static final ConcurrentHashMap<String, Long> ALBUM_IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ARTIST_IDS = new ConcurrentHashMap<>();

    private SongLinks() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String orFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** Re-link an album/artist after tags changed and forget the cached ids. */
    public static void invalidate() {
        ALBUM_IDS.clear();
        ARTIST_IDS.clear();
    }

    public static void link(Song song, EntityManager em) {
        if (song == null || em == null) {
            return;
        }

        String albumName = orFallback(song.getAlbum(), "Unknown Album");
        String albumArtist = song.getAlbumArtist() == null ? "" : song.getAlbumArtist().trim();
        Long albumId = albumIdFor(em, albumName, albumArtist, song.getArtworkPath(), song.getReleaseDate());
        song.setAlbumId(albumId);

        String artistName = orFallback(song.getArtist(), "Unknown Artist");
        song.setArtistId(artistIdFor(em, artistName));
    }

    private static Long albumIdFor(EntityManager em, String name, String artist, String artwork, String releaseDate) {
        String nameKey = normalize(name);
        String artistKey = normalize(artist);
        String cacheKey = nameKey + "\u0000" + artistKey;

        Long cached = ALBUM_IDS.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // One JVM, many scan threads: serialise creation so the unique constraint
        // can never fire from a race (this is what produced duplicate songs before).
        synchronized (SongLinks.class) {
            cached = ALBUM_IDS.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Album album = em.createQuery(
                            "SELECT a FROM Album a WHERE a.nameKey = :nk AND a.artistKey = :ak", Album.class)
                    .setParameter("nk", nameKey)
                    .setParameter("ak", artistKey)
                    .setMaxResults(1)
                    .getResultList()
                    .stream().findFirst().orElse(null);
            if (album == null) {
                album = new Album();
                album.name = name;
                album.artist = artist;
                album.nameKey = nameKey;
                album.artistKey = artistKey;
                album.artworkPath = artwork;
                album.releaseDate = releaseDate;
                em.persist(album);
                em.flush();          // assign the id now; safe outside entity callbacks
            } else {
                boolean dirty = false;
                if ((album.artworkPath == null || album.artworkPath.isBlank()) && artwork != null && !artwork.isBlank()) {
                    album.artworkPath = artwork;
                    dirty = true;
                }
                if ((album.releaseDate == null || album.releaseDate.isBlank()) && releaseDate != null && !releaseDate.isBlank()) {
                    album.releaseDate = releaseDate;
                    dirty = true;
                }
                if (dirty) {
                    em.merge(album);
                }
            }
            ALBUM_IDS.put(cacheKey, album.id);
            return album.id;
        }
    }

    private static Long artistIdFor(EntityManager em, String name) {
        String nameKey = normalize(name);
        Long cached = ARTIST_IDS.get(nameKey);
        if (cached != null) {
            return cached;
        }
        synchronized (SongLinks.class) {
            cached = ARTIST_IDS.get(nameKey);
            if (cached != null) {
                return cached;
            }
            Artist artist = em.createQuery("SELECT a FROM Artist a WHERE a.nameKey = :nk", Artist.class)
                    .setParameter("nk", nameKey)
                    .setMaxResults(1)
                    .getResultList()
                    .stream().findFirst().orElse(null);
            if (artist == null) {
                artist = new Artist();
                artist.name = name;
                artist.nameKey = nameKey;
                em.persist(artist);
                em.flush();
            }
            ARTIST_IDS.put(nameKey, artist.id);
            return artist.id;
        }
    }
}
