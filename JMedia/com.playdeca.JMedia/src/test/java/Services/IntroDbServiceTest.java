package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntroDbService parsing of BOTH IntroDB response shapes:
 * - Source 1 (/segments): OBJECT shape   {"outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000}}
 * - Source 2 (/media):    ARRAY shape    {"intro":[{"start_ms":228892,"end_ms":245607}],"credits":[{"start_ms":3431000,"end_ms":null}]}
 *
 * Plain JUnit 5 (mirrors SmartNamingServiceTest): no QuarkusTest, no mocking, no network.
 * Fixtures are pinned string constants of live-verified payloads, so tests are
 * deterministic and fully offline.
 */
public class IntroDbServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Pinned fixtures (live-verified payloads) ─────────────────────────

    /** Source 1 /segments object shape: outro is an OBJECT; intro/recap are null. */
    private static final String SOURCE_1_OBJECT =
            "{\"imdb_id\":\"tt0903747\",\"season\":1,\"episode\":1,\"intro\":null,\"recap\":null,"
            + "\"outro\":{\"start_sec\":3431,\"end_sec\":3500,\"start_ms\":3431000,\"end_ms\":3500000,\"confidence\":1}}";

    /** Source 2 /media array shape: intro/credits are ARRAYS of objects. */
    private static final String SOURCE_2_ARRAY =
            "{\"tmdb_id\":1396,\"type\":\"tv\",\"season\":1,\"episode\":1,"
            + "\"intro\":[{\"start_ms\":228892,\"end_ms\":245607}],"
            + "\"credits\":[{\"start_ms\":3431000,\"end_ms\":null}]}";

    /** Both keys present: outro (object) AND credits (array) — outro must win. */
    private static final String BOTH_KEYS =
            "{\"outro\":{\"start_sec\":3431,\"end_sec\":3500,\"start_ms\":3431000,\"end_ms\":3500000},"
            + "\"credits\":[{\"start_ms\":228892,\"end_ms\":245607}]}";

    /** ms and sec DISAGREE — proves milliseconds are preferred when both are present. */
    private static final String MS_PREFERRED =
            "{\"intro\":[{\"start_sec\":57,\"end_sec\":61,\"start_ms\":228892,\"end_ms\":245607}]}";

    // ── Case 1: Source-1 OBJECT shape → outro parses ─────────────────────

    @Test
    void source1ObjectOutroParses() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(SOURCE_1_OBJECT);
        assertTrue(result.isPresent(), "Source-1 object body should parse to metadata");
        Optional<IntroDbService.Timestamps> outro = result.get().outro;
        assertTrue(outro.isPresent(), "Source-1 object outro should be present");
        assertEquals(3431.0, outro.get().start, 0.0001,
                "outro.start should be 3431.0 (start_ms 3431000 / 1000)");
        assertEquals(3500.0, outro.get().end, 0.0001,
                "outro.end should be 3500.0 (end_ms 3500000 / 1000)");
    }

    // ── Case 2: Source-1 `intro:null` / `recap:null` → empty ─────────────

    @Test
    void source1NullIntroAndRecapStayEmpty() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(SOURCE_1_OBJECT);
        assertTrue(result.isPresent(), "metadata should be present (outro exists)");
        assertTrue(result.get().intro.isEmpty(), "intro:null should yield empty intro");
        assertTrue(result.get().recap.isEmpty(), "recap:null should yield empty recap");
    }

    // ── Case 3: Source-2 ARRAY shape → intro parses ──────────────────────

    @Test
    void source2ArrayIntroParses() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(SOURCE_2_ARRAY);
        assertTrue(result.isPresent(), "Source-2 array body should parse to metadata");
        Optional<IntroDbService.Timestamps> intro = result.get().intro;
        assertTrue(intro.isPresent(), "Source-2 array intro should be present");
        assertEquals(228.892, intro.get().start, 0.0001,
                "intro.start should be 228.892 (start_ms 228892 / 1000)");
        assertEquals(245.607, intro.get().end, 0.0001,
                "intro.end should be 245.607 (end_ms 245607 / 1000)");
    }

    // ── Case 4: Source-2 credits ARRAY → outro fallback when outro absent ─

    @Test
    void source2CreditsFallbackToOutro() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(SOURCE_2_ARRAY);
        assertTrue(result.isPresent(), "metadata should be present");
        Optional<IntroDbService.Timestamps> outro = result.get().outro;
        assertTrue(outro.isPresent(), "credits array should fall back to outro when 'outro' key absent");
        assertEquals(3431.0, outro.get().start, 0.0001,
                "outro.start should come from credits[0].start_ms 3431000 / 1000 = 3431.0");
    }

    // ── Case 5: `outro` key WINS over `credits` when both present ────────

    @Test
    void outroKeyWinsOverCredits() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(BOTH_KEYS);
        assertTrue(result.isPresent(), "metadata should be present");
        Optional<IntroDbService.Timestamps> outro = result.get().outro;
        assertTrue(outro.isPresent(), "outro should be present");
        assertEquals(3431.0, outro.get().start, 0.0001,
                "outro.start should be 3431.0 from the outro OBJECT, NOT 228.892 from credits");
        assertEquals(3500.0, outro.get().end, 0.0001,
                "outro.end should be 3500.0 from the outro OBJECT, NOT 245.607 from credits");
    }

    // ── Case 6: `end_ms:null` → end stays null, no NPE ───────────────────

    @Test
    void nullEndMsYieldsNullEndNoNpe() throws Exception {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(SOURCE_2_ARRAY);
        assertTrue(result.isPresent(), "metadata should be present");
        IntroDbService.Timestamps outro = result.get().outro.get();
        assertEquals(3431.0, outro.start, 0.0001, "outro.start should still parse from start_ms");
        assertNull(outro.end, "end_ms:null must not NPE and must leave end as null");

        // Segment-level: object node with end_ms:null.
        JsonNode node = MAPPER.readTree("{\"start_ms\":3431000,\"end_ms\":null}");
        Optional<IntroDbService.Timestamps> ts = IntroDbService.parseSegment(node);
        assertTrue(ts.isPresent(), "object node with start_ms should parse");
        assertEquals(3431.0, ts.get().start, 0.0001, "start should be 3431.0");
        assertNull(ts.get().end, "end should be null for end_ms:null");
    }

    // ── Case 7: `{}` / missing keys → empty ──────────────────────────────

    @Test
    void emptyOrMissingKeysReturnEmpty() throws Exception {
        assertTrue(IntroDbService.parseBody("{}").isEmpty(),
                "empty object should yield empty Optional");
        assertTrue(IntroDbService.parseBody("{\"season\":1}").isEmpty(),
                "object with only unrelated keys should yield empty Optional");
        assertTrue(IntroDbService.parseSegment(MAPPER.readTree("{}")).isEmpty(),
                "empty object node should yield empty segment");
        assertTrue(IntroDbService.parseSegment(MAPPER.readTree("{\"outro\":{}}").path("outro")).isEmpty(),
                "empty outro object node should yield empty segment");
    }

    // ── Case 8: milliseconds preferred over seconds when both present ────

    @Test
    void millisecondsPreferredOverSeconds() {
        Optional<IntroDbService.MediaMetadata> result = IntroDbService.parseBody(MS_PREFERRED);
        assertTrue(result.isPresent(), "metadata should be present");
        IntroDbService.Timestamps intro = result.get().intro.get();
        assertEquals(228.892, intro.start, 0.0001,
                "start should use start_ms 228892 (NOT start_sec 57)");
        assertEquals(245.607, intro.end, 0.0001,
                "end should use end_ms 245607 (NOT end_sec 61)");
    }

    // ── Bonus: seconds path when milliseconds absent ─────────────────────

    @Test
    void secondsUsedWhenMillisecondsAbsent() throws Exception {
        JsonNode node = MAPPER.readTree("{\"start_sec\":57,\"end_sec\":61}");
        Optional<IntroDbService.Timestamps> ts = IntroDbService.parseSegment(node);
        assertTrue(ts.isPresent(), "object node with only start_sec should parse");
        assertEquals(57.0, ts.get().start, 0.0001, "start should come from start_sec 57");
        assertEquals(61.0, ts.get().end, 0.0001, "end should come from end_sec 61");
    }

    // ── Bonus: null guard + malformed JSON → empty ───────────────────────

    @Test
    void nullNodeAndMalformedJsonReturnEmpty() {
        assertTrue(IntroDbService.parseSegment(null).isEmpty(),
                "null node should yield empty Optional");
        assertTrue(IntroDbService.parseBody("not valid json {").isEmpty(),
                "malformed JSON should yield empty Optional");
    }
}
