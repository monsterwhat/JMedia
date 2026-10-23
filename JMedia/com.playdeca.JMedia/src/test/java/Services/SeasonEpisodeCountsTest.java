package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 season-episode-count tests (offline, deterministic, no QuarkusTest —
 * mirrors IntroDbServiceTest): accumulation of max(episodeNumber) per season
 * from an IMDb Dev-style {@code /episodes} array, and JSON round-trip of the
 * {@code Series.seasonEpisodeCounts} column via the pure static helpers on
 * {@link VideoMetadataService}.
 */
public class SeasonEpisodeCountsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds a deterministic IMDb Dev-style episodes array: seasons 1 and 2
     * with 25 episodes each (shuffled insertion order proves max() wins),
     * plus edge entries that must be ignored: a season-0 special, an entry
     * with a missing episodeNumber, and a non-object entry.
     */
    private static ArrayNode episodesFixture() {
        ArrayNode episodes = MAPPER.createArrayNode();
        // Season 2 first (out of order), descending episode numbers.
        for (int ep = 25; ep >= 1; ep--) {
            episodes.add(episodeNode("tt9000%02d".formatted(ep), 2, ep, "S2E" + ep));
        }
        // Season 1 ascending.
        for (int ep = 1; ep <= 25; ep++) {
            episodes.add(episodeNode("tt8000%02d".formatted(ep), 1, ep, "S1E" + ep));
        }
        // Season-0 special: must be skipped (consumers require seasons from 1).
        episodes.add(episodeNode("tt0000001", 0, 3, "Special"));
        // Missing episodeNumber: must be skipped.
        ObjectNode noEpNum = MAPPER.createObjectNode();
        noEpNum.put("id", "tt0000002");
        noEpNum.put("season", 1);
        noEpNum.put("title", "No number");
        episodes.add(noEpNum);
        // Non-object entry: must be skipped without throwing.
        episodes.add("not-an-object");
        return episodes;
    }

    private static ObjectNode episodeNode(String id, int season, int episodeNumber, String title) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("season", season);
        node.put("episodeNumber", episodeNumber);
        node.put("title", title);
        return node;
    }

    // ── Accumulation ────────────────────────────────────────────────────

    @Test
    void accumulatesMaxEpisodeNumberPerSeason() {
        JsonNode episodes = episodesFixture();
        Map<Integer, Integer> counts = VideoMetadataService.accumulateSeasonEpisodeCounts(episodes);
        Map<Integer, Integer> expected = new TreeMap<>(Map.of(1, 25, 2, 25));
        assertEquals(expected, counts, "seasons 1-2 must accumulate to 25 episodes each");
    }

    @Test
    void stringSeasonCoercesToInt() {
        // IMDb Dev returns numeric seasons, but asInt() also accepts numeric
        // strings — prove the helper is robust to both shapes.
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "tt0000003");
        node.put("season", "2");
        node.put("episodeNumber", 7);
        ArrayNode episodes = MAPPER.createArrayNode().add(node);
        assertEquals(Map.of(2, 7), VideoMetadataService.accumulateSeasonEpisodeCounts(episodes));
    }

    @Test
    void nullMissingAndNonArrayYieldEmpty() {
        assertTrue(VideoMetadataService.accumulateSeasonEpisodeCounts(null).isEmpty(),
                "null array must yield empty");
        assertTrue(VideoMetadataService.accumulateSeasonEpisodeCounts(
                MAPPER.createObjectNode().path("episodes")).isEmpty(),
                "missing array must yield empty");
        assertTrue(VideoMetadataService.accumulateSeasonEpisodeCounts(
                MAPPER.createObjectNode().put("not", "array")).isEmpty(),
                "non-array node must yield empty");
    }

    // ── JSON round-trip of the persisted column ──────────────────────────

    @Test
    void jsonRoundTripPreservesCounts() {
        Map<Integer, Integer> original = new TreeMap<>(Map.of(1, 25, 2, 25));
        String json = VideoMetadataService.toSeasonEpisodeCountsJson(original);
        assertNotNull(json, "serialization must produce JSON");
        Map<Integer, Integer> parsed = VideoMetadataService.parseSeasonEpisodeCountsJson(json);
        assertEquals(original, parsed, "parse(serialize(counts)) must round-trip");
    }

    @Test
    void serializedFormUsesStringSeasonKeys() throws Exception {
        String json = VideoMetadataService.toSeasonEpisodeCountsJson(Map.of(1, 25, 2, 25));
        assertNotNull(json);
        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.isObject(), "persisted form must be a JSON object");
        assertEquals(25, root.path("1").asInt(), "season key '1' must map to 25");
        assertEquals(25, root.path("2").asInt(), "season key '2' must map to 25");
    }

    @Test
    void emptyAndInvalidJsonParseToNull() {
        assertNull(VideoMetadataService.toSeasonEpisodeCountsJson(null),
                "null map must serialize to null (never persist empties)");
        assertNull(VideoMetadataService.toSeasonEpisodeCountsJson(Map.of()),
                "empty map must serialize to null (never persist empties)");
        assertNull(VideoMetadataService.parseSeasonEpisodeCountsJson(null));
        assertNull(VideoMetadataService.parseSeasonEpisodeCountsJson("  "));
        assertNull(VideoMetadataService.parseSeasonEpisodeCountsJson("{}"),
                "empty object carries no lengths");
        assertNull(VideoMetadataService.parseSeasonEpisodeCountsJson("not-json{{"),
                "garbage must not throw, must yield null");
        assertNull(VideoMetadataService.parseSeasonEpisodeCountsJson("{\"1\":0,\"x\":-3}"),
                "non-positive counts and non-numeric seasons must yield null");
    }
}
