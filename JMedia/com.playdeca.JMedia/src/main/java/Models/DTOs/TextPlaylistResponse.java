package Models.DTOs;

import Models.Playlist;
import lombok.Data;
import java.util.List;

@Data
public class TextPlaylistResponse {
    private Playlist playlist;
    private int totalLines;
    private int matchedSongs;
    private List<String> unmatchedLines;
    private String message;
}