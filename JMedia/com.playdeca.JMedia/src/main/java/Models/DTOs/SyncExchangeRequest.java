package Models.DTOs;

import java.util.List;

public class SyncExchangeRequest {

    public List<SyncSongData> songs;

    public List<SyncVideoData> videos;

    public List<SyncCollectionData> collections;

    public List<SyncSubtitleData> subtitles;

    public String syncType = "ALL";

    public int limit;

}
