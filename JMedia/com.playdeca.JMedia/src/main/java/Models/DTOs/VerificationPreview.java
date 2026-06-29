package Models.DTOs;

/**
 * Preview of what metadata enrichment would change for a single video.
 */
public class VerificationPreview {
    public Long videoId;
    public String filename;
    public String type; // "movie" or "episode"

    // Core fields that enrichment might change
    public VerificationField<String> title;
    public VerificationField<String> seriesTitle;
    public VerificationField<String> episodeTitle;
    public VerificationField<Integer> seasonNumber;
    public VerificationField<Integer> episodeNumber;
    public VerificationField<String> imdbId;
    public VerificationField<String> showImdbId;
    public VerificationField<String> tmdbId;

    public boolean hasDifferences() {
        return title.isDifferent() || seriesTitle.isDifferent() ||
               episodeTitle.isDifferent() || seasonNumber.isDifferent() ||
               episodeNumber.isDifferent() || imdbId.isDifferent() ||
               showImdbId.isDifferent() || tmdbId.isDifferent();
    }
}
