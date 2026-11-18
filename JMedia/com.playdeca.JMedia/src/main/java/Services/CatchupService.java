package Services;

import Models.Video.LiveChannel;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catch-up (timeshift) URL construction for live channels.
 *
 * <p>JMedia does not record streams itself. When the upstream M3U advertises an
 * archive ({@code catchup}, {@code catchup-source}, {@code catchup-days}), this
 * service builds the provider's archive URL for a requested start time and the
 * Xtream timeshift endpoints proxy it. Supported modes: default, append, shift,
 * archive, timeshift, xc/xtream, flussonic (hls/ts/dash).
 */
@ApplicationScoped
public class CatchupService {

    private static final Logger LOG = LoggerFactory.getLogger(CatchupService.class);

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DURATION_MINUTES = 24 * 60;
    private static final long ARCHIVE_GRACE_SECONDS = 3600L;

    private static final DateTimeFormatter XC_START =
            DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(ZoneOffset.UTC);

    private static final Pattern XC_LIVE_PATH = Pattern.compile(
            "^(https?://[^/]+)/(?:live|movie|series)/([^/]+)/([^/]+)/([^/.?]+)(\\.[^/?]+)?(?:\\?.*)?$");

    private static final Pattern PHP_DATE = Pattern.compile("\\$\\{\\((b|e)\\)([^}]+)}");
    private static final Pattern DURATION_TOKEN = Pattern.compile("\\$?\\{duration(?::\\d+)?}");

    public boolean isAvailable(LiveChannel ch) {
        if (ch == null) {
            return false;
        }
        boolean type = ch.catchupType != null && !ch.catchupType.isBlank();
        boolean source = ch.catchupSource != null && !ch.catchupSource.isBlank();
        boolean days = ch.catchupDays != null && ch.catchupDays > 0;
        return type || source || days;
    }

    public int archiveDays(LiveChannel ch) {
        if (!isAvailable(ch)) {
            return 0;
        }
        return ch.catchupDays != null && ch.catchupDays > 0 ? ch.catchupDays : DEFAULT_DAYS;
    }

    /** Keep clients from requesting the future or outside the archive window. */
    public boolean isWithinArchive(LiveChannel ch, long startEpoch, long nowEpoch) {
        int days = archiveDays(ch);
        if (days <= 0) {
            return false;
        }
        long earliest = nowEpoch - days * 86_400L;
        return startEpoch >= earliest - ARCHIVE_GRACE_SECONDS && startEpoch <= nowEpoch + 300L;
    }

    /**
     * Build the upstream archive URL, or null when the channel/type has no
     * usable archive address. {@code durationMinutes} is the requested window.
     */
    public String resolveUrl(LiveChannel ch, long startEpoch, int durationMinutes, long nowEpoch) {
        if (!isAvailable(ch) || ch.streamUrl == null || ch.streamUrl.isBlank()
                || durationMinutes <= 0 || durationMinutes > MAX_DURATION_MINUTES) {
            return null;
        }
        long durationSeconds = durationMinutes * 60L;
        long endEpoch = startEpoch + durationSeconds;
        String live = ch.streamUrl.trim();
        String source = ch.catchupSource == null ? null : ch.catchupSource.trim();
        String type = ch.catchupType == null ? "" : ch.catchupType.trim().toLowerCase(Locale.ROOT);

        String template;
        switch (type) {
            case "", "default" -> template = source != null && !source.isBlank()
                    ? source
                    : withQuery(live, "utc=" + startEpoch + "&lutc=" + nowEpoch);
            case "append" -> {
                if (source == null || source.isBlank()) {
                    return null;
                }
                template = appendSource(live, source);
            }
            case "shift" -> template = withQuery(base(live, source),
                    "utc=" + startEpoch + "&lutc=" + nowEpoch);
            case "archive" -> template = withQuery(base(live, source),
                    "archive=" + startEpoch + "&archive_end=" + endEpoch);
            case "timeshift" -> template = withQuery(base(live, source),
                    "timeshift=" + startEpoch + "&timenow=" + nowEpoch);
            case "xc", "xtream", "xtream-codes" -> template = source != null && !source.isBlank()
                    ? source : buildXcUrl(live, startEpoch, durationMinutes);
            case "flussonic", "flussonic-hls", "flussonic-dash", "flussonic-ts", "fs" ->
                    template = buildFlussonic(base(live, source), type, startEpoch, durationSeconds);
            default -> {
                if (source != null && !source.isBlank()) {
                    template = source;
                } else {
                    template = withQuery(live, "utc=" + startEpoch + "&lutc=" + nowEpoch);
                    LOG.debug("Unknown catch-up type '{}', falling back to shift", type);
                }
            }
        }

        if (template == null || template.isBlank()) {
            return null;
        }
        String resolved = fillPlaceholders(template, startEpoch, endEpoch, durationSeconds, nowEpoch);
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
            LOG.warn("Catch-up URL is not http(s) for channel {}: {}", ch.id, resolved);
            return null;
        }
        return resolved;
    }

    /**
     * Parse the Xtream timeshift start value: Unix seconds (or millis), or the
     * common {@code yyyy-MM-dd:HH-mm} / {@code yyyyMMdd-HH} forms.
     */
    public Long parseStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                long parsed = Long.parseLong(v);
                return v.length() >= 13 ? parsed / 1000L : parsed;
            } catch (NumberFormatException e) {
                LOG.warn("Catch-up start '{}' is not a valid timestamp", value);
                return null;
            }
        }
        String[] patterns = {
                "yyyy-MM-dd:HH-mm", "yyyy-MM-dd-HH-mm", "yyyy-MM-dd HH:mm",
                "yyyyMMdd-HH", "yyyyMMddHHmm"
        };
        for (String pattern : patterns) {
            try {
                LocalDateTime dt = LocalDateTime.parse(v, DateTimeFormatter.ofPattern(pattern));
                return dt.toEpochSecond(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                // Expected while probing the accepted shapes; the final failure is logged below.
                LOG.debug("Catch-up start '{}' does not match {}", value, pattern);
            }
        }
        LOG.warn("Catch-up start '{}' does not match a supported format", value);
        return null;
    }

    // ------------------------------------------------------------- URL builders

    private static String base(String live, String source) {
        return source != null && !source.isBlank() ? source : live;
    }

    private static String appendSource(String live, String source) {
        if (source.startsWith("?") || source.startsWith("&")) {
            return live + source;
        }
        return live + (live.contains("?") ? "&" : "?") + source;
    }

    private static String withQuery(String url, String query) {
        if (url.endsWith("?") || url.endsWith("&")) {
            return url + query;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String buildXcUrl(String live, long startEpoch, int durationMinutes) {
        Matcher m = XC_LIVE_PATH.matcher(live);
        if (!m.matches()) {
            LOG.warn("Catch-up type xc but the live URL is not an Xtream stream: {}", live);
            return null;
        }
        String base = m.group(1);
        String user = m.group(2);
        String pass = m.group(3);
        String id = m.group(4);
        String ext = m.group(5) != null ? m.group(5) : ".ts";
        return base + "/timeshift/" + user + "/" + pass + "/" + durationMinutes
                + "/" + XC_START.format(Instant.ofEpochSecond(startEpoch)) + "/" + id + ext;
    }

    private static String buildFlussonic(String base, String type, long startEpoch, long durationSeconds) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String query = "";
        String path = base;
        int q = base.indexOf('?');
        if (q >= 0) {
            query = base.substring(q);
            path = base.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String folder = path.substring(0, slash + 1);
        String last = path.substring(slash + 1);
        boolean dash = "flussonic-dash".equals(type) || last.endsWith(".mpd");
        boolean ts = "flussonic-ts".equals(type) || "fs".equals(type) || last.endsWith(".ts");

        if (dash) {
            return folder + "archive-" + startEpoch + "-" + durationSeconds + ".mpd" + query;
        }
        if (ts) {
            return folder + "timeshift_abs-" + startEpoch + ".ts" + query;
        }
        if ("index.m3u8".equals(last)) {
            return folder + "archive-" + startEpoch + "-" + durationSeconds + ".m3u8" + query;
        }
        if (last.endsWith(".m3u8")) {
            return folder + last.substring(0, last.length() - ".m3u8".length())
                    + "-" + startEpoch + "-" + durationSeconds + ".m3u8" + query;
        }
        return folder + "timeshift_abs-" + startEpoch + ".m3u8" + query;
    }

    // ----------------------------------------------------------- placeholder fill

    String fillPlaceholders(String template, long startEpoch, long endEpoch,
                            long durationSeconds, long nowEpoch) {
        String out = template;

        // ${(b)Y-m-d:H-i} / ${(e)Y-m-d:H-i}: PHP date formatting for start/end.
        Matcher php = PHP_DATE.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (php.find()) {
            long epoch = "b".equals(php.group(1)) ? startEpoch : endEpoch;
            php.appendReplacement(sb, Matcher.quoteReplacement(formatPhpDate(php.group(2), epoch)));
        }
        php.appendTail(sb);
        out = sb.toString();

        // {Y}-{m}-{d}:{H}-{M} tokens (Kodi-style Xtream templates).
        Instant start = Instant.ofEpochSecond(startEpoch);
        LocalDateTime startUtc = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        out = out.replace("{Y}", String.format("%04d", startUtc.getYear()))
                .replace("{m}", String.format("%02d", startUtc.getMonthValue()))
                .replace("{d}", String.format("%02d", startUtc.getDayOfMonth()))
                .replace("{H}", String.format("%02d", startUtc.getHour()))
                .replace("{M}", String.format("%02d", startUtc.getMinute()))
                .replace("{S}", String.format("%02d", startUtc.getSecond()));

        // ${duration} / {duration} / {duration:60}
        out = DURATION_TOKEN.matcher(out).replaceAll(String.valueOf(durationSeconds));

        out = replaceToken(out, "start", startEpoch);
        out = replaceToken(out, "timestamp", startEpoch);
        out = replaceToken(out, "utc", startEpoch);
        out = replaceToken(out, "end", endEpoch);
        out = replaceToken(out, "utcend", endEpoch);
        out = replaceToken(out, "now", nowEpoch);
        out = replaceToken(out, "lutc", nowEpoch);
        return out;
    }

    private static String replaceToken(String out, String token, long value) {
        return out.replace("${" + token + "}", String.valueOf(value))
                .replace("{" + token + "}", String.valueOf(value));
    }

    /** Convert the common PHP date tokens used in catchup-source to a Java pattern. */
    static String formatPhpDate(String phpFormat, long epoch) {
        StringBuilder javaPattern = new StringBuilder();
        String format = phpFormat == null ? "" : phpFormat;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            String mapped = switch (c) {
                case 'Y' -> "yyyy";
                case 'y' -> "yy";
                case 'm' -> "MM";
                case 'n' -> "M";
                case 'd' -> "dd";
                case 'j' -> "d";
                case 'H' -> "HH";
                case 'G' -> "H";
                case 'i' -> "mm";
                case 's' -> "ss";
                default -> null;
            };
            if (mapped != null) {
                javaPattern.append(mapped);
            } else if (c == '\'') {
                javaPattern.append("''");
            } else {
                javaPattern.append('\'').append(c).append('\'');
            }
        }
        try {
            return DateTimeFormatter.ofPattern(javaPattern.toString())
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochSecond(epoch));
        } catch (IllegalArgumentException e) {
            LOG.warn("Unsupported catch-up date format '{}': {}", phpFormat, e.getMessage());
            return String.valueOf(epoch);
        }
    }
}
