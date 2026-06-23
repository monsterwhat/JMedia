package Models.DTOs;

import java.util.List;

public class SyncExchangeResponse {

    public List<SyncSongData> songs;
    public List<String> updatedIds;
    public List<String> createdIds;
    public List<String> errors;

    public SyncExchangeResponse() {
        this.updatedIds = new java.util.ArrayList<>();
        this.createdIds = new java.util.ArrayList<>();
        this.errors = new java.util.ArrayList<>();
    }

}
