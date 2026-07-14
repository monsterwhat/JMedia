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
        "(tvg-id|tvg-name|tvg-logo|group-title|tvg-country|tvg-language)\\s*=\\s*\"([^\"]*)\""
    );

    private static final Pattern ATTR_UNQUOTED_PATTERN = Pattern.compile(
        "(tvg-id|tvg-name|tvg-logo|group-title|tvg-country|tvg-language)\\s*=\\s*([^\\s,]+)"
    );

    public static class M3uEntry {
        public String name;
        public String streamUrl;
        public String logoUrl;
        public String groupTitle;
        public String tvgId;
        public String tvgName;
        public String country;
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

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines and the header
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("#EXTM3U")) {
                continue;
            }

            if (trimmed.startsWith("#EXTINF:")) {
                pending = parseExtInf(trimmed);
            } else if (!trimmed.startsWith("#")) {
                // This line is a stream URL
                if (pending != null) {
                    pending.streamUrl = trimmed;
                    entries.add(pending);
                    pending = null;
                } else {
                    // Standalone URL without #EXTINF metadata
                    M3uEntry entry = new M3uEntry();
                    entry.streamUrl = trimmed;
                    entry.name = extractNameFromUrl(trimmed);
                    entries.add(entry);
                }
            }
            // Other # lines (e.g., #EXTVLCOPT, #EXTGRP) are skipped
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
            case "group-title" -> entry.groupTitle = value != null && value.contains(";") ? value.substring(0, value.indexOf(';')).trim() : value;
            case "tvg-country" -> entry.country = value;
            case "tvg-language" -> { /* stored if needed later */ }
            default -> { /* unknown attribute, ignore */ }
        }
    }

    private boolean isAttributeSet(M3uEntry entry, String key) {
        return switch (key) {
            case "tvg-id" -> entry.tvgId != null;
            case "tvg-name" -> entry.tvgName != null;
            case "tvg-logo" -> entry.logoUrl != null;
            case "group-title" -> entry.groupTitle != null;
            case "tvg-country" -> entry.country != null;
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
