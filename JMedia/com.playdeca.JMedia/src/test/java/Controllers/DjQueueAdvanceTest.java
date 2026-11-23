package Controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import Models.Music.PlaybackState;
import Models.Music.Song;
import org.junit.jupiter.api.Test;

/**
 * Queue behaviour of a DJ Mode transition. The server now advances the queue
 * through {@link PlaybackQueueController#advance} (as on a natural song end)
 * instead of removing the finished song by hand, and Smart Shuffle picks the
 * following song when a song starts.
 */
class DjQueueAdvanceTest {

    private final PlaybackQueueControllerSmartPickTest.FakeSongService songs =
            new PlaybackQueueControllerSmartPickTest.FakeSongService();
    private final PlaybackQueueControllerSmartPickTest.FakeHistoryService history =
            new PlaybackQueueControllerSmartPickTest.FakeHistoryService();
    private final PlaybackQueueController controller = new PlaybackQueueController();

    DjQueueAdvanceTest() throws Exception {
        inject("songService", songs);
        inject("playbackHistoryService", history);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = PlaybackQueueController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void song(long id, String genre) {
        Song s = new Song();
        s.id = id;
        s.setGenre(genre);
        s.setBpm(120);
        s.setArtist("Artist " + id);
        s.setAlbum("Album " + id);
        songs.songs.put(id, s);
    }

    /** State as it is when the client reports a DJ transition into djNext. */
    private PlaybackState plannedTransition(PlaybackState.RepeatMode repeat, List<Long> cue,
                                            long current, long djNext) {
        PlaybackState st = new PlaybackState();
        st.setShuffleMode(PlaybackState.ShuffleMode.SMART_SHUFFLE);
        st.setRepeatMode(repeat);
        st.setDjModeActive(true);
        st.setDjSongsPerGenre(2);
        st.setCue(new ArrayList<>(cue));
        st.setOriginalCue(new ArrayList<>(cue));
        st.setCurrentSongId(current);
        st.setCueIndex(cue.indexOf(current));
        st.setDjTransitionPlanned(true);
        st.setDjNextSongId(djNext);
        st.setPlaying(true);
        return st;
    }

    /** Mirrors the queue part of PlaybackController.handleTransitionStarted. */
    private void transition(PlaybackState st, long djNext) {
        controller.advance(st, true, false, false, 1L);
        PlaybackController.alignCueToSong(st, djNext);
        st.setCurrentSongId(djNext);
    }

    @Test
    void repeatAllKeepsPlayedSongsAndSourcePool() {
        for (long id = 1; id <= 4; id++) song(id, "Rock");
        PlaybackState st = plannedTransition(PlaybackState.RepeatMode.ALL, List.of(1L, 2L, 3L, 4L), 2L, 3L);

        transition(st, 3L);

        assertEquals(List.of(1L, 2L, 3L, 4L), st.getCue(), "Repeat ALL must keep the loop intact");
        assertEquals(2, st.getCueIndex());
        assertEquals(List.of(1L, 2L, 3L, 4L), st.getOriginalCue(), "the source pool must not shrink");
    }

    @Test
    void repeatOffConsumesOnlyTheFinishedSong() {
        for (long id = 1; id <= 3; id++) song(id, "Rock");
        PlaybackState st = plannedTransition(PlaybackState.RepeatMode.OFF, List.of(1L, 2L, 3L), 1L, 2L);

        transition(st, 2L);

        assertEquals(List.of(2L, 3L), st.getCue());
        assertEquals(0, st.getCueIndex());
        assertEquals(List.of(1L, 2L, 3L), st.getOriginalCue(), "the source pool must not shrink");
    }

    @Test
    void plannedSongWinsEvenWhenNotNextInQueue() {
        for (long id = 1; id <= 4; id++) song(id, "Rock");
        // The client already plays 4; the queue's next is 2.
        PlaybackState st = plannedTransition(PlaybackState.RepeatMode.ALL, List.of(1L, 2L, 3L, 4L), 1L, 4L);

        transition(st, 4L);

        assertEquals(4L, st.getCue().get(st.getCueIndex()), "the queue must point at the playing song");
    }

    @Test
    void songStartPickEndsAFullGenreBlock() {
        song(10, "Rock");  // previous song
        song(20, "Rock");  // just started
        song(40, "Rock");
        song(30, "Jazz");
        history.recent = new ArrayList<>(List.of(10L));
        PlaybackState st = plannedTransition(PlaybackState.RepeatMode.OFF, List.of(20L, 40L, 30L), 20L, 40L);
        // A new song has started: the old plan was cleared before re-planning.
        st.setDjTransitionPlanned(null);
        st.setDjNextSongId(null);

        controller.prepareSmartNextForDj(st, 1L);

        assertEquals(30L, st.getCue().get(st.getCueIndex() + 1),
                "with 2 rock songs played, Smart Shuffle must queue another genre next");
    }

    @Test
    void songStartPickKeepsSkipStreak() {
        song(10, "Jazz");  // previous song
        song(20, "Rock");  // just started
        song(30, "Jazz");
        song(40, "Rock");
        history.recent = new ArrayList<>(List.of(10L));
        PlaybackState st = plannedTransition(PlaybackState.RepeatMode.OFF, List.of(20L, 30L, 40L), 20L, 30L);
        st.setDjTransitionPlanned(null);
        st.setDjNextSongId(null);
        st.setDjSkipsBeforeGenreChange(3);
        st.setDjGenreSkipCount(1);

        controller.prepareSmartNextForDj(st, 1L);

        assertEquals(40L, st.getCue().get(st.getCueIndex() + 1), "a half-full block stays in the genre");
        assertEquals(1, st.getDjGenreSkipCount(), "a song start must not reset the skip streak");
    }
}
