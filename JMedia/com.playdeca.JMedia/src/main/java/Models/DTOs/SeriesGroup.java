package Models.DTOs;

import java.util.List;

public class SeriesGroup {
    private String seriesTitle;
    private List<SeasonGroup> seasons;
    private int total;

    public SeriesGroup(String seriesTitle, List<SeasonGroup> seasons) {
        this.seriesTitle = seriesTitle;
        this.seasons = seasons;
        this.total = seasons.stream().mapToInt(SeasonGroup::getTotal).sum();
    }

    public String getSeriesTitle() { return seriesTitle; }
    public List<SeasonGroup> getSeasons() { return seasons; }
    public int getTotal() { return total; }
}
