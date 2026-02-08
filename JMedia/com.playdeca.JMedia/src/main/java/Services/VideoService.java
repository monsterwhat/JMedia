package Services;

import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import Models.Season;
import Models.Show;
import Models.Video;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoService {

    @PersistenceContext
    private EntityManager em;

    // Simplified service for Video entity operations
    @Transactional
    public List<Video> findAll() {
        return Video.listAll();
    }

    @Transactional
    public Video find(Long id) {
        return Video.findById(id);
    }

    @Transactional
    public List<Video> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Video> videos = new ArrayList<>();
        for (Long id : ids) {
            Video video = Video.findById(id);
            if (video != null) {
                videos.add(video);
            }
        }
        return videos;
    }

    @Transactional
    public List<String> findAllSeriesTitles() {
        return Video.<Video>list("type", "episode")
            .stream()
            .map(v -> v.seriesTitle)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    @Transactional
    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        return Video.<Video>list("type = ?1 and seriesTitle = ?2", "episode", seriesTitle)
            .stream()
            .map(v -> v.seasonNumber)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    @Transactional
    public List<Video> findEpisodesForSeason(String seriesTitle, Integer seasonNumber) {
        return Video.list("type = ?1 and seriesTitle = ?2 and seasonNumber = ?3", 
                        "episode", seriesTitle, seasonNumber);
    }

    @Transactional
    public List<Video> findByMediaType(String mediaType) {
        if (mediaType == null) {
            return findAll();
        }
        return Video.list("type", mediaType.toLowerCase());
    }

    @Transactional
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit) {
        List<Video> videos = findByMediaType(mediaType);
        long totalCount = videos.size();
        
        int startIndex = (page - 1) * limit;
        int endIndex = Math.min(startIndex + limit, videos.size());
        
        List<Video> pageVideos = videos.subList(startIndex, endIndex);
        
        return new PaginatedVideos(pageVideos, totalCount);
    }

    @Transactional
    public List<Long> findRandomIds(int count, List<Long> excludeIds) {
        List<Video> allVideos = Video.listAll();
        return allVideos.stream()
            .filter(v -> !excludeIds.contains(v.id))
            .sorted((a, b) -> Math.random() > 0.5 ? 1 : -1)
            .limit(count)
            .map(v -> v.id)
            .collect(Collectors.toList());
    }

    public record PaginatedVideos(List<Video> videos, long totalCount) {}

    // Legacy methods for MediaFile/Episode/Movie relationships
    // These can be kept for compatibility but should eventually be removed
    
    @Transactional
    public List<Movie> getAllMovies() {
        return Movie.listAll();
    }

    @Transactional
    public List<Episode> getAllEpisodes() {
        return Episode.listAll();
    }

    @Transactional
    public List<Show> getAllShows() {
        return Show.listAll();
    }

    @Transactional
    public List<Season> getSeasonsForShow(String showTitle) {
        return Season.list("showTitle", showTitle);
    }

    @Transactional
    public List<Episode> getEpisodesForSeason(String showTitle, int seasonNumber) {
        return Episode.list("showTitle = ?1 and seasonNumber = ?2", showTitle, seasonNumber);
    }

    @Transactional
    public Movie findMovieById(Long id) {
        return Movie.findById(id);
    }

    @Transactional
    public Episode findEpisodeById(Long id) {
        return Episode.findById(id);
    }

    @Transactional
    public Show findShowById(Long id) {
        return Show.findById(id);
    }

    @Transactional
    public Season findSeasonById(Long id) {
        return Season.findById(id);
    }

    @Transactional
    public MediaFile findMediaFileById(Long id) {
        return MediaFile.findById(id);
    }

    @Transactional
    public MediaFile findMediaFileByPath(String path) {
        return MediaFile.find("path", path).firstResult();
    }

    @Transactional
    public void saveMediaFile(MediaFile mediaFile) {
        if (mediaFile.id == null) {
            mediaFile.persist();
        } else {
            mediaFile.persist();
        }
    }

    @Transactional
    public void deleteMediaFile(MediaFile mediaFile) {
        if (mediaFile != null) {
            mediaFile.delete();
        }
    }

    @Transactional
    public void saveMovie(Movie movie) {
        if (movie.id == null) {
            movie.persist();
        } else {
            movie.persist();
        }
    }

    @Transactional
    public void saveEpisode(Episode episode) {
        if (episode.id == null) {
            episode.persist();
        } else {
            episode.persist();
        }
    }

    @Transactional
    public void saveShow(Show show) {
        if (show.id == null) {
            show.persist();
        } else {
            show.persist();
        }
    }

    @Transactional
    public void saveSeason(Season season) {
        if (season.id == null) {
            season.persist();
        } else {
            season.persist();
        }
    }

    @Transactional
    public void deleteMovie(Movie movie) {
        if (movie != null) {
            movie.delete();
        }
    }

    @Transactional
    public void deleteEpisode(Episode episode) {
        if (episode != null) {
            episode.delete();
        }
    }

    @Transactional
    public void deleteShow(Show show) {
        if (show != null) {
            show.delete();
        }
    }

    @Transactional
    public void deleteSeason(Season season) {
        if (season != null) {
            season.delete();
        }
    }
}