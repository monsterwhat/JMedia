package Detectors;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EpisodeDetector {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)[sS](\\d{1,2})[eE](\\d{1,3})"),
            Pattern.compile("(?i)(\\d{1,2})x(\\d{1,3})")
    };

    public static Optional<EpisodeInfo> detect(String filename) {
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(filename);
            if (matcher.find()) {
                int season = Integer.parseInt(matcher.group(1));
                int episode = Integer.parseInt(matcher.group(2));

                String titleHint = null;
                // Attempt to extract titleHint, which is anything after the episode pattern
                int endIndex = matcher.end();
                if (endIndex < filename.length()) {
                    titleHint = filename.substring(endIndex).trim();
                    // Remove common file extension patterns or other junk
                    titleHint = titleHint.replaceAll("\\.(mp4|mkv|avi|webm|flv|mov|wmv|mpg|mpeg)$", "");
                    titleHint = titleHint.replaceAll("^[.-]", "").trim(); // Remove leading dot/dash
                    if (titleHint.isEmpty()) {
                        titleHint = null;
                    }
                }
                return Optional.of(new EpisodeInfo(season, episode, titleHint));
            }
        }
        return Optional.empty();
    }

    public static class EpisodeInfo {
        public final int season;
        public final int episode;
        public final String titleHint;

        public EpisodeInfo(int season, int episode, String titleHint) {
            this.season = season;
            this.episode = episode;
            this.titleHint = titleHint;
        }
    }
}
