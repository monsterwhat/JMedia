package Services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class M3uParserService {

    // Matches #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Channel Name
    private static final Pattern EXTINF_PATTERN = Pattern.compile(
        "^#EXTINF:(-?\\d+)(.*)$"
    );

    // Matches key="value" or key=value pairs inside the attribute string
    private static final Pattern ATTR_PATTERN = Pattern.compile(
        "(tvg-id|tvg-name|tvg-logo|group-title|tvg-country|tvg-language|radio|tvg-type|catchup|catchup-type|catchup-source|catchup-days|catchup-time|catchup-enable|catchup-correction|tvg-rec|timeshift)\\s*=\\s*\"([^\"]*)\""
    );

    private static final Pattern ATTR_UNQUOTED_PATTERN = Pattern.compile(
        "(tvg-id|tvg-name|tvg-logo|group-title|tvg-country|tvg-language|radio|tvg-type|catchup|catchup-type|catchup-source|catchup-days|catchup-time|catchup-enable|catchup-correction|tvg-rec|timeshift)\\s*=\\s*([^\\s,]+)"
    );

    public static class M3uEntry {
        public String name;
        public String streamUrl;
        public String logoUrl;
        public String groupTitle;
        /** All group-title memberships (semicolon-separated in the source). */
        public List<String> groupTitles = new ArrayList<>();
        public String tvgId;
        public String tvgName;
        public String country;
        public Boolean isRadio = false;
        /** Catch-up mode, archive URL template and retention window. */
        public String catchupType;
        public String catchupSource;
        public Integer catchupDays;
        public Double catchupCorrectionHours;
        /** catchup-enable="0" explicitly disables the header default. */
        public Boolean catchupEnabled;
        public double duration = -1;
    }

    /**
     * Fetch M3U content from a remote URL.
     */
    public String fetchM3uContent(String url) throws Exception {
        url = extractUrl(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 JMedia-M3U-Parser");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        int status = conn.getResponseCode();
        if (status >= 400) {
            conn.disconnect();
            throw new Exception("HTTP " + status + " when fetching M3U from " + url);
        }

        try (InputStream is = conn.getInputStream()) {
            byte[] data = is.readAllBytes();
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Extract a URL from a string that may contain extra text (e.g. "United States https://...").
     * Returns the trimmed string if it already looks like a valid URL.
     */
    private String extractUrl(String input) {
        if (input == null) return input;
        input = input.trim();
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }
        int idx = input.indexOf("http://");
        if (idx < 0) idx = input.indexOf("https://");
        if (idx > 0) {
            return input.substring(idx).trim();
        }
        return input;
    }

    /**
     * Parse M3U text content into a list of entries.
     */
    public List<M3uEntry> parse(String m3uContent) {
        List<M3uEntry> entries = new ArrayList<>();
        if (m3uContent == null || m3uContent.isBlank()) {
            return entries;
        }

        String[] lines = m3uContent.split("\\r?\\n");
        M3uEntry pending = null;
        M3uEntry headerDefaults = new M3uEntry();
        String persistentGroup = null;
        // Group inherited from the persistent directive when this #EXTINF was read.
        // pendingGroupOpen stays true until an explicit group-title or a later
        // #EXTGRP decides the group, so either can still win.
        String pendingInheritedGroup = null;
        boolean pendingGroupOpen = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines. #EXTM3U can declare playlist-wide catch-up defaults.
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("#EXTM3U")) {
                continue;
            }
            if (trimmed.length() > 7 && trimmed.substring(0, 7).equalsIgnoreCase("#EXTM3U")) {
                parseAttributes(headerDefaults, trimmed.substring(7));
                continue;
            }

            // Kodi-style group directive: applies to a pending #EXTINF without an
            // explicit group-title, and persists for the following entries. An empty
            // value clears it.
            if (trimmed.startsWith("#EXTGRP:")) {
                String value = trimmed.substring("#EXTGRP:".length()).trim();
                persistentGroup = value.isEmpty() ? null : value;
                if (pending != null && pendingGroupOpen) {
                    setGroupTitles(pending, persistentGroup);
                    pendingGroupOpen = false;
                    pendingInheritedGroup = null;
                }
                continue;
            }

            if (trimmed.startsWith("#EXTINF:")) {
                pending = parseExtInf(trimmed);
                applyHeaderDefaults(pending, headerDefaults);
                pendingGroupOpen = pending.groupTitles.isEmpty();
                pendingInheritedGroup = pendingGroupOpen ? persistentGroup : null;
            } else if (!trimmed.startsWith("#")) {
                // This line is a stream URL
                if (pending != null) {
                    if (pendingGroupOpen && pendingInheritedGroup != null) {
                        setGroupTitles(pending, pendingInheritedGroup);
                    }
                    pending.streamUrl = trimmed;
                    entries.add(pending);
                    pending = null;
                    pendingGroupOpen = false;
                    pendingInheritedGroup = null;
                } else {
                    // Standalone URL without #EXTINF metadata
                    M3uEntry entry = new M3uEntry();
                    entry.streamUrl = trimmed;
                    entry.name = extractNameFromUrl(trimmed);
                    entries.add(entry);
                }
            }
            // Other # lines (e.g., #EXTVLCOPT) are skipped
        }

        return entries;
    }

    /**
     * Parse a single #EXTINF line.
     * Format: #EXTINF:duration attrs,Channel Name
     */
    private M3uEntry parseExtInf(String line) {
        M3uEntry entry = new M3uEntry();

        Matcher extInfMatcher = EXTINF_PATTERN.matcher(line);
        if (!extInfMatcher.find()) {
            entry.name = line;
            return entry;
        }

        entry.duration = Double.parseDouble(extInfMatcher.group(1));
        String remainder = extInfMatcher.group(2);

        // Split on comma: everything before the last comma is attributes, after is the display name
        int lastComma = remainder.lastIndexOf(',');
        if (lastComma >= 0) {
            String attrs = remainder.substring(0, lastComma);
            entry.name = remainder.substring(lastComma + 1).trim();
            parseAttributes(entry, attrs);
        } else {
            entry.name = remainder.trim();
        }

        return entry;
    }

    /**
     * Parse key="value" attributes from the attribute string.
     */
    private void parseAttributes(M3uEntry entry, String attrs) {
        // First try quoted values
        Matcher matcher = ATTR_PATTERN.matcher(attrs);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            applyAttribute(entry, key, value);
        }

        // Then try unquoted values for any remaining attributes
        Matcher unquotedMatcher = ATTR_UNQUOTED_PATTERN.matcher(attrs);
        while (unquotedMatcher.find()) {
            String key = unquotedMatcher.group(1);
            String value = unquotedMatcher.group(2);
            // Only apply if not already set by quoted match
            if (!isAttributeSet(entry, key)) {
                applyAttribute(entry, key, value);
            }
        }

        // Derive country if not explicitly set
        if (entry.country == null || entry.country.isBlank()) {
            entry.country = deriveCountry(entry.tvgId, entry.groupTitle);
        }
    }

    private void applyAttribute(M3uEntry entry, String key, String value) {
        switch (key) {
            case "tvg-id" -> entry.tvgId = value;
            case "tvg-name" -> entry.tvgName = value;
            case "tvg-logo" -> entry.logoUrl = value;
            case "group-title" -> setGroupTitles(entry, value);
            case "tvg-country" -> entry.country = value;
            case "tvg-language" -> { /* stored if needed later */ }
            case "radio" -> entry.isRadio = isTruthy(value);
            case "tvg-type" -> {
                if ("radio".equalsIgnoreCase(unquote(value))) entry.isRadio = true;
            }
            case "catchup", "catchup-type" -> entry.catchupType = unquote(value);
            case "catchup-source" -> entry.catchupSource = unescapeAmpersands(unquote(value));
            case "catchup-days" -> entry.catchupDays = parseInteger(unquote(value));
            case "catchup-correction" -> entry.catchupCorrectionHours = parseDecimal(unquote(value));
            case "catchup-time" -> entry.catchupDays = daysFromSeconds(unquote(value));
            case "catchup-enable" -> entry.catchupEnabled = isTruthy(value);
            case "tvg-rec" -> applyTvgRec(entry, value);
            case "timeshift" -> applyTimeshift(entry, value);
            default -> { /* unknown attribute, ignore */ }
        }
    }

    /** Copy playlist-wide catch-up defaults into an entry that did not override them. */
    private static void applyHeaderDefaults(M3uEntry entry, M3uEntry header) {
        if (entry.catchupType == null) entry.catchupType = header.catchupType;
        if (entry.catchupSource == null) entry.catchupSource = header.catchupSource;
        if (entry.catchupDays == null) entry.catchupDays = header.catchupDays;
        if (entry.catchupCorrectionHours == null) entry.catchupCorrectionHours = header.catchupCorrectionHours;
        if (entry.catchupEnabled == null) entry.catchupEnabled = header.catchupEnabled;
    }

    /** Legacy tvg-rec: 0 disables, a positive number is the archive window in days. */
    private static void applyTvgRec(M3uEntry entry, String value) {
        Integer days = parseInteger(unquote(value));
        if (days == null) {
            return;
        }
        if (days <= 0) {
            entry.catchupEnabled = false;
        } else {
            entry.catchupEnabled = true;
            if (entry.catchupDays == null) entry.catchupDays = days;
        }
    }

    /** Legacy timeshift="N": N days of archive, and implies the timeshift mode. */
    private static void applyTimeshift(M3uEntry entry, String value) {
        Integer days = parseInteger(unquote(value));
        if (days == null) {
            return;
        }
        if (days <= 0) {
            entry.catchupEnabled = false;
        } else {
            entry.catchupEnabled = true;
            if (entry.catchupDays == null) entry.catchupDays = days;
            if (entry.catchupType == null) entry.catchupType = "timeshift";
        }
    }

    private static Integer daysFromSeconds(String value) {
        Integer seconds = parseInteger(value);
        if (seconds == null) {
            return null;
        }
        return seconds <= 0 ? 0 : Math.max(1, (seconds + 86_399) / 86_400);
    }

    /** Digits-only parser; never throws and never logs for a bad M3U attribute. */
    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 9) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return null;
            }
        }
        return Integer.parseInt(v);
    }

    /** Decimal parser for catchup-correction (e.g. -1, 0.5, +2). */
    private static Double parseDecimal(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 12) {
            return null;
        }
        int i = 0;
        if (v.charAt(0) == '+' || v.charAt(0) == '-') {
            i++;
        }
        boolean dot = false;
        boolean digit = false;
        for (; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.') {
                if (dot) {
                    return null;
                }
                dot = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                return null;
            }
        }
        return digit ? Double.parseDouble(v) : null;
    }

    /** Replaces the entry's memberships; the first one is the primary group. */
    private static void setGroupTitles(M3uEntry entry, String value) {
        entry.groupTitles.clear();
        if (value != null) {
            for (String part : value.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !entry.groupTitles.contains(trimmed)) {
                    entry.groupTitles.add(trimmed);
                }
            }
        }
        entry.groupTitle = entry.groupTitles.isEmpty() ? null : entry.groupTitles.get(0);
    }

    /** M3U boolean attributes: "true", "1" or "yes" (quoted or not). */
    private static boolean isTruthy(String value) {
        String v = unquote(value);
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v));
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Some playlists XML-escape the archive template's query separators. */
    private static String unescapeAmpersands(String value) {
        return value == null ? null : value.replace("&amp;", "&");
    }

    private boolean isAttributeSet(M3uEntry entry, String key) {
        return switch (key) {
            case "tvg-id" -> entry.tvgId != null;
            case "tvg-name" -> entry.tvgName != null;
            case "tvg-logo" -> entry.logoUrl != null;
            case "group-title" -> entry.groupTitle != null;
            case "tvg-country" -> entry.country != null;
            case "catchup" -> entry.catchupType != null;
            case "catchup-type" -> entry.catchupType != null;
            case "catchup-source" -> entry.catchupSource != null;
            case "catchup-days" -> entry.catchupDays != null;
            case "catchup-time" -> entry.catchupDays != null;
            case "catchup-correction" -> entry.catchupCorrectionHours != null;
            case "catchup-enable" -> entry.catchupEnabled != null;
            case "tvg-rec" -> entry.catchupEnabled != null || entry.catchupDays != null;
            case "timeshift" -> entry.catchupType != null || entry.catchupDays != null;
            default -> false;
        };
    }

    /**
     * Derive country code from tvg-id (e.g., "ESPN.us" -> "us") or group-title.
     */
    private String deriveCountry(String tvgId, String groupTitle) {
        if (tvgId != null && tvgId.contains(".")) {
            String parts[] = tvgId.split("\\.");
            String suffix = parts[parts.length - 1];
            // Only return if it looks like a 2-letter country code
            if (suffix.length() == 2 && suffix.equals(suffix.toLowerCase())) {
                return suffix;
            }
        }
        if (groupTitle != null) {
            String lower = groupTitle.toLowerCase();
            if (lower.contains("us ") || lower.contains("usa") || lower.contains("united states")) return "us";
            if (lower.contains("uk ") || lower.contains("united kingdom") || lower.contains("british")) return "uk";
            if (lower.contains("canada") || lower.contains("ca ")) return "ca";
            if (lower.contains("germany") || lower.contains("deutsch")) return "de";
            if (lower.contains("france") || lower.contains("fran")) return "fr";
            if (lower.contains("spain") || lower.contains("espa")) return "es";
            if (lower.contains("italy") || lower.contains("itali")) return "it";
            if (lower.contains("brazil") || lower.contains("brasil")) return "br";
            if (lower.contains("japan") || lower.contains("jap")) return "jp";
            if (lower.contains("korea") || lower.contains("korean")) return "kr";
        }
        return null;
    }

    /**
     * Extract a display name from a URL by taking the last path segment.
     */
    private String extractNameFromUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String segment = path.substring(path.lastIndexOf('/') + 1);
            if (!segment.isEmpty()) {
                // Remove extension
                int dot = segment.lastIndexOf('.');
                if (dot > 0) {
                    segment = segment.substring(0, dot);
                }
                return segment.replace('-', ' ').replace('_', ' ');
            }
        } catch (Exception e) {
            // ignore
        }
        return url;
    }
}
