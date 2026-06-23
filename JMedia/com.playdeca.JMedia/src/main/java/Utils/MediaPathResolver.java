package Utils;

import Models.Video;
import java.text.Normalizer;
import java.util.regex.Pattern;

public class MediaPathResolver {

    private static final String THUMBNAIL_DIR = "thumbnails";
    private static final String STORYBOARD_DIR = "storyboards";
    private static final int MAX_SLUG_LENGTH = 100;

    private static final Pattern NON_ASCII = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_UNDERSCORE = Pattern.compile("^_+|_+$");
    private static final Pattern TECHNICAL_NOISE = Pattern.compile(
        "(?i)\\b(720p|1080p|2160p|4k|bluray|bdrip|dvdrip|web-dl|webrip|hdtv|x264|x265|hevc|aac|ac3|dts)\\b"
    );

    public static String resolveThumbnailName(Video video) {
        if (video == null) return null;
        return buildName(video) + ".webp";
    }

    public static String resolveThumbnailPath(Video video) {
        String name = resolveThumbnailName(video);
        if (name == null) return null;
        return THUMBNAIL_DIR + "/" + name;
    }

    public static String resolveStoryboardName(Video video) {
        if (video == null) return null;
        return buildName(video) + ".webp";
    }

    public static String resolveStoryboardPath(Video video) {
        String name = resolveStoryboardName(video);
        if (name == null) return null;
        return STORYBOARD_DIR + "/" + name;
    }

    private static String buildName(Video video) {
        String id = getPrimaryId(video);
        if (id != null) {
            if ("episode".equalsIgnoreCase(video.type) && video.episodeNumber != null) {
                String season = video.seasonNumber != null
                    ? String.format("S%02d", video.seasonNumber)
                    : "S00";
                String episode = String.format("E%02d", video.episodeNumber);
                return id + "_" + season + episode;
            }
            return id;
        }

        String title = getBaseTitle(video);
        if (title != null && !title.isBlank()) {
            String slug = slugify(title);
            if (slug == null || slug.isBlank()) {
                return "video_" + video.id;
            }
            if ("episode".equalsIgnoreCase(video.type) && video.episodeNumber != null) {
                String season = video.seasonNumber != null
                    ? String.format("S%02d", video.seasonNumber)
                    : "S00";
                String episode = String.format("E%02d", video.episodeNumber);
                slug = slug + "_" + season + episode;
            } else if ("movie".equalsIgnoreCase(video.type) && video.releaseYear != null) {
                slug = slug + "_" + video.releaseYear;
            }
            return slug;
        }

        return "video_" + video.id;
    }

    public static String getPrimaryId(Video video) {
        if (video.imdbId != null && !video.imdbId.isBlank()) return video.imdbId;
        if (video.tmdbId != null && !video.tmdbId.isBlank()) return "tmdb_" + video.tmdbId;
        if (video.tvdbId != null && !video.tvdbId.isBlank()) return "tvdb_" + video.tvdbId;
        return null;
    }

    public static boolean hasExternalId(Video video) {
        return getPrimaryId(video) != null;
    }

    private static String getBaseTitle(Video video) {
        if ("episode".equalsIgnoreCase(video.type) && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
            return video.seriesTitle;
        }
        if (video.title != null && !video.title.isBlank()) {
            return video.title;
        }
        if (video.seriesTitle != null && !video.seriesTitle.isBlank()) {
            return video.seriesTitle;
        }
        if (video.episodeTitle != null && !video.episodeTitle.isBlank()) {
            return video.episodeTitle;
        }
        return null;
    }

    public static String slugify(String input) {
        if (input == null || input.isBlank()) return null;

        String result = Normalizer.normalize(input, Normalizer.Form.NFD);
        result = result.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        result = result.toLowerCase();

        String cleaned = TECHNICAL_NOISE.matcher(result).replaceAll("");
        if (cleaned.isBlank()) cleaned = result;

        cleaned = NON_ASCII.matcher(cleaned).replaceAll("_");
        cleaned = LEADING_TRAILING_UNDERSCORE.matcher(cleaned).replaceAll("");

        if (cleaned.length() > MAX_SLUG_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SLUG_LENGTH);
            cleaned = LEADING_TRAILING_UNDERSCORE.matcher(cleaned).replaceAll("");
        }

        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * Computes the legacy thumbnail filename that would have been generated before
     * the standardization update. Used for migration/fallback cleanup.
     */
    public static String legacyThumbnailName(Long videoId) {
        return "video_" + videoId + ".webp";
    }

    /**
     * Checks whether the stored thumbnail path matches the canonical name for this video.
     */
    public static boolean needsRename(Video video, String currentThumbnailPath) {
        if (currentThumbnailPath == null || currentThumbnailPath.isBlank()) return false;
        String canonicalName = resolveThumbnailName(video);
        if (canonicalName == null) return false;
        return !currentThumbnailPath.endsWith(canonicalName);
    }
}
