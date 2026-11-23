package Controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import Models.Music.PlaybackState;
import Models.Music.Song;
import Services.PlaybackHistoryService;
import Services.SongService;
import Utils.GenreNormalizer;
import org.junit.jupiter.api.Test;

/**
 * Regression guards for the Smart Shuffle next-song pick in block mode
 * (songs per genre >= 1). Uses in-memory fakes instead of CDI and a database.
 */
class PlaybackQueueControllerSmartPickTest {

    /** In-memory song lookup. Candidate queries match on canonical genre key. */
    static class FakeSongService extends SongService {
        final Map<Long, Song> songs = new HashMap<>();

        @Override
        public Song find(Long id) {
            return songs.get(id);
        }

        @Override
        public List<Song> findByIds(List<Long> ids) {
            return ids.stream().map(songs::get).filter(Objects::nonNull).collect(Collectors.toList());
        }

        @Override
        public List<Song> findCandidatesByGenreAndBpm(String genre, int currentBpm, int bpmTolerance,
                                                      List<Long> exclude, List<Long> pool, int max) {
            return findCandidatesByGenre(genre, exclude, pool, max);
        }

        @Override
        public List<Song> findCandidatesByGenre(String genre, List<Long> exclude, List<Long> pool, int max) {
            String key = GenreNormalizer.resolveAll(genre).get(0).key();
            return pool.stream()
                    .filter(id -> exclude == null || !exclude.contains(id))
                    .map(songs::get)
                    .filter(Objects::nonNull)
                    .filter(s -> GenreNormalizer.resolveAll(s.getGenre()).get(0).key().equals(key))
                    .limit(max)
                    .collect(Collectors.toList());
        }
    }

    /** Fixed play history, newest first. */
    static class FakeHistoryService extends PlaybackHistoryService {
        List<Long> recent = new ArrayList<>();

        @Override
        public List<Long> getRecentlyPlayedSongIds(int count, Long profileId) {
            return new ArrayList<>(recent.subList(0, Math.min(count, recent.size())));
        }
    }

    private final FakeSongService songs = new FakeSongService();
    private final FakeHistoryService history = new FakeHistoryService();
    private final PlaybackQueueController controller = new PlaybackQueueController();

    PlaybackQueueControllerSmartPickTest() throws Exception {
        inject("songService", songs);
        inject("playbackHistoryService", history);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = PlaybackQueueController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void song(long id, String genre, int bpm, String artist) {
        Song s = new Song();
        s.id = id;
        s.setGenre(genre);
        s.setBpm(bpm);
        s.setArtist(artist);
        s.setAlbum("Album " + id);
        songs.songs.put(id, s);
    }

    private PlaybackState blockModeState(PlaybackState.RepeatMode repeat, int songsPerGenre,
                                         List<Long> cue, long current) {
        PlaybackState state = new PlaybackState();
        state.setShuffleMode(PlaybackState.ShuffleMode.SMART_SHUFFLE);
        state.setRepeatMode(repeat);
        state.setDjSongsPerGenre(songsPerGenre);
        state.setCue(new ArrayList<>(cue));
        state.setOriginalCue(new ArrayList<>());
        state.setCurrentSongId(current);
        state.setCueIndex(cue.indexOf(current));
        state.setPlaying(true);
        return state;
    }

    /** The only same-genre candidate sits before the current song in the cue. */
    private void pickBehindCurrentFixture() {
        song(10, "Rock", 120, "A");
        song(20, "Rock", 120, "B");
        song(30, "Jazz", 90, "C");
    }

    @Test
    void repeatAllPlaysPickThatWasMovedFromBeforeCurrent() {
        pickBehindCurrentFixture();
        PlaybackState state = blockModeState(PlaybackState.RepeatMode.ALL, 2, List.of(10L, 20L, 30L), 20L);

        Long next = controller.advance(state, true, false, false, 1L);

        assertEquals(10L, next, "the smart pick must play, not the song after it");
    }

    @Test
    void repeatOffRemovesFinishedSongNotThePick() {
        pickBehindCurrentFixture();
        PlaybackState state = blockModeState(PlaybackState.RepeatMode.OFF, 2, List.of(10L, 20L, 30L), 20L);

        Long next = controller.advance(state, true, false, false, 1L);

        assertEquals(10L, next, "the smart pick must play next");
        assertEquals(List.of(10L, 30L), state.getCue(), "the finished song is the one removed");
    }

    @Test
    void repeatOffEndsGenreBlockUsingPlayHistory() {
        song(10, "Rock", 120, "A");  // played before the current song
        song(20, "Rock", 120, "B");  // current
        song(40, "Rock", 120, "D");
        song(30, "Jazz", 120, "C");
        history.recent = new ArrayList<>(List.of(10L));
        // Repeat OFF keeps the current song at index 0, so the cue holds no
        // played songs; only history shows the rock run has reached 2.
        PlaybackState state = blockModeState(PlaybackState.RepeatMode.OFF, 2, List.of(20L, 40L, 30L), 20L);

        Long next = controller.advance(state, true, false, false, 1L);

        assertEquals(30L, next, "a full genre block must switch to another genre");
    }

    @Test
    void repeatOffStaysInGenreWhileBlockIsNotFull() {
        song(10, "Jazz", 120, "A");
        song(20, "Rock", 120, "B");
        song(40, "Rock", 120, "D");
        song(30, "Jazz", 120, "C");
        history.recent = new ArrayList<>(List.of(10L));
        PlaybackState state = blockModeState(PlaybackState.RepeatMode.OFF, 2, List.of(20L, 30L, 40L), 20L);

        Long next = controller.advance(state, true, false, false, 1L);

        assertEquals(40L, next, "a block of 2 continues the current genre after one song");
    }
}
