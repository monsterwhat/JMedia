package Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Curated parent "families" for the grouped genre view.
 *
 * <p>Genres are stored flat in {@code SongGenre}. This class maps each
 * canonical genre to one or more broad families (Alternative, Rock, Metal, …)
 * so the UI can show a general genre and include every subgenre under it.
 * Matching uses normalized genre keys and whole-phrase boundaries, never raw
 * substring checks, so "rock" does not match "rocksteady".
 *
 * <p>A genre may belong to several families on purpose: "Alternative Rock"
 * appears under both Alternative and Rock, and "Folk Rock" under Folk and Rock.
 */
public final class GenreFamilies {

    public record Family(String key, String name, List<String> keywords, List<String> explicitMembers) {}

    /** Display order of the grouped view. */
    private static final List<Family> FAMILIES = List.of(
            new Family("alternative", "Alternative",
                    List.of("alternative"),
                    List.of("alt rock", "britpop", "shoegaze", "grunge", "emo", "screamo",
                            "indie rock", "indie pop", "madchester", "new wave", "post punk")),
            new Family("rock", "Rock",
                    List.of("rock", "punk", "grunge", "shoegaze", "emo", "hardcore",
                            "post rock", "surf rock", "garage rock", "psychedelic rock",
                            "southern rock", "progressive rock", "math rock"),
                    List.of("rockabilly", "britpop", "indie rock", "alternative rock", "pop rock")),
            new Family("metal", "Metal",
                    List.of("metal", "metalcore", "deathcore", "thrash", "doom", "djent",
                            "grindcore", "sludge", "stoner", "death metal", "black metal",
                            "power metal", "progressive metal", "nu metal", "symphonic metal"),
                    List.of()),
            new Family("pop", "Pop",
                    List.of("pop", "synth pop", "dream pop", "city pop", "k pop", "j pop",
                            "indie pop", "electropop", "dance pop", "teen pop", "power pop",
                            "bubblegum", "chamber pop"),
                    List.of("synthpop")),
            new Family("hip-hop", "Hip-Hop",
                    List.of("hip hop", "rap", "trap", "drill", "boom bap", "grime",
                            "phonk", "trip hop", "gangsta rap", "conscious hip hop"),
                    List.of("hiphop")),
            new Family("rnb-soul", "R&B / Soul",
                    List.of("r and b", "rnb", "soul", "neo soul", "funk", "motown",
                            "quiet storm", "contemporary r and b"),
                    List.of("rhythm and blues")),
            new Family("electronic", "Electronic",
                    List.of("electronic", "edm", "house", "techno", "trance", "dubstep",
                            "drum and bass", "dnb", "ambient", "idm", "breakbeat",
                            "hardstyle", "synthwave", "downtempo", "chillout", "electro",
                            "big beat", "uk garage", "speed garage", "future bass"),
                    List.of("electronica")),
            new Family("jazz", "Jazz",
                    List.of("jazz", "bebop", "swing", "fusion", "smooth jazz", "acid jazz",
                            "ragtime", "dixieland", "cool jazz", "hard bop", "free jazz"),
                    List.of()),
            new Family("classical", "Classical",
                    List.of("classical", "baroque", "romantic", "opera", "orchestral",
                            "chamber", "piano", "symphony", "choral", "concerto"),
                    List.of("classical crossover")),
            new Family("folk", "Folk",
                    List.of("folk", "singer songwriter", "americana", "celtic", "bluegrass",
                            "country folk", "acoustic", "fingerstyle"),
                    List.of()),
            new Family("country", "Country",
                    List.of("country", "bluegrass", "honky tonk", "outlaw country", "western",
                            "country rock", "country pop"),
                    List.of()),
            new Family("blues", "Blues",
                    List.of("blues", "delta blues", "electric blues", "chicago blues",
                            "jump blues", "piedmont blues"),
                    List.of()),
            new Family("reggae", "Reggae",
                    List.of("reggae", "dancehall", "dub", "ska", "rocksteady", "ragga"),
                    List.of()),
            new Family("latin", "Latin",
                    List.of("latin", "salsa", "bachata", "merengue", "cumbia", "reggaeton",
                            "bossa nova", "tango", "latin pop", "tejano", "vallenato"),
                    List.of()),
            new Family("world", "World",
                    List.of("world", "afrobeat", "afro", "flamenco", "india", "asian",
                            "middle eastern", "aboriginal", "tribal", "ethnic", "caribbean"),
                    List.of("afro fusion", "afro house")),
            new Family("gospel", "Gospel",
                    List.of("gospel", "christian", "worship", "spiritual", "religious"),
                    List.of("contemporary christian")),
            new Family("soundtrack", "Soundtrack",
                    List.of("soundtrack", "score", "ost", "video game", "anime", "musical",
                            "film", "tv theme"),
                    List.of("original soundtrack", "game music")),
            new Family("holiday", "Holiday",
                    List.of("holiday", "christmas", "xmas", "festive", "hanukkah"),
                    List.of()),
            new Family("new-age", "New Age",
                    List.of("new age", "meditation", "relaxation", "healing", "spa"),
                    List.of()),
            new Family("easy-listening", "Easy Listening",
                    List.of("easy listening", "lounge", "chillout", "downtempo",
                            "adult contemporary", "soft rock"),
                    List.of()),
            new Family("spoken-word", "Spoken Word",
                    List.of("spoken word", "audiobook", "podcast", "comedy", "poetry",
                            "speech"),
                    List.of()),
            new Family("children", "Children's",
                    List.of("children", "kids", "family", "nursery"),
                    List.of()),
            new Family("karaoke", "Karaoke",
                    List.of("karaoke"),
                    List.of())
    );

    private GenreFamilies() {}

    public static List<Family> all() {
        return FAMILIES;
    }

    public static Family find(String key) {
        if (key == null) return null;
        for (Family family : FAMILIES) {
            if (family.key().equalsIgnoreCase(key)) return family;
        }
        return null;
    }

    /** All families a canonical genre belongs to (may be empty). */
    public static List<Family> match(String genreKey, String genreName) {
        String key = genreKey != null && !genreKey.isBlank()
                ? genreKey
                : GenreNormalizer.key(genreName);
        if (key == null || key.isBlank()) return List.of();

        List<Family> matches = new ArrayList<>();
        for (Family family : FAMILIES) {
            if (matchesFamily(family, key)) matches.add(family);
        }
        return matches;
    }

    private static boolean matchesFamily(Family family, String genreKey) {
        for (String explicit : family.explicitMembers()) {
            String member = GenreNormalizer.key(explicit);
            if (!member.isBlank() && genreKey.equals(member)) return true;
        }
        for (String keyword : family.keywords()) {
            String needle = GenreNormalizer.key(keyword);
            if (!needle.isBlank() && containsPhrase(genreKey, needle)) return true;
        }
        return false;
    }

    /** Whole-phrase match, so "rock" never matches "rocksteady". */
    private static boolean containsPhrase(String haystack, String needle) {
        if (haystack.equals(needle)) return true;
        return haystack.startsWith(needle + " ")
                || haystack.endsWith(" " + needle)
                || haystack.contains(" " + needle + " ");
    }
}
