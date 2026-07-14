package Models.DTOs;

import java.util.ArrayList;
import java.util.List;

public class M3uImportResponse {
    public Long playlistId;
    public String playlistName;
    public int totalEntries;
    public int channelsCreated;
    public int failedEntries;
    public String message;
    public List<String> errors = new ArrayList<>();
}
