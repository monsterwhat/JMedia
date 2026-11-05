package Utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenreNormalizerTest {

    private static List<String> names(String raw) {
        return GenreNormalizer.resolveAll(raw).stream().map(GenreNormalizer.Genre::name).toList();
    }

    @Test
    void blankAndSentinelsBecomeUnidentified() {
        assertEquals(List.of("Unidentified"), names(null));
        assertEquals(List.of("Unidentified"), names("   "));
        assertEquals(List.of("Unidentified"), names("Unknown"));
        assertEquals(List.of("Unidentified"), names("Unknown Genre"));
        assertEquals(List.of("Unidentified"), names("N/A"));
        assertEquals(List.of("Unidentified"), names("Other"));
        assertEquals(List.of("Unidentified"), names("Various"));
    }

    @Test
    void explicitSeparatorsResolveEveryGenre() {
        assertEquals(List.of("Rock", "Pop"), names("Rock/Pop"));
        assertEquals(List.of("Rock", "Pop", "Metal"), names("Rock, Pop, Metal"));
        assertEquals(List.of("Rock", "Pop"), names("Rock | Pop"));
        assertEquals(List.of("Rock", "Pop"), names("Rock • Pop"));
        assertEquals(List.of("Rock", "Pop"), names("Rock; Pop"));
    }

    @Test
    void wholeStringAliasesWin() {
        assertEquals(List.of("Hip-Hop"), names("Hip-Hop"));
        assertEquals(List.of("Hip-Hop"), names("Hip Hop"));
        assertEquals(List.of("Hip-Hop"), names("HipHop"));
        assertEquals(List.of("R&B"), names("R&B"));
        assertEquals(List.of("R&B"), names("RnB"));
        assertEquals(List.of("R&B"), names("Rhythm and Blues"));
        assertEquals(List.of("Drum & Bass"), names("Drum & Bass"));
        assertEquals(List.of("Drum & Bass"), names("Drum and Bass"));
        assertEquals(List.of("Rock & Roll"), names("Rock and Roll"));
        assertEquals(List.of("AC/DC"), names("AC/DC"));
    }

    @Test
    void multiGenreDeduplicatesOnCanonicalKey() {
        assertEquals(List.of("Hip-Hop"), names("Hip-Hop/Rap"));
        assertEquals(List.of("R&B", "Soul"), names("R&B/Soul"));
    }

    @Test
    void unmappedValuesKeepACleanedDisplayName() {
        assertEquals(List.of("Shoegaze"), names("shoegaze"));
        assertEquals(List.of("M\u00FAsica"), names("M\u00FAsica"));
        assertEquals(List.of("Alternative Rock"), names("Alternative Rock"));
        assertEquals(List.of("Electronic"), names("EDM"));
        assertEquals(List.of("Trip-Hop"), names("trip-hop"));
    }

    @Test
    void whitespaceAndParentheticalsAreHandled() {
        assertEquals(List.of("Rock"), names("  Rock  "));
        assertEquals(List.of("Rock"), names("Rock (Live)"));
        assertEquals(List.of("Rock"), names("Rock [Remastered]"));
    }

    @Test
    void ampersandSplitsOnlyWhenBothSidesResolve() {
        assertEquals(List.of("Rock", "Pop"), names("Rock & Pop"));
        assertEquals(List.of("Rock", "Pop"), names("Rock and Pop"));
    }

    @Test
    void caseAndAccentsAreFoldedForMatchingOnly() {
        assertEquals(List.of("Rock"), names("ROCK"));
        assertEquals(List.of("Rock"), names("rock"));
        assertEquals(List.of("M\u00FAsica"), names("M\u00FAsica"));
    }

    @Test
    void turkishLocaleDoesNotBreakMatching() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(List.of("Indie"), names("Indie"));
            assertEquals(List.of("Indie"), names("indie"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void everyPrefixOfAListIsResolved() {
        assertEquals(List.of("Pop", "Rock", "Electronic"), names("Pop/Rock/Electronic"));
        assertEquals(List.of("Rock", "Pop", "Metal", "Jazz"), names("Rock, Pop, Metal, Jazz"));
    }
}
