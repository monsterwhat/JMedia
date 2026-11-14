package API.Rest;

import Models.Video.Video;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Absolute-numbering helpers used by the info modal to reconcile a library whose
 * season split differs from TMDb's (HxH 2011: 58/78/12 locally vs 62/74/12).
 */
class VideoUiApiEpisodeNumberingTest {

    private static Video ep(int season, int episode) {
        Video v = new Video();
        v.seasonNumber = season;
        v.episodeNumber = episode;
        return v;
    }

    @Test
    void infersLengthsFromTheHighestEpisodeNumberPerSeason() {
        Map<Integer, Integer> lengths = VideoUiApi.inferLocalSeasonLengths(List.of(
                ep(1, 1), ep(1, 58), ep(1, 2), ep(2, 78), ep(2, 59), ep(3, 12)));

        assertEquals(3, lengths.size());
        assertEquals(58, lengths.get(1));
        assertEquals(78, lengths.get(2));
        assertEquals(12, lengths.get(3));
    }

    @Test
    void ignoresSpecialsAndInvalidNumbers() {
        Video special = ep(0, 3);
        Video emptyEpisode = ep(1, 0);
        Map<Integer, Integer> lengths = VideoUiApi.inferLocalSeasonLengths(
                List.of(special, emptyEpisode, ep(1, 5)));

        assertEquals(Map.of(1, 5), lengths);
    }

    @Test
    void mapsLocalEpisodesToAbsoluteNumbers() {
        Map<Integer, Integer> local = Map.of(1, 58, 2, 78, 3, 12);

        assertEquals(58, VideoUiApi.absoluteEpisodeNumber(1, 58, local));
        assertEquals(59, VideoUiApi.absoluteEpisodeNumber(2, 1, local));
        assertEquals(63, VideoUiApi.absoluteEpisodeNumber(2, 5, local));
        assertEquals(137, VideoUiApi.absoluteEpisodeNumber(3, 1, local));
        assertEquals(148, VideoUiApi.absoluteEpisodeNumber(3, 12, local));
    }

    @Test
    void mapsTmdbEpisodesToTheSameAbsoluteNumbers() {
        Map<Integer, Integer> tmdb = Map.of(1, 62, 2, 74, 3, 12);

        // TMDb S01E59-62 are the four episodes JMedia flagged as missing.
        assertEquals(59, VideoUiApi.absoluteEpisodeNumber(1, 59, tmdb));
        assertEquals(62, VideoUiApi.absoluteEpisodeNumber(1, 62, tmdb));
        // ...and they are local S02E01-04.
        Map<Integer, Integer> local = Map.of(1, 58, 2, 78, 3, 12);
        assertEquals(59, VideoUiApi.absoluteEpisodeNumber(2, 1, local));
        assertEquals(62, VideoUiApi.absoluteEpisodeNumber(2, 4, local));
        // Later episodes keep matching.
        assertEquals(63, VideoUiApi.absoluteEpisodeNumber(2, 1, tmdb));
        assertEquals(63, VideoUiApi.absoluteEpisodeNumber(2, 5, local));
        assertEquals(148, VideoUiApi.absoluteEpisodeNumber(3, 12, tmdb));
    }

    @Test
    void returnsNullWhenAnEarlierSeasonLengthIsUnknown() {
        assertNull(VideoUiApi.absoluteEpisodeNumber(3, 1, Map.of(1, 58, 3, 12)));
        assertNull(VideoUiApi.absoluteEpisodeNumber(0, 1, Map.of(1, 58)));
        assertNull(VideoUiApi.absoluteEpisodeNumber(1, 0, Map.of(1, 58)));
    }

    @Test
    void detectsACompleteRun() {
        assertTrue(VideoUiApi.isCompleteRun(148, 148));
        assertFalse(VideoUiApi.isCompleteRun(147, 148));
        assertFalse(VideoUiApi.isCompleteRun(148, 147));
        assertFalse(VideoUiApi.isCompleteRun(0, 0));
    }

    @Test
    void detectsAbsoluteCatalogueNumbering() {
        assertTrue(VideoUiApi.usesAbsoluteEpisodeNumbers(List.of(63, 64, 136)));
        assertTrue(VideoUiApi.usesAbsoluteEpisodeNumbers(List.of(137, 148)));
        assertFalse(VideoUiApi.usesAbsoluteEpisodeNumbers(List.of(1, 2, 62)));
        assertFalse(VideoUiApi.usesAbsoluteEpisodeNumbers(List.of()));
        assertFalse(VideoUiApi.usesAbsoluteEpisodeNumbers(null));
    }

    @Test
    void localEpisodeSetCoversEveryTmdbAbsoluteNumberForTheFullRun() {
        Map<Integer, Integer> local = Map.of(1, 58, 2, 78, 3, 12);
        Map<Integer, Integer> tmdb = Map.of(1, 62, 2, 74, 3, 12);
        boolean[] seen = new boolean[149];
        for (int s = 1; s <= 3; s++) {
            for (int e = 1; e <= local.get(s); e++) {
                seen[VideoUiApi.absoluteEpisodeNumber(s, e, local)] = true;
            }
        }
        int matched = 0;
        for (int s = 1; s <= 3; s++) {
            for (int e = 1; e <= tmdb.get(s); e++) {
                int absolute = VideoUiApi.absoluteEpisodeNumber(s, e, tmdb);
                if (seen[absolute]) matched++;
            }
        }
        assertEquals(148, matched);
        assertTrue(seen[148]);
    }
}
