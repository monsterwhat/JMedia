package Services;

import Models.Video.MediaFile;

public final class VideoTypeDetection {

    private VideoTypeDetection() {}

    public static String detectVideoType(MediaFile mediaFile) {
        String filename = extractFilenameFromPath(mediaFile.path);
        String pathLower = mediaFile.path.toLowerCase();

        if (pathLower.contains("tv shows") || pathLower.contains("tvseries") ||
            pathLower.contains("/tv/") || pathLower.contains("\\tv\\") ||
            pathLower.contains("season") || pathLower.contains("series")) {
            return "episode";
        }

        if (filename.toLowerCase().contains("movie") ||
            pathLower.contains("movies") ||
            isTypicalMovieDuration(mediaFile.durationSeconds)) {

            if (filename.matches(".*[sS]\\d+[eE]\\d+.*") || filename.matches(".*\\d+x\\d+.*")) {
                return "episode";
            }
            return "movie";
        } else if (isTypicalEpisodeDuration(mediaFile.durationSeconds)) {
            return "episode";
        }

        return "movie";
    }

    public static String extractFilenameFromPath(String path) {
        if (path == null) return null;
        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);
        return lastSeparator >= 0 ? path.substring(lastSeparator + 1) : path;
    }

    public static boolean isTypicalMovieDuration(int durationSeconds) {
        return durationSeconds >= 40 * 60 && durationSeconds <= 300 * 60;
    }

    public static boolean isTypicalEpisodeDuration(int durationSeconds) {
        return durationSeconds >= 5 * 60 && durationSeconds <= 120 * 60;
    }

    public static String calculateDisplayResolution(String resolution) {
        if (resolution == null) return null;

        String[] parts = resolution.split("x");
        if (parts.length != 2) return resolution;

        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);

            if (height >= 2160) return "4K";
            if (height >= 1440) return "2K";
            if (height >= 1080) return "Full HD";
            if (height >= 720) return "HD";
            return "SD";
        } catch (NumberFormatException e) {
            return resolution;
        }
    }

    public static String detectQuality(MediaFile mediaFile) {
        if (mediaFile.width == 0 || mediaFile.height == 0) return "Unknown";

        String resolution = calculateDisplayResolution(mediaFile.width + "x" + mediaFile.height);
        if (resolution.contains("4K")) return "4K";
        if (resolution.contains("Full HD")) return "Full HD";
        if (resolution.contains("HD")) return "HD";
        return "SD";
    }
}