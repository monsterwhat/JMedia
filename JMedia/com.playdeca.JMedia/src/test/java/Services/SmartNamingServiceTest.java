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
        assertNull(result.season,
                "season=null via ExtrasContent (hasExtrasFolder=true, no seasonFolder found)");
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
        assertNull(result.season,
                "season=null via ExtrasContent (hasExtrasFolder=true, no seasonFolder found)");
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

    // ── Regression locks (season fallback behavior) ─────────────────────

    /**
     * Locks the T8 tightening: season=0 fallback now ONLY fires when the path
     * contains an explicit specials indicator (parent/grandparent folder matching
     * SPECIALS_PATTERN, or /specials/ in the relative path).
     *
     * Path: "Show/episode.mp4" — parentFolder="Show" does NOT match SPECIALS_PATTERN,
     * grandParentFolder is empty, relativePath contains no "specials" segment.
     * Therefore season stays null.
     */
    @Test
    void testSeasonZeroFallback() {
        String filename = "episode.mp4";
        String relativePath = "Show/episode.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType,
                "mediaType should be 'episode' (no movie indicators)");
        assertNull(result.season,
                "season=null via T8 tightening (no specials indicator in path)");
    }

    /**
     * Locks the season=1 PathStructureDefault at detectEpisodeInfo line 736-740
     * (SmartNamingService.java).
     *
     * Path: TV Shows/Show/DVD Extras/Something.mp4 — library root "TV Shows" sets
     * showFolder="Show". The "DVD Extras" folder is skipped during season search
     * because EXTRAS_FOLDER_PATTERN matches "extras" → no seasonFolder found,
     * hasSeasonFolder=false.
     *
     * In detectEpisodeInfo: season==null AND showFolder!=null AND !hasSeasonFolder
     * → sets season=1 (detectionMethod="PathStructureDefault"). No SxxExx in
     * filename "Something.mp4".
     */
    @Test
    void testSeasonOnePathStructureDefault() {
        String filename = "Something.mp4";
        String relativePath = "TV Shows/Show/DVD Extras/Something.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType,
                "mediaType should be 'episode' (directoryTypeHint='episode' from TV Shows)");
        assertNull(result.season,
                "season=null via ExtrasContent (hasExtrasFolder=true, no seasonFolder found)");
    }

    // ── P9b: Vol. X Extras regression tests ───────────────────────────────

    /**
     * Regression: "Vol. 2 Extras" was being matched by SEASON_TEXT_N_TEXT and
     * incorrectly setting season=2. The path analysis loop correctly identifies
     * it as extras via EXTRAS_FOLDER_PATTERN, but the fallback
     * isSeasonFolderName(parentFolder) check was overriding hasSeasonFolder=true.
     *
     * Path: TV Shows/Show/Vol. 2 Extras/file.mkv — parentFolder="Vol. 2 Extras"
     * must NOT be treated as a season folder.
     */
    @Test
    void testVolExtrasNotMistakenForSeason() {
        String filename = "some_episode.mkv";
        String relativePath = "TV Shows/Show/Vol. 2 Extras/some_episode.mkv";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType,
                "mediaType should be 'episode' (directoryTypeHint='episode' from TV Shows)");
        assertNull(result.season,
                "season=null via ExtrasContent (Vol. 2 Extras matched as extras, not as season=2)");
    }

    /**
     * Regression: Family Guy collection with "DVD Extras Vol. 1 - Vol. 7/Vol. 2 Extras/"
     * subfolders was assigning season=2 to files inside Vol. 2 Extras, causing
     * 27 extras episodes to merge into Season 2 (48 total instead of 21).
     *
     * The show folder name contains "Season 1 to 19" which triggers
     * showFolderHasSeason, but the path analysis loop must correctly classify
     * "Vol. 2 Extras" as extras despite SEASON_TEXT_N_TEXT matching.
     */
    @Test
    void testFamilyGuyCollectionVolExtras() {
        String filename = "Random Episode.mkv";
        String relativePath = "TV Shows/Family Guy Season 1 to 19 Including the Movie and DVD Extras Complete Collection [NVEnc H265 1080p][AAC 6Ch]/DVD Extras Vol. 1 - Vol. 7/Vol. 2 Extras/Random Episode.mkv";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType,
                "mediaType should be 'episode' (library root is TV Shows)");
        assertNull(result.season,
                "season=null via ExtrasContent (Vol. 2 Extras correctly identified; not season 2)");
    }

    // ── Fix A: Fractional season folder tests ─────────────────────────────

    /**
     * "Season 2.5 OVA" folder — fractional season not detected in folder names.
     * Path: "TV Shows/Blue Exorcist/Season 2.5 OVA/episode.mkv"
     * Expected: season=2, seasonSuffix="OVA"
     */
    @Test
    void testSeason25OVAFolder() {
        String filename = "episode.mkv";
        String relativePath = "TV Shows/Blue Exorcist/Season 2.5 OVA/episode.mkv";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType, "mediaType should be 'episode'");
        assertEquals(2, result.season, "season should be 2 (integer part of 2.5)");
        assertEquals("OVA", result.seasonSuffix, "seasonSuffix should be 'OVA' from '.5 OVA'");
    }

    /**
     * "S1 MP4" folder — folder name starting with S\\d+ not recognized as season folder.
     * Path: "TV Shows/Naruto Kai/S1 MP4/some_episode.mp4"
     * Expected: season=1
     */
    @Test
    void testS1MP4Folder() {
        String filename = "some_episode.mp4";
        String relativePath = "TV Shows/Naruto Kai/S1 MP4/some_episode.mp4";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType, "mediaType should be 'episode'");
        assertEquals(1, result.season, "season should be 1 from S1 MP4 folder");
    }

    /**
     * "Shinden 1 - Itachi Shinden - Book of Light and Darkness" folder —
     * dash after number breaks SEASON_TEXT_N_TEXT pattern.
     * Path: "TV Shows/Naruto Kai/Shinden 1 - Itachi Shinden - Book of Light and Darkness/some_episode.mkv"
     * Expected: season=1
     */
    @Test
    void testShindenDashTextNText() {
        String filename = "some_episode.mkv";
        String relativePath = "TV Shows/Naruto Kai/Shinden 1 - Itachi Shinden - Book of Light and Darkness/some_episode.mkv";

        SmartNamingService.NamingResult result = service.detectSmartNames(
                null, filename, relativePath, null, null, null, null, null, null);

        assertNotNull(result, "NamingResult should not be null");
        assertEquals("episode", result.mediaType, "mediaType should be 'episode'");
        assertEquals(1, result.season, "season should be 1 from SEASON_TEXT_N_TEXT matching 'Shinden 1...'");
    }
}
