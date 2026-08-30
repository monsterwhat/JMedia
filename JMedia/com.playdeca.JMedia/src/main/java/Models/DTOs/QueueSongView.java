package Models.DTOs;

/**
 * Lightweight projection of {@code Song} for the queue UI fragment.
 * <p>
 * The full {@code Song} entity carries heavy CLOB columns (lyrics, artworkBase64)
 * plus an eagerly-fetched {@code SongAnalysis} join. Loading all of those for every
 * page of the queue repeatedly explodes the heap (H2 streams the CLOB through
 * {@code ValueLob.readString → StringBuffer.append} on every row).
 * <p>
 * This DTO selects only the columns the queue template actually renders:
 * {@code id, title, artist}. Returning this from the service layer keeps
 * per-page allocations in kilobytes instead of megabytes.
 */
public record QueueSongView(Long id, String title, String artist, String path) {
    public boolean isFlac() {
        return path != null && path.toLowerCase().endsWith(".flac");
    }
}
