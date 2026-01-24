package Models.DTOs;

import lombok.Data;
import java.util.List;

@Data
public class TextPlaylistRequest {
    private String playlistName;
    private String description;
    private List<String> textLines;
}