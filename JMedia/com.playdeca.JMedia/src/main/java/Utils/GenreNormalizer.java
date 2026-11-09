package Utils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure genre-string resolver. No CDI, DB, HTTP or framework imports.
 *
 * <p>Resolves every genre a raw tag belongs to. Whole-string aliases are checked
 * first (so {@code R&B}, {@code Drum & Bass} and {@code Hip-Hop} are not split
 * apart), then explicit list separators, then a guarded {@code &}/{@code and}
 * split. Unmapped values keep a cleaned display name. Null/blank/sentinel
 * values resolve to the single {@link #UNIDENTIFIED_NAME} genre.
 */
public final class GenreNormalizer {

    public static final String UNIDENTIFIED_KEY = "unidentified";
    public static final String UNIDENTIFIED_NAME = "Unidentified";

    public record Genre(String key, String name) {}

    private static final Set<String> SENTINELS = Set.of(
            "", "unknown", "unknown genre", "unidentified", "unclassified",
            "uncategorized", "n a", "na", "none", "no genre", "not tagged",
            "other", "misc", "miscellaneous", "various",
            // Non-English "unknown / other" tags seen in real libraries.
            "sconosciuto", "sconosciuta", "sconosciuti", "sconosciute",
            "desconocido", "desconocida", "desconocidos", "sin genero",
            "inconnu", "inconnue", "unbekannt", "onbekend",
            "altro", "altra", "altri", "altre",
            "otro", "otra", "otros", "otras",
            "autre", "autres", "andere", "outro", "outra", "outros", "outras",
            "nieznany", "neznan", "ei tiedossa"
    );

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    private static void alias(String raw, String canonical) {
        ALIASES.put(key(raw), canonical);
    }

    static {
        alias("hip hop", "Hip-Hop");
        alias("hiphop", "Hip-Hop");
        alias("rap", "Hip-Hop");
        alias("r and b", "R&B");
        alias("rnb", "R&B");
        alias("rhythm and blues", "R&B");
        alias("drum and bass", "Drum & Bass");
        alias("drum n bass", "Drum & Bass");
        alias("dnb", "Drum & Bass");
        alias("rock and roll", "Rock & Roll");
        alias("rock n roll", "Rock & Roll");
        alias("edm", "Electronic");
        alias("electronica", "Electronic");
        alias("dance", "Electronic");
        alias("alt rock", "Alternative Rock");
        alias("alternative", "Alternative Rock");
        alias("heavy metal", "Metal");
        alias("kpop", "K-Pop");
        alias("jpop", "J-Pop");
        alias("lofi", "Lo-Fi");
        alias("lo fi", "Lo-Fi");
        alias("synthpop", "Synth-Pop");
        alias("synth pop", "Synth-Pop");
        alias("post rock", "Post-Rock");
        alias("ost", "Soundtrack");
        alias("original soundtrack", "Soundtrack");
        alias("game music", "Video Game Music");
        alias("video game music", "Video Game Music");
        alias("christmas", "Holiday");
        alias("xmas", "Holiday");
        alias("ac dc", "AC/DC");
        alias("acdc", "AC/DC");
        // Spanish / Portuguese / Italian tags that duplicate an English genre.
        alias("acustico", "Acoustic");
        alias("acustica", "Acoustic");
        alias("gospel", "Gospel");
        alias("clasica", "Classical");
        alias("clasico", "Classical");
        alias("musica clasica", "Classical");
        alias("electronica", "Electronic");
        alias("electronico", "Electronic");
        alias("folclor", "Folk");
        alias("folclore", "Folk");
        alias("rock alternativo", "Alternative Rock");
        alias("alternativa", "Alternative Rock");
        alias("alternativo", "Alternative Rock");
    }

    private GenreNormalizer() {}

    /** Resolve every genre in a raw tag. Never returns an empty list. */
    public static List<Genre> resolveAll(String raw) {
        LinkedHashMap<String, Genre> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return unidentified();
        }
        String cleaned = clean(raw);
        String wholeKey = key(cleaned);
        if (cleaned.isBlank() || SENTINELS.contains(wholeKey)) {
            return unidentified();
        }

        // 1. Whole-string alias wins (protects R&B, Drum & Bass, Hip-Hop, AC/DC).
        String wholeAlias = ALIASES.get(wholeKey);
        if (wholeAlias != null) {
            add(out, key(wholeAlias), wholeAlias);
            return List.copyOf(out.values());
        }

        // 2. Strip one trailing "(...)" / "[...]" and resolve the inner value.
        String stripped = stripTrailingGroup(cleaned);
        if (!stripped.equals(cleaned)) {
            List<Genre> inner = resolveAll(stripped);
            if (!inner.isEmpty()) {
                return inner;
            }
        }

        // 3. Explicit list separators.
        List<String> parts = split(cleaned);
        if (parts.size() > 1) {
            for (String part : parts) {
                Genre g = resolveSingle(part);
                if (g != null) add(out, g.key(), g.name());
            }
        }

        // 4. "&" / "and" only when both sides resolve independently, and never
        //    inside an abbreviation like R&B / D&B where "&" sits between two
        //    single letters — splitting there produced "Alternative R" out of
        //    "Alternative R&B".
        if (out.isEmpty()) {
            int amp = indexOfAnd(cleaned);
            if (amp > 0 && !isAmpersandAbbreviation(cleaned, amp)) {
                String left = cleaned.substring(0, amp);
                String right = cleaned.substring(amp + (cleaned.regionMatches(true, amp, " and ", 0, 5) ? 5 : 1));
                Genre l = resolveSingle(left);
                Genre r = resolveSingle(right);
                if (l != null && r != null) {
                    add(out, l.key(), l.name());
                    add(out, r.key(), r.name());
                }
            }
        }

        // 5. Single value (unmapped values keep their cleaned display).
        if (out.isEmpty()) {
            Genre g = resolveSingle(cleaned);
            if (g != null) add(out, g.key(), g.name());
        }

        return out.isEmpty() ? unidentified() : List.copyOf(out.values());
    }

    /** Primary genre name (first resolved), never null. */
    public static String primaryName(String raw) {
        return resolveAll(raw).get(0).name();
    }

    /** Stable matching key for a raw or canonical genre string. */
    public static String key(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value, Normalizer.Form.NFKC);
        n = n.replace("&", " and ");
        n = n.replace('-', ' ').replace('_', ' ');
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT);
        n = n.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    private static List<Genre> unidentified() {
        return List.of(new Genre(UNIDENTIFIED_KEY, UNIDENTIFIED_NAME));
    }

    private static void add(Map<String, Genre> out, String key, String name) {
        if (key == null || key.isBlank() || out.containsKey(key)) return;
        out.put(key, new Genre(key, name));
    }

    private static Genre resolveSingle(String part) {
        String cleaned = clean(part);
        if (cleaned.isBlank()) return null;
        String k = key(cleaned);
        if (k.isBlank() || SENTINELS.contains(k)) return null;
        String alias = ALIASES.get(k);
        if (alias != null) return new Genre(key(alias), alias);
        return new Genre(k, fixAbbreviations(titleCase(cleaned)));
    }

    private static String clean(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        n = n.replace('\u00A0', ' ');
        n = n.replaceAll("[\\p{Cntrl}\\u200B-\\u200D\\uFEFF]", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (String p : value.split("[/\\\\,;|•·+]")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        return parts;
    }

    private static int indexOfAnd(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' && i > 0 && i < value.length() - 1) return i;
            if (value.regionMatches(true, i, " and ", 0, 5)) return i;
        }
        return -1;
    }

    /**
     * True when the character at {@code index} is "and"/"&" inside a short
     * abbreviation such as R&B or D&B (a single letter on each side), which must
     * not be treated as a list separator.
     */
    private static boolean isAmpersandAbbreviation(String value, int index) {
        if (index < 0 || index >= value.length() || value.charAt(index) != '&') return false;
        if (index < 1 || index + 1 >= value.length()) return false;
        char before = value.charAt(index - 1);
        char after = value.charAt(index + 1);
        if (!Character.isLetter(before) || !Character.isLetter(after)) return false;
        boolean beforeIsSingle = index < 2 || !Character.isLetter(value.charAt(index - 2));
        boolean afterIsSingle = index + 2 >= value.length() || !Character.isLetter(value.charAt(index + 2));
        return beforeIsSingle && afterIsSingle;
    }

    /** Restore the display form of abbreviations after title-casing. */
    private static String fixAbbreviations(String value) {
        return value
                .replaceAll("(?i)\\br&b\\b", "R&B")
                .replaceAll("(?i)\\bd&b\\b", "D&B");
    }

    private static String stripTrailingGroup(String value) {
        String v = value.trim();
        if (v.endsWith(")") || v.endsWith("]")) {
            char open = v.endsWith(")") ? '(' : '[';
            int idx = v.lastIndexOf(open);
            if (idx > 0) return v.substring(0, idx).trim();
        }
        return value;
    }

    private static String titleCase(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            String[] hy = word.split("-", -1);
            for (int i = 0; i < hy.length; i++) {
                if (i > 0) b.append('-');
                if (hy[i].isEmpty()) continue;
                b.append(Character.toUpperCase(hy[i].charAt(0))).append(hy[i].substring(1));
            }
        }
        return b.toString();
    }
}
