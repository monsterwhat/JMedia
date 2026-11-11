package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Offline tests for provider-id tokens in show folder names.
 *
 * <p>The token form is the deterministic escape hatch for shows whose folder
 * name does not match a catalogue entry (an arc released as its own folder):
 * it states the id outright instead of relying on a title search.
 *
 * <pre>
 *   Show (2004) {tmdb-30984}    {tvdb-123}    {imdb-tt0434665}
 *   Show (2004) [tmdbid-30984]  [imdbid-tt0434665]
 * </pre>
 *
 * <p>Plain JUnit 5: no QuarkusTest, no mocks, no network — same style as
 * {@code SmartNamingServiceTest} and {@code SiblingInferenceTest}.
 */
class ProviderHintTest {

    @Test
    void parsesBracedToken() {
        SmartNamingService.ProviderHint hint =
                SmartNamingService.parseProviderHint("Bleach (2004) {tmdb-30984}");

        assertEquals(Integer.valueOf(30984), hint.tmdbId);
        assertNull(hint.imdbId);
        assertNull(hint.tvdbId);
    }

    @Test
    void parsesBracketedToken() {
        SmartNamingService.ProviderHint tmdb =
                SmartNamingService.parseProviderHint("Bleach (2004) [tmdbid-30984]");
        SmartNamingService.ProviderHint imdb =
                SmartNamingService.parseProviderHint("Bleach (2004) [imdbid-tt0434665]");

        assertEquals(Integer.valueOf(30984), tmdb.tmdbId);
        assertEquals("tt0434665", imdb.imdbId);
    }

    @Test
    void parsesTvdbHint() {
        SmartNamingService.ProviderHint hint =
                SmartNamingService.parseProviderHint("Some Anime {tvdb-74796}");

        assertEquals(Integer.valueOf(74796), hint.tvdbId);
    }

    @Test
    void returnsNullWhenNoTokenIsPresent() {
        assertNull(SmartNamingService.parseProviderHint("Bleach Thousand Year Blood War"));
        assertNull(SmartNamingService.parseProviderHint(""));
        assertNull(SmartNamingService.parseProviderHint(null));
    }

    @Test
    void qualityBracketsAreNotMistakenForAHint() {
        assertNull(SmartNamingService.parseProviderHint("Bleach (2004) [1080p]"));
        assertNull(SmartNamingService.parseProviderHint("Show [x265.AAC]"));
    }

    @Test
    void ignoresANonImdbValueForTheImdbKind() {
        assertNull(SmartNamingService.parseProviderHint("Show {imdb-1234}"),
                "a bare number is not a valid IMDb id and must be ignored");
    }

    @Test
    void stripsTheTokenFromTheDisplayName() {
        assertEquals("Bleach", SmartNamingService.cleanShowName("Bleach (2004) [tmdbid-30984]"));
        assertEquals("Steven Universe",
                SmartNamingService.cleanShowName("Steven Universe (2013) {tmdb-61175}"));
    }

    @Test
    void namingResultCarriesTheHintForAnArcFolder() {
        SmartNamingService service = new SmartNamingService();

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null,
                "Bleach - S02E01 - The Blood Warfare.mkv",
                "TV Shows/Bleach (2004) [tmdbid-30984]/Season 2/"
                        + "Bleach - S02E01 - The Blood Warfare.mkv",
                null, null, null, null, null, null);

        assertEquals(Integer.valueOf(30984), result.providerTmdbId,
                "the folder token must survive into the naming result");
        assertEquals("Bleach", result.showName,
                "the token must not leak into the show name");
        assertEquals(Integer.valueOf(2), result.season);
        assertEquals(Integer.valueOf(1), result.episode);
    }

    @Test
    void hintIsAbsentForAnOrdinaryFolder() {
        SmartNamingService service = new SmartNamingService();

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null,
                "Bleach - S02E01 - The Blood Warfare.mkv",
                "TV Shows/Bleach/Season 2/Bleach - S02E01 - The Blood Warfare.mkv",
                null, null, null, null, null, null);

        assertNull(result.providerTmdbId);
        assertTrue(result.showName != null && !result.showName.isEmpty());
    }
}
