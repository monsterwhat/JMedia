package Services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4a sibling-inference tests (offline, plain JUnit, no network): the
 * conservative season-folder parser, detection-method eligibility gating,
 * on-disk lengths-map construction via {@code @TempDir} fixtures, and the
 * resolver applied to a disk-derived map.
 */
public class SiblingInferenceTest {

    // ── Season folder parser ─────────────────────────────────────────

    @Test
    void parsesUnambiguousSeasonFolders() {
        assertEquals(2, VideoImportService.parseSeasonFolderName("Season 2"));
        assertEquals(2, VideoImportService.parseSeasonFolderName("season 02"));
        assertEquals(1, VideoImportService.parseSeasonFolderName("S01"));
        assertEquals(3, VideoImportService.parseSeasonFolderName("s3"));
        assertEquals(2, VideoImportService.parseSeasonFolderName("2"));
        assertEquals(3, VideoImportService.parseSeasonFolderName("T03"));
        assertEquals(1, VideoImportService.parseSeasonFolderName("Temporada 1"));
        assertEquals(4, VideoImportService.parseSeasonFolderName("Temp 4"));
    }

    @Test
    void rejectsAmbiguousFolderNames() {
        assertNull(VideoImportService.parseSeasonFolderName(null));
        assertNull(VideoImportService.parseSeasonFolderName("Featurette"));
        assertNull(VideoImportService.parseSeasonFolderName("Vol. 2 Extras"),
                "extras folders must never read as seasons");
        assertNull(VideoImportService.parseSeasonFolderName("Season 2.5 OVA"),
                "fractional/annotated folders are ambiguous");
        assertNull(VideoImportService.parseSeasonFolderName("S1 MP4"),
                "trailing quality tags are ambiguous");
        assertNull(VideoImportService.parseSeasonFolderName("Shinden 1 - Itachi Shinden"),
                "arc names must not parse as seasons here");
        assertNull(VideoImportService.parseSeasonFolderName(""));
        assertNull(VideoImportService.parseSeasonFolderName("Season 0"),
                "season 0 is not a valid inference base");
    }

    // ── Eligibility gating ───────────────────────────────────────────

    @Test
    void bareNumberedMethodsAreEligible() {
        assertTrue(SmartNamingService.isSiblingInferenceEligible("DashNumber"));
        assertTrue(SmartNamingService.isSiblingInferenceEligible("ShowNumberDash+PathSeason"));
        assertTrue(SmartNamingService.isSiblingInferenceEligible("BareNumber"));
        assertTrue(SmartNamingService.isSiblingInferenceEligible("PathStructure"),
                "PathStructure episodes come from non-returning Dash/Bare patterns");
    }

    @Test
    void providerRemappedAndExplicitMethodsAreExcluded() {
        assertFalse(SmartNamingService.isSiblingInferenceEligible("ShowNumberDash+Absolute"),
                "provider already handled this row");
        assertFalse(SmartNamingService.isSiblingInferenceEligible("DashNumber+Absolute"));
        assertFalse(SmartNamingService.isSiblingInferenceEligible("SxxExx"),
                "explicit season in the name is never remapped");
        assertFalse(SmartNamingService.isSiblingInferenceEligible("XxY"));
        assertFalse(SmartNamingService.isSiblingInferenceEligible(null));
    }

    @Test
    void detectionMethodIsExposedOnNamingResult() {
        SmartNamingService service = new SmartNamingService();
        SmartNamingService.NamingResult bare = service.detectSmartNames(
                null, "5 - Title.mp4", "TV Shows/Show/Season 2/5 - Title.mp4",
                null, null, null, null, null, null);
        assertNotNull(bare.detectionMethod, "detectSmartNames must expose the method");
        assertTrue(SmartNamingService.isSiblingInferenceEligible(bare.detectionMethod),
                "bare-numbered file must be inference-eligible, got: " + bare.detectionMethod);

        SmartNamingService.NamingResult explicit = service.detectSmartNames(
                null, "Show - S02E05 - Title.mkv", "TV Shows/Show/Season 2/Show - S02E05 - Title.mkv",
                null, null, null, null, null, null);
        assertFalse(SmartNamingService.isSiblingInferenceEligible(explicit.detectionMethod),
                "explicit SxxExx must not be inference-eligible");
    }

    // ── On-disk lengths map (@TempDir) ───────────────────────────────

    private static void touchVideoFile(Path dir, String name) throws Exception {
        Files.createFile(dir.resolve(name));
    }

    @Test
    void buildsContiguousLengthsFromDisk(@TempDir Path showDir) throws Exception {
        Path season1 = Files.createDirectory(showDir.resolve("Season 1"));
        touchVideoFile(season1, "a.mkv");
        touchVideoFile(season1, "b.mkv");
        touchVideoFile(season1, "c.mp4");
        Files.createFile(season1.resolve("notes.txt")); // non-video: ignored
        Path season2 = Files.createDirectory(showDir.resolve("Season 2"));
        touchVideoFile(season2, "d.mkv");
        touchVideoFile(season2, "e.mkv");

        Map<Integer, Integer> lengths = new VideoImportService().buildSiblingLengths(showDir, 2);
        assertEquals(new TreeMap<>(Map.of(1, 3, 2, 2)), new TreeMap<>(lengths));
    }

    @Test
    void missingSeasonFolderSkips(@TempDir Path showDir) throws Exception {
        Path season2 = Files.createDirectory(showDir.resolve("Season 2"));
        touchVideoFile(season2, "d.mkv");

        assertNull(new VideoImportService().buildSiblingLengths(showDir, 2),
                "season 1 missing on disk must skip");
    }

    @Test
    void emptySeasonFolderSkips(@TempDir Path showDir) throws Exception {
        Path season1 = Files.createDirectory(showDir.resolve("Season 1"));
        touchVideoFile(season1, "a.mkv");
        Files.createDirectory(showDir.resolve("Season 2")); // present but empty

        assertNull(new VideoImportService().buildSiblingLengths(showDir, 2),
                "empty season folder must skip");
    }

    @Test
    void duplicateSeasonFoldersSkip(@TempDir Path showDir) throws Exception {
        Path season1 = Files.createDirectory(showDir.resolve("Season 1"));
        touchVideoFile(season1, "a.mkv");
        Path alias = Files.createDirectory(showDir.resolve("S01"));
        touchVideoFile(alias, "b.mkv");
        Path season2 = Files.createDirectory(showDir.resolve("Season 2"));
        touchVideoFile(season2, "d.mkv");

        assertNull(new VideoImportService().buildSiblingLengths(showDir, 2),
                "two folders parsing to the same season is ambiguous");
    }

    @Test
    void resolverAppliesToDiskDerivedMap(@TempDir Path showDir) throws Exception {
        Path season1 = Files.createDirectory(showDir.resolve("Season 1"));
        touchVideoFile(season1, "a.mkv");
        touchVideoFile(season1, "b.mkv");
        touchVideoFile(season1, "c.mkv");
        Path season2 = Files.createDirectory(showDir.resolve("Season 2"));
        touchVideoFile(season2, "d.mkv");
        touchVideoFile(season2, "e.mkv");

        Map<Integer, Integer> lengths = new VideoImportService().buildSiblingLengths(showDir, 2);
        assertNotNull(lengths);
        // Absolute 4 with S1=3 on disk lives in S2's range [4,5] -> S02E01.
        int[] remapped = SmartNamingService.resolveAbsoluteNumber(2, 4, lengths);
        assertNotNull(remapped);
        assertArrayEquals(new int[]{2, 1}, remapped);
        // Already-relative numbers and out-of-range numbers refuse.
        assertNull(SmartNamingService.resolveAbsoluteNumber(2, 2, lengths),
                "number inside an earlier season keeps the folder reading");
        assertNull(SmartNamingService.resolveAbsoluteNumber(2, 9, lengths),
                "number beyond all known ranges refuses");
    }
}
