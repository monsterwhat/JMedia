package Services;

import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import Models.Season;
import Models.Show;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoService {

    @PersistenceContext
    private EntityManager em;

    public record VideoDTO(
        Long id, // MediaFile ID
        String path,
        String type, // "Movie" or "Episode"
        String title,
        String seriesTitle,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle,
        Integer releaseYear,
        int durationSeconds,
        int width,
        int height
    ) {}

    private VideoDTO episodeToDTO(Episode episode, MediaFile mf) {
        Show show = episode.season != null ? episode.season.show : null;
        String seriesTitle = show != null ? show.name : "Unknown Show";
        String title = String.format("%s - S%02dE%02d", seriesTitle, episode.seasonNumber, episode.episodeNumber);
        if (episode.title != null && !episode.title.isBlank()) {
            title += " - " + episode.title;
        }
        return new VideoDTO(mf.id, mf.path, "Episode", title, seriesTitle, episode.seasonNumber, episode.episodeNumber, episode.title, null, mf.durationSeconds, mf.width, mf.height);
    }
    
    private VideoDTO movieToDTO(Movie movie, MediaFile mf) {
         return new VideoDTO(mf.id, mf.path, "Movie", movie.title, null, null, null, null, movie.releaseYear, mf.durationSeconds, mf.width, mf.height);
    }

    @Transactional
    public VideoDTO find(Long id) {
        MediaFile mf = MediaFile.findById(id);
        if (mf == null || !"video".equals(mf.type)) {
            return null;
        }

        Movie movie = Movie.find("videoPath", mf.path).firstResult();
        if (movie != null) {
            return movieToDTO(movie, mf);
        }

        Episode episode = Episode.find("videoPath", mf.path).firstResult();
        if (episode != null) {
            return episodeToDTO(episode, mf);
        }

        return null;
    }
    
    @Transactional
    public List<VideoDTO> findAll() {
        List<VideoDTO> videos = new ArrayList<>();
        Map<String, MediaFile> mfMap = MediaFile.<MediaFile>streamAll().collect(Collectors.toMap(mf -> mf.path, Function.identity()));

        Movie.<Movie>streamAll().forEach(movie -> {
            MediaFile mf = mfMap.get(movie.videoPath);
            if (mf != null) {
                videos.add(movieToDTO(movie, mf));
            }
        });
        
        Episode.<Episode>streamAll().forEach(episode -> {
            MediaFile mf = mfMap.get(episode.videoPath);
            if (mf != null) {
                videos.add(episodeToDTO(episode, mf));
            }
        });
        
        return videos;
    }

    @Transactional
    public List<VideoDTO> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<MediaFile> mediaFiles = MediaFile.list("id in ?1", ids);
        if (mediaFiles.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, MediaFile> mfMap = mediaFiles.stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));
        
        List<VideoDTO> results = new ArrayList<>();
        Movie.<Movie>stream("videoPath in ?1", mfMap.keySet()).forEach(movie -> {
             MediaFile mf = mfMap.get(movie.videoPath);
             if (mf != null) results.add(movieToDTO(movie, mf));
        });
        
        Episode.<Episode>stream("videoPath in ?1", mfMap.keySet()).forEach(episode -> {
            MediaFile mf = mfMap.get(episode.videoPath);
            if (mf != null) results.add(episodeToDTO(episode, mf));
        });

        Map<Long, VideoDTO> dtoMap = results.stream().collect(Collectors.toMap(VideoDTO::id, Function.identity()));
        return ids.stream()
                  .map(dtoMap::get)
                  .filter(Objects::nonNull)
                  .collect(Collectors.toList());
    }
    
    public record PaginatedVideos(List<VideoDTO> videos, long totalCount) {}

    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit) {
        if (page < 1) page = 1;
        Page p = Page.of(page - 1, limit);

        if ("Movie".equalsIgnoreCase(mediaType)) {
            List<Movie> movies = Movie.findAll(Sort.by("title")).page(p).list();
            long totalCount = Movie.count();
            Map<String, MediaFile> mfMap = MediaFile.<MediaFile>list("path in ?1", movies.stream().map(m -> m.videoPath).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));
            List<VideoDTO> dtos = movies.stream().map(m -> movieToDTO(m, mfMap.get(m.videoPath))).filter(Objects::nonNull).collect(Collectors.toList());
            return new PaginatedVideos(dtos, totalCount);
        }
        
        if ("Episode".equalsIgnoreCase(mediaType)) {
            List<Episode> episodes = Episode.findAll(Sort.by("season.show.name").and("seasonNumber").and("episodeNumber")).page(p).list();
            long totalCount = Episode.count();
            Map<String, MediaFile> mfMap = MediaFile.<MediaFile>list("path in ?1", episodes.stream().map(e -> e.videoPath).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));
            List<VideoDTO> dtos = episodes.stream().map(e -> episodeToDTO(e, mfMap.get(e.videoPath))).filter(Objects::nonNull).collect(Collectors.toList());
            return new PaginatedVideos(dtos, totalCount);
        }

        // Fallback for null or other mediaTypes: just return movies for now.
        List<Movie> movies = Movie.findAll(Sort.by("title")).page(p).list();
        long totalCount = Movie.count() + Episode.count(); // More accurate total
        Map<String, MediaFile> mfMap = MediaFile.<MediaFile>list("path in ?1", movies.stream().map(m -> m.videoPath).collect(Collectors.toList()))
            .stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));
        List<VideoDTO> dtos = movies.stream().map(m -> movieToDTO(m, mfMap.get(m.videoPath))).filter(Objects::nonNull).collect(Collectors.toList());
        return new PaginatedVideos(dtos, totalCount);
    }
    
    public List<String> findAllSeriesTitles() {
        List<String> titles = Show.streamAll(Sort.by("name")).map(s -> ((Show)s).name).collect(Collectors.toList());
        System.out.println("DEBUG: All series titles in database: " + titles);
        return titles;
    }

    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        System.out.println("DEBUG: Looking for show with name: '" + seriesTitle + "'");
        Show show = Show.find("name", seriesTitle).firstResult();
        if (show == null) {
            System.out.println("DEBUG: Show not found in database");
            return Collections.emptyList();
        }
        System.out.println("DEBUG: Found show: " + show.name);
        List<Integer> seasons = Season.stream("show", Sort.by("seasonNumber"), show).map(s -> ((Season)s).seasonNumber).collect(Collectors.toList());
        System.out.println("DEBUG: Found seasons: " + seasons);
        return seasons;
    }

    public List<VideoDTO> findEpisodesForSeason(String seriesTitle, Integer seasonNumber) {
        Show show = Show.find("name", seriesTitle).firstResult();
        if (show == null) return Collections.emptyList();
        Season season = Season.find("show = ?1 and seasonNumber = ?2", show, seasonNumber).firstResult();
        if (season == null) return Collections.emptyList();
        
        List<Episode> episodes = Episode.list("season", Sort.by("episodeNumber"), season);
        Map<String, MediaFile> mfMap = MediaFile.<MediaFile>list("path in ?1", episodes.stream().map(e -> e.videoPath).collect(Collectors.toList()))
            .stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));

        return episodes.stream()
                       .map(e -> episodeToDTO(e, mfMap.get(e.videoPath)))
                       .filter(Objects::nonNull)
                       .collect(Collectors.toList());
    }
    
    @Transactional
    public VideoDTO findByPath(String path) {
        MediaFile mf = MediaFile.find("path", path).firstResult();
        if (mf == null) return null;
        return find(mf.id);
    }
}