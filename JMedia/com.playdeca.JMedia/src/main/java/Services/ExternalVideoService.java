package Services;

import Models.ExistingVideo;
import Models.ExternalVideo;
import Models.Profile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExternalVideoService {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<ExternalVideo> findByProfile(Long profileId) {
        return ExternalVideo.find("profile.id = ?1 order by lastUpdated desc", profileId).list();
    }

    public ExternalVideo findById(Long id) {
        return ExternalVideo.findById(id);
    }

    @Transactional
    public ExternalVideo create(String url, String title, String alternativeUrlsJson, Long profileId,
                                String seriesTitle, Integer seasonNumber, Integer episodeNumber,
                                String episodeTitle, ExistingVideo entryType) {
        Profile profile = Profile.findById(profileId);
        if (profile == null) return null;

        String sourceType = detectSourceType(url);
        ExternalVideo ev = new ExternalVideo();
        ev.profile = profile;
        ev.url = url;
        ev.title = title != null && !title.isBlank() ? title : url;
        ev.sourceType = sourceType;
        ev.alternativeUrls = alternativeUrlsJson;
        ev.currentTime = 0;
        ev.watchProgress = 0.0;
        ev.watched = false;
        ev.seriesTitle = seriesTitle;
        ev.seasonNumber = seasonNumber;
        ev.episodeNumber = episodeNumber;
        ev.episodeTitle = episodeTitle;
        ev.entryType = entryType;
        ev.lastUpdated = LocalDateTime.now();
        ev.persist();
        return ev;
    }

    @Transactional
    public void delete(Long id) {
        ExternalVideo ev = ExternalVideo.findById(id);
        if (ev != null) ev.delete();
    }

    @Transactional
    public void updateProgress(Long id, double currentTime, double duration) {
        ExternalVideo ev = ExternalVideo.findById(id);
        if (ev == null) return;
        ev.currentTime = currentTime;
        if (duration > 0) {
            ev.watchProgress = Math.min(1.0, currentTime / duration);
            if (ev.watchProgress >= 0.95) {
                ev.watched = true;
            }
        }
        ev.lastUpdated = LocalDateTime.now();
        ev.persist();
    }

    public List<String> findAllSeriesTitles() {
        return ExternalVideo.<ExternalVideo>list("entryType = ?1 and seriesTitle is not null", ExistingVideo.EPISODE)
                .stream()
                .map(ev -> ev.seriesTitle)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<ExternalVideo> findBySeries(String seriesTitle) {
        return ExternalVideo.list("entryType = ?1 and seriesTitle = ?2", ExistingVideo.EPISODE, seriesTitle);
    }

    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        return findBySeries(seriesTitle).stream()
                .map(ev -> ev.seasonNumber != null ? ev.seasonNumber : 1)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<ExternalVideo> findBySeriesAndSeason(String seriesTitle, Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber == 1) {
            return ExternalVideo.list("entryType = ?1 and seriesTitle = ?2 and (seasonNumber = ?3 or seasonNumber is null)",
                    ExistingVideo.EPISODE, seriesTitle, 1);
        }
        return ExternalVideo.list("entryType = ?1 and seriesTitle = ?2 and seasonNumber = ?3",
                ExistingVideo.EPISODE, seriesTitle, seasonNumber);
    }

    public List<ExternalVideo> findAllMovies() {
        return ExternalVideo.list("entryType = ?1", ExistingVideo.MOVIE);
    }

    public String detectSourceType(String url) {
        if (url == null) return "unknown";
        String lower = url.toLowerCase();
        if (lower.startsWith("magnet:")) return "torrent";
        if (lower.endsWith(".torrent")) return "torrent";
        if (lower.contains(".m3u8")) return "hls";
        if (lower.contains(".mpd")) return "dash";
        if (lower.endsWith(".mp4")) return "mp4";
        if (lower.endsWith(".webm")) return "webm";
        if (lower.endsWith(".ogg") || lower.endsWith(".ogv")) return "ogg";
        if (lower.endsWith(".avi")) return "avi";
        if (lower.endsWith(".mkv")) return "mkv";
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return "youtube";
        if (lower.contains("streamtape.com")) return "streamtape";
        return "direct";
    }
}
