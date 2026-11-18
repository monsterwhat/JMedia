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
    private static final Pattern DURATION_TOKEN = Pattern.compile("\\$?\\{duration(?::(\\d+))?}");
    private static final Pattern OFFSET_TOKEN = Pattern.compile("\\$?\\{offset(?::(\\d+))?}");
    private static final Pattern TIME_TOKEN = Pattern.compile(
            "\\$?\\{(start|utc|end|utcend|timestamp|now|lutc|current_utc)(?::([^}]+))?}");
    private static final Pattern LIVE_CREDENTIALS = Pattern.compile(
            "^https?://[^/]+/(?:live|movie|series)/([^/]+)/([^/]+)/");

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
        return resolveUrl(ch, startEpoch, durationMinutes, nowEpoch, null);
    }

    /**
     * @param catchupId XMLTV programme id for templates using {catchup-id}; may be null
     */
    public String resolveUrl(LiveChannel ch, long startEpoch, int durationMinutes,
                             long nowEpoch, String catchupId) {
        if (!isAvailable(ch) || ch.streamUrl == null || ch.streamUrl.isBlank()
                || durationMinutes <= 0 || durationMinutes > MAX_DURATION_MINUTES) {
            return null;
        }
        long durationSeconds = durationMinutes * 60L;
        // Providers whose archive clock differs from UTC advertise catchup-correction.
        long correctionSeconds = ch.catchupCorrectionHours == null ? 0L
                : Math.round(ch.catchupCorrectionHours * 3600.0);
        long effectiveStart = startEpoch + correctionSeconds;
        long effectiveNow = nowEpoch + correctionSeconds;
        long effectiveEnd = effectiveStart + durationSeconds;
        String live = ch.streamUrl.trim();
        String source = ch.catchupSource == null ? null : ch.catchupSource.trim();
        String type = ch.catchupType == null ? "" : ch.catchupType.trim().toLowerCase(Locale.ROOT);

        String template;
        switch (type) {
            case "", "default" -> template = source != null && !source.isBlank()
                    ? source
                    : withQuery(live, "utc=" + effectiveStart + "&lutc=" + effectiveNow);
            case "append" -> {
                if (source == null || source.isBlank()) {
                    return null;
                }
                template = appendSource(live, source);
            }
            case "shift" -> template = withQuery(base(live, source),
                    "utc=" + effectiveStart + "&lutc=" + effectiveNow);
            case "archive" -> template = withQuery(base(live, source),
                    "archive=" + effectiveStart + "&archive_end=" + effectiveEnd);
            case "timeshift" -> template = withQuery(base(live, source),
                    "timeshift=" + effectiveStart + "&timenow=" + effectiveNow);
            case "xc", "xtream", "xtream-codes", "xstream", "xstream-1" -> template = source != null && !source.isBlank()
                    ? source : buildXcUrl(live, effectiveStart, durationMinutes);
            case "flussonic", "flussonic-hls", "flussonic-dash", "flussonic-ts", "fs" ->
                    template = buildFlussonic(base(live, source), type, effectiveStart, durationSeconds);
            case "vod" -> {
                if (source == null || source.isBlank()) {
                    return null;
                }
                template = source;
            }
            default -> {
                if (source != null && !source.isBlank()) {
                    template = source;
                } else {
                    template = withQuery(live, "utc=" + effectiveStart + "&lutc=" + effectiveNow);
                    LOG.debug("Unknown catch-up type '{}', falling back to shift", type);
                }
            }
        }

        if (template == null || template.isBlank()) {
            return null;
        }
        if ((template.contains("{catchup-id}") || template.contains("${catchup-id}"))
                && (catchupId == null || catchupId.isBlank())) {
            LOG.debug("Catch-up template for channel {} needs a programme catch-up id", ch.id);
            return null;
        }
        String resolved = fillPlaceholders(template, effectiveStart, effectiveEnd,
                durationSeconds, effectiveNow, live, catchupId);
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
                            long durationSeconds, long nowEpoch, String liveUrl, String catchupId) {
        String out = template;

        // ${(b)FORMAT} / ${(e)FORMAT}: PHP-style (Y-m-d:H:i) or Java-style
        // (yyyyMMddHHmmss, optionally suffixed with |UTC).
        Matcher php = PHP_DATE.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (php.find()) {
            long epoch = "b".equals(php.group(1)) ? startEpoch : endEpoch;
            php.appendReplacement(sb, Matcher.quoteReplacement(formatDateToken(php.group(2), epoch)));
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

        // {duration} is seconds; {duration:X} is seconds divided by X (e.g. 60 = minutes).
        out = divideToken(out, DURATION_TOKEN, durationSeconds);
        out = replaceToken(out, "durmin", durationSeconds / 60);
        // {offset} is seconds since the programme start; {offset:X} divides it.
        out = divideToken(out, OFFSET_TOKEN, nowEpoch - startEpoch);

        // Provider credentials for xc-style templates (from the live URL path).
        if (out.contains("${login}") || out.contains("{login}")
                || out.contains("${password}") || out.contains("{password}")) {
            Matcher creds = LIVE_CREDENTIALS.matcher(liveUrl == null ? "" : liveUrl);
            if (creds.find()) {
                String login = java.net.URLDecoder.decode(creds.group(1), java.nio.charset.StandardCharsets.UTF_8);
                String pass = java.net.URLDecoder.decode(creds.group(2), java.nio.charset.StandardCharsets.UTF_8);
                out = out.replace("${login}", login).replace("{login}", login)
                        .replace("${password}", pass).replace("{password}", pass);
            }
        }

        out = out.replace("${start_iso}", start.toString())
                .replace("{start_iso}", start.toString())
                .replace("${end_iso}", Instant.ofEpochSecond(endEpoch).toString())
                .replace("{end_iso}", Instant.ofEpochSecond(endEpoch).toString());

        if (catchupId != null) {
            out = out.replace("${catchup-id}", catchupId)
                    .replace("{catchup-id}", catchupId);
        }

        // Timestamp tokens, optionally with a date format: {utc:Ymd-H-M}, ${end:...}.
        Matcher time = TIME_TOKEN.matcher(out);
        StringBuffer timeOut = new StringBuffer();
        while (time.find()) {
            String token = time.group(1);
            long epoch = switch (token) {
                case "start", "utc" -> startEpoch;
                case "end", "utcend" -> endEpoch;
                default -> nowEpoch;
            };
            String format = time.group(2);
            String value = format == null || format.isBlank()
                    ? String.valueOf(epoch)
                    : formatDateToken(format, epoch);
            time.appendReplacement(timeOut, Matcher.quoteReplacement(value));
        }
        time.appendTail(timeOut);
        return timeOut.toString();
    }

    private static String divideToken(String out, Pattern pattern, long baseValue) {
        Matcher m = pattern.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long value = baseValue;
            String divisor = m.group(1);
            if (divisor != null) {
                long d = Long.parseLong(divisor);
                if (d > 0) {
                    value = baseValue / d;
                }
            }
            m.appendReplacement(sb, String.valueOf(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceToken(String out, String token, long value) {
        return out.replace("${" + token + "}", String.valueOf(value))
                .replace("{" + token + "}", String.valueOf(value));
    }

    /**
     * Formats a date placeholder. Supports both PHP-style tokens used by Xtream
     * sources (Y-m-d:H:i) and Java patterns used by SrcBox-style sources
     * (yyyyMMddHHmmss, optionally suffixed with |UTC).
     */
    static String formatDateToken(String format, long epoch) {
        if (format == null || format.isBlank()) {
            return String.valueOf(epoch);
        }
        String f = format.trim();
        if (f.endsWith("|UTC")) {
            f = f.substring(0, f.length() - 4);
        } else if (f.endsWith("|LOCAL")) {
            f = f.substring(0, f.length() - 6);
        }
        if (looksLikeJavaPattern(f)) {
            try {
                return DateTimeFormatter.ofPattern(f)
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.ofEpochSecond(epoch));
            } catch (IllegalArgumentException e) {
                LOG.warn("Unsupported catch-up date pattern '{}': {}", format, e.getMessage());
                return String.valueOf(epoch);
            }
        }
        return formatPhpDate(f, epoch);
    }

    private static boolean looksLikeJavaPattern(String f) {
        return f.contains("yyyy") || f.contains("MM") || f.contains("dd")
                || f.contains("HH") || f.contains("mm") || f.contains("ss")
                || f.contains("'T'");
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
                case 'M' -> "mm";
                case 's' -> "ss";
                case 'S' -> "ss";
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
