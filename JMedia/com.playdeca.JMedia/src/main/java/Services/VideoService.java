package Services;

import Models.Episode;
import Models.MediaFile;
import Models.Movie;
import Models.Season;
import Models.Show;
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
        int height,
        String thumbnailPath // Added for carousel support
    ) {}

    private VideoDTO episodeToDTO(Episode episode, MediaFile mf) {
        Show show = episode.season != null ? episode.season.show : null;
        String seriesTitle = show != null ? show.name : "Unknown Show";
        String title = String.format("%s - S%02dE%02d", seriesTitle, episode.seasonNumber, episode.episodeNumber);
        if (episode.title != null && !episode.title.isBlank()) {
            title += " - " + episode.title;
        }
        // For now, generate placeholder thumbnail path - could be enhanced with actual thumbnail service
        String thumbnailPath = "/api/video/thumbnail/" + mf.id;
        return new VideoDTO(mf.id, mf.path, "Episode", title, seriesTitle, episode.seasonNumber, episode.episodeNumber, episode.title, null, mf.durationSeconds, mf.width, mf.height, thumbnailPath);
    }
    
    private VideoDTO movieToDTO(Movie movie, MediaFile mf) {
         // For now, generate placeholder thumbnail path - could be enhanced with actual thumbnail service
         String thumbnailPath = "/api/video/thumbnail/" + mf.id;
         return new VideoDTO(mf.id, mf.path, "Movie", movie.title, null, null, null, null, movie.releaseYear, mf.durationSeconds, mf.width, mf.height, thumbnailPath);
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

    // ==================== CAROUSEL ALGORITHM METHODS ====================
    
    @Transactional
    public List<VideoDTO> getNewReleases(int daysBack, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysBack);
        long cutoffTimestamp = cutoff.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<MediaFile> recentFiles = MediaFile.list("type = ?1 AND lastModified >= ?2", Sort.by("lastModified").descending(), "video", cutoffTimestamp);
        
        List<VideoDTO> videos = new ArrayList<>();
        Map<String, MediaFile> mfMap = recentFiles.stream().collect(Collectors.toMap(mf -> mf.path, Function.identity()));
        
        // Check for movies
        Movie.<Movie>stream("videoPath in ?1", mfMap.keySet()).forEach(movie -> {
            MediaFile mf = mfMap.get(movie.videoPath);
            if (mf != null) {
                videos.add(movieToDTO(movie, mf));
            }
        });
        
        // Check for episodes
        Episode.<Episode>stream("videoPath in ?1", mfMap.keySet()).forEach(episode -> {
            MediaFile mf = mfMap.get(episode.videoPath);
            if (mf != null) {
                videos.add(episodeToDTO(episode, mf));
            }
        });
        
        // Sort by lastModified and limit
        videos.sort((v1, v2) -> {
            MediaFile mf1 = MediaFile.findById(v1.id());
            MediaFile mf2 = MediaFile.findById(v2.id());
            long time1 = mf1 != null ? mf1.lastModified : 0;
            long time2 = mf2 != null ? mf2.lastModified : 0;
            return Long.compare(time2, time1); // reverse order (newer first)
        });
        
        return videos.stream().limit(limit).collect(Collectors.toList());
    }
    
    @Transactional
    public List<VideoDTO> getLatestEpisodesBySeries(int limit) {
        // Get the most recent episode for each series
        Map<String, Episode> latestEpisodes = new HashMap<>();
        
        Episode.<Episode>streamAll().forEach(episode -> {
            String seriesName = episode.season != null && episode.season.show != null ? episode.season.show.name : null;
            if (seriesName != null) {
                Episode existing = latestEpisodes.get(seriesName);
                if (existing == null || isNewerEpisode(episode, existing)) {
                    latestEpisodes.put(seriesName, episode);
                }
            }
        });
        
        List<VideoDTO> videos = new ArrayList<>();
        for (Episode episode : latestEpisodes.values()) {
            MediaFile mf = MediaFile.find("path", episode.videoPath).firstResult();
            if (mf != null) {
                videos.add(episodeToDTO(episode, mf));
            }
        }
        
        // Sort by series name and limit
        videos.sort(Comparator.comparing(v -> v.seriesTitle()));
        return videos.stream().limit(limit).collect(Collectors.toList());
    }
    
    private boolean isNewerEpisode(Episode episode1, Episode episode2) {
        if (episode1.seasonNumber > episode2.seasonNumber) return true;
        if (episode1.seasonNumber < episode2.seasonNumber) return false;
        return episode1.episodeNumber > episode2.episodeNumber;
    }
    
    @Transactional
    public List<VideoDTO> getFeaturedContent(int limit, Map<Long, Integer> playCounts) {
        List<VideoDTO> allVideos = findAll();
        
        // Sort by play count, then recency (fallback to random)
        if (playCounts != null && !playCounts.isEmpty()) {
            allVideos.sort((v1, v2) -> {
                Integer count1 = playCounts.getOrDefault(v1.id(), 0);
                Integer count2 = playCounts.getOrDefault(v2.id(), 0);
                int countCompare = count2.compareTo(count1);
                if (countCompare != 0) return countCompare;
                
                // If same play count, favor newer content
                MediaFile mf1 = MediaFile.findById(v1.id());
                MediaFile mf2 = MediaFile.findById(v2.id());
                if (mf1 != null && mf2 != null) {
                    long time1 = mf1.lastModified;
                    long time2 = mf2.lastModified;
                    return Long.compare(time2, time1); // reverse order (newer first)
                }
                return 0;
            });
        } else {
            // Fallback to newer content preference with stable random tiebreaker
            Random random = new Random();
            allVideos.sort((v1, v2) -> {
                MediaFile mf1 = MediaFile.findById(v1.id());
                MediaFile mf2 = MediaFile.findById(v2.id());
                if (mf1 != null && mf2 != null) {
                    // Primary sort: newer content first
                    long time1 = mf1.lastModified;
                    long time2 = mf2.lastModified;
                    int timeCompare = Long.compare(time2, time1); // reverse order (newer first)
                    if (timeCompare != 0) {
                        return timeCompare;
                    }
                }
                // Tie-breaker: use hash-based deterministic ordering
                return Integer.compare(v1.hashCode(), v2.hashCode());
            });
        }
        
        return allVideos.stream().limit(limit).collect(Collectors.toList());
    }
    
    @Transactional
    public List<VideoDTO> enrichWithThumbnails(List<VideoDTO> videos) {
        // For now, use placeholder logic - this could be enhanced with actual thumbnail service
        return videos.stream().map(dto -> {
            // Add thumbnail path logic here when thumbnail service is available
            String thumbnailPath = "/api/video/thumbnail/" + dto.id();
            return new VideoDTO(dto.id(), dto.path(), dto.type(), dto.title(), dto.seriesTitle(), 
                                dto.seasonNumber(), dto.episodeNumber(), dto.episodeTitle(), 
                                dto.releaseYear(), dto.durationSeconds(), dto.width(), dto.height(), 
                                thumbnailPath);
        }).collect(Collectors.toList());
    }
}