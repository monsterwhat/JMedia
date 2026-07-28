package Services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmartNamingService covering:
 * - P9: Family Guy Season 1-19 collection scenarios
 * - P10: Trailer Park Boys collection scenarios
 * - P10: Pattern unit tests via reflection
 */
public class SmartNamingServiceTest {

    private SmartNamingService service;

    @BeforeEach
    void setUp() {
        service = new SmartNamingService();
    }

    // ── Reflection helper ────────────────────────────────────────────────

    private static Pattern getPattern(String fieldName) throws Exception {
        Field field = SmartNamingService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }

    // ── P9: Family Guy Season 1-19 Collection Tests ─────────────────────

    /**
     * Standard S01E01 episode inside Season 1 subfolder.
     * Show folder "Family Guy The Complete Seasons 1-19 (1999-)" is cleaned via cleanShowName.
     */
    @Test
    void testFamilyGuySeasonEpisode() {
        String filename = "S01E01.mp4";
        String relativePath = "TV Shows/Family Guy The Complete Seasons 1-19 (1999-)/Season 1/S01E01.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType, "mediaType should be 'episode'");
        assertNotNull(result.showName, "showName should not be null");
        assertTrue(result.showName.contains("Family Guy"),
                "showName should contain 'Family Guy', got: " + result.showName);
        assertEquals(1, result.season, "season should be 1");
        assertEquals(1, result.episode, "episode should be 1");
    }

    /**
     * DVD Extras folder detected by EXTRAS_FOLDER_PATTERN → skipped during season search.
     * The extras file has no SxxExx pattern, so contentType is determined by path logic.
     * The show name is extracted from the show folder via cleanShowName fallback.
     */
    @Test
    void testFamilyGuyDVDExtras() {
        String filename = "Family Guy - Something Something Dark Side.mp4";
        String relativePath = "TV Shows/Family Guy The Complete Seasons 1-19 (1999-)/DVD Extras/Family Guy - Something Something Dark Side.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType, "mediaType should be 'episode'");
        assertNotNull(result.showName, "showName should not be null");
        assertEquals("Family Guy The", result.showName,
                "showName resolves to 'Family Guy The' via cleanShowName fallback on show folder");
        assertEquals(1, result.season,
                "season=1 via PathStructureDefault (showFolder present, no seasonFolder found)");
    }

    /**
     * "Movies/" subfolder → mediaTypeHint="movie" → short-circuit to movie media type.
     * Show name is extracted from the show folder via cleanShowName fallback.
     */
    @Test
    void testFamilyGuyMovie() {
        String filename = "Family Guy The Movie.mp4";
        String relativePath = "TV Shows/Family Guy The Complete Seasons 1-19 (1999-)/Movies/Family Guy The Movie.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("movie", result.mediaType,
                "mediaType should be 'movie' due to Movies/ folder hint");
        assertEquals("Family Guy The", result.showName,
                "showName resolves to 'Family Guy The' via cleanShowName fallback on show folder");
    }

    /**
     * Root-level standalone file with no season folder → flat structure detected.
     * Season defaults to 1 (FlatStructureDefault).
     */
    @Test
    void testFamilyGuyRootStandalone() {
        String filename = "Family Guy - Star Wars Parody.mp4";
        String relativePath = "TV Shows/Family Guy The Complete Seasons 1-19 (1999-)/Family Guy - Star Wars Parody.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertNotNull(result.showName, "showName should not be null");
        assertTrue(result.showName.contains("Family Guy"),
                "showName should contain 'Family Guy', got: " + result.showName);
        assertNotNull(result.season, "season should not be null");
        // Flat structure with showFolder present → PathStructureDefault sets season=1
    }

    // ── P10: Trailer Park Boys Collection Tests ──────────────────────────

    /**
     * Standard S01E01 episode inside a quality-encoded season subfolder.
     * "Trailer Park Boys S01 (360p re-dvdrip)" is detected as a season folder.
     * The collection name no longer sets mediaTypeHint="movie" because the
     * movieFilmWordPattern now requires the name to start with "Movies"/"Film".
     */
    @Test
    void testTPBSeasonEpisode() {
        String filename = "S01E01.mp4";
        String relativePath = "TV Shows/Trailer Park Boys S01-S12 (1999-) + Movies + Spinoffs/Trailer Park Boys S01 (360p re-dvdrip)/S01E01.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType,
                "mediaType is 'episode' (movieFilmWordPattern no longer matches 'Movies' mid-name in collection)");
        assertEquals("Trailer Park Boys", result.showName,
                "showName should be 'Trailer Park Boys'");
        assertEquals(1, result.season, "season should be 1");
        assertEquals(1, result.episode, "episode should be 1");
    }

    /**
     * SxxMxx pattern (S06M01) → contentType="movie", season=6.
     * Parent folder "Movies" in the collection name → mediaTypeHint="movie".
     */
    @Test
    void testTPBSxxMxxMovie() {
        String filename = "Trailer Park Boys S06M01 The Movie (2006 360p re-blurip).mp4";
        String relativePath = "TV Shows/Trailer Park Boys S01-S12 (1999-) + Movies + Spinoffs/Trailer Park Boys S06M01 The Movie (2006 360p re-blurip).mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("movie", result.mediaType,
                "mediaType should be 'movie' due to SxxMxx and/or folder hint");
        assertNotNull(result.showName, "showName should not be null");
        assertEquals("Trailer Park Boys", result.showName,
                "showName should be 'Trailer Park Boys'");
        assertEquals(6, result.season, "season should be 6 from S06M01");
        assertEquals("movie", result.contentType, "contentType should be 'movie' from SxxMxx");
    }

    /**
     * "Xtras/" folder detected by expanded EXTRAS_FOLDER_PATTERN → skipped during season search.
     * Show name is correctly resolved to the collection name "Trailer Park Boys" via
     * the grandParentFolder block (parentIsExtras triggers fallback).
     */
    @Test
    void testTPBXtrasFolder() {
        String filename = "Trailer Park Boys - Live in Dublin (2014).mp4";
        String relativePath = "TV Shows/Trailer Park Boys S01-S12 (1999-) + Movies + Spinoffs/Xtras/Trailer Park Boys - Live in Dublin (2014).mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("Trailer Park Boys", result.showName,
                "showName resolves to 'Trailer Park Boys' via grandParentFolder cleanShowName");
        assertEquals("episode", result.mediaType,
                "mediaType is 'episode' (movieFilmWordPattern no longer matches 'Movies' mid-name in collection)");
        assertEquals(1, result.season,
                "season=1 via PathStructureDefault (showFolder present, no seasonFolder found)");
    }

    /**
     * SxxXep pattern (S10X01) in a spinoff file.
     * Note: CONTENT_TYPE_SXXXN pattern matches before CONTENT_TYPE_SXXXEP because
     * SXXXN's `X(\d{1,2})` also captures "X01". This means contentType="extra" rather
     * than the episode being detected by SXXXEP. This documents the current code behavior.
     */
    @Test
    void testTPBSxxXepSpinoff() {
        String filename = "Trailer Park Boys S10X01 Out of the Park Europe (2016-).mp4";
        String relativePath = "TV Shows/Trailer Park Boys S01-S12 (1999-) + Movies + Spinoffs/Trailer Park Boys S10X01 Out of the Park Europe (2016-).mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertNotNull(result.showName, "showName should not be null");
        assertEquals("Trailer Park Boys", result.showName,
                "showName should be 'Trailer Park Boys'");
        assertEquals(10, result.season, "season should be 10 from S10X01");
        // CONTENT_TYPE_SXXXN matches "S10X01" before CONTENT_TYPE_SXXXEP because
        // \d{1,2} captures "01" from "X01". This sets contentType="extra" and episode=null.
        assertEquals("extra", result.contentType,
                "contentType should be 'extra' (SXXXN matches before SXXXEP for S10X01)");
    }

    /**
     * S00M01 = season 0 movie (specials).
     */
    @Test
    void testTPBS00M01SeasonZeroMovie() {
        String filename = "Trailer Park Boys S00M01 (1999 360p re-dvdrip).mp4";
        String relativePath = "TV Shows/Trailer Park Boys S01-S12 (1999-) + Movies + Spinoffs/Trailer Park Boys S00M01 (1999 360p re-dvdrip).mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("movie", result.mediaType,
                "mediaType should be 'movie' due to SxxMxx pattern and/or folder hint");
        assertNotNull(result.showName, "showName should not be null");
        assertEquals("Trailer Park Boys", result.showName,
                "showName should be 'Trailer Park Boys'");
        assertEquals(0, result.season, "season should be 0 from S00M01");
        assertEquals("movie", result.contentType, "contentType should be 'movie' from SxxMxx");
    }

    // ── P10: Pattern Unit Tests (via reflection) ─────────────────────────

    @Test
    void testExtrasFolderPatternMatchesXtras() throws Exception {
        Pattern pattern = getPattern("EXTRAS_FOLDER_PATTERN");
        assertTrue(pattern.matcher("Xtras").find(),
                "EXTRAS_FOLDER_PATTERN should match 'Xtras'");
    }

    @Test
    void testExtrasFolderPatternMatchesExtras() throws Exception {
        Pattern pattern = getPattern("EXTRAS_FOLDER_PATTERN");
        assertTrue(pattern.matcher("extras").find(),
                "EXTRAS_FOLDER_PATTERN should match 'extras'");
    }

    @Test
    void testExtrasFolderPatternMatchesVolExtras() throws Exception {
        Pattern pattern = getPattern("EXTRAS_FOLDER_PATTERN");
        assertTrue(pattern.matcher("Vol. 1 Extras").find(),
                "EXTRAS_FOLDER_PATTERN should match 'Vol. 1 Extras'");
    }

    @Test
    void testExtrasFolderPatternMatchesBehindTheScenes() throws Exception {
        Pattern pattern = getPattern("EXTRAS_FOLDER_PATTERN");
        assertTrue(pattern.matcher("behind the scenes").find(),
                "EXTRAS_FOLDER_PATTERN should match 'behind the scenes'");
    }

    @Test
    void testContentTypeSxxxepPattern() throws Exception {
        Pattern pattern = getPattern("CONTENT_TYPE_SXXXEP");
        Matcher matcher = pattern.matcher("S10X01");
        assertTrue(matcher.find(), "CONTENT_TYPE_SXXXEP should match 'S10X01'");
        assertEquals("10", matcher.group(1), "group(1) should be '10' (season)");
        assertEquals("01", matcher.group(2), "group(2) should be '01' (episode)");
    }

    @Test
    void testContentTypeSxxMxxPattern() throws Exception {
        Pattern pattern = getPattern("CONTENT_TYPE_SXXMXX");
        Matcher matcher = pattern.matcher("S06M01");
        assertTrue(matcher.find(), "CONTENT_TYPE_SXXMXX should match 'S06M01'");
        assertEquals("06", matcher.group(1), "group(1) should be '06' (season)");
        assertEquals("01", matcher.group(2), "group(2) should be '01' (movie number)");
    }
}
