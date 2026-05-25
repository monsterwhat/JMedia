package Models.DTOs;

import java.util.List;

public class SeasonGroup {
    private int seasonNumber;
    private List<VideoItem> episodes;
    private int total;

    public SeasonGroup(int seasonNumber, List<VideoItem> episodes) {
        this.seasonNumber = seasonNumber;
        this.episodes = episodes;
        this.total = episodes.size();
    }

    public int getSeasonNumber() { return seasonNumber; }
    public List<VideoItem> getEpisodes() { return episodes; }
    public int getTotal() { return total; }
}
