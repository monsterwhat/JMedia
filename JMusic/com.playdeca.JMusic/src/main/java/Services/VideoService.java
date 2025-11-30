package Services;

import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoService {

    @PersistenceContext
    private EntityManager em;

    // Inner record for paginated video results
    public record PaginatedVideos(List<Video> videos, long totalCount) {}

    @Transactional
    public void save(Video video) {
        if (video.id == null || em.find(Video.class, video.id) == null) {
            em.persist(video);
        } else {
            em.merge(video);
        }
    }

    @Transactional(value = Transactional.TxType.REQUIRES_NEW)
    public Video persistVideoInNewTx(Video video) {
        if (video.id == null || em.find(Video.class, video.id) == null) {
            em.persist(video);
        } else {
            em.merge(video);
        }
        em.flush(); // Ensure changes are written to DB within this transaction
        return video;
    }

    @Transactional
    public void delete(Video video) {
        if (video != null) {
            Video managed = em.contains(video) ? video : em.merge(video);
            em.remove(managed);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Video find(Long id) {
        return em.find(Video.class, id);
    }

    @Transactional
    public List<Video> findAll() {
        return em.createQuery("SELECT v FROM Video v", Video.class)
                .getResultList();
    }

    // New method to find videos by a list of IDs
    public List<Video> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // Ensure the order of results matches the order of IDs in the input list
        // This is important for queue management
        String query = "SELECT v FROM Video v WHERE v.id IN :ids";
        List<Video> results = em.createQuery(query, Video.class)
                               .setParameter("ids", ids)
                               .getResultList();
        
        // Manual sorting to preserve the order of input IDs
        // This is O(N log N) or O(N*M) depending on map implementation, but necessary
        // if the database doesn't guarantee order for IN clause.
        java.util.Map<Long, Video> videoMap = results.stream()
                .collect(Collectors.toMap(video -> video.id, video -> video, (existing, replacement) -> existing));
        
        return ids.stream()
                  .map(videoMap::get)
                  .filter(java.util.Objects::nonNull)
                  .collect(Collectors.toList());
    }


    public List<Video> findByMediaType(String mediaType) {
        return em.createQuery("SELECT v FROM Video v WHERE v.mediaType = :mediaType ORDER BY v.seriesTitle, v.releaseYear, v.seasonNumber, v.episodeNumber", Video.class)
                .setParameter("mediaType", mediaType)
                .getResultList();
    }

    // Modified method for paginated lookup, returning PaginatedVideos record
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit) {
        // Ensure page and limit are valid
        if (page < 1) page = 1; // Page numbers are 1-based
        if (limit <= 0) limit = 50; // Default limit

        long totalCount = countByMediaType(mediaType);
        
        // Calculate offset (first result) for 0-based indexing by JPA
        int offset = (page - 1) * limit;

        TypedQuery<Video> query = em.createQuery(
                "SELECT v FROM Video v WHERE v.mediaType = :mediaType ORDER BY v.seriesTitle, v.releaseYear, v.seasonNumber, v.episodeNumber",
                Video.class);
        query.setParameter("mediaType", mediaType);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        
        List<Video> videos = query.getResultList();
        return new PaginatedVideos(videos, totalCount);
    }

    // New method to count total items for pagination (already exists, but keeping for clarity)
    public long countByMediaType(String mediaType) {
        return em.createQuery("SELECT COUNT(v) FROM Video v WHERE v.mediaType = :mediaType", Long.class)
                .setParameter("mediaType", mediaType)
                .getSingleResult();
    }

    public List<String> findAllSeriesTitles() {
        return em.createQuery("SELECT DISTINCT v.seriesTitle FROM Video v WHERE v.mediaType = 'Episode' AND v.seriesTitle IS NOT NULL ORDER BY v.seriesTitle", String.class)
                .getResultList();
    }

    public List<Integer> findSeasonNumbersForSeries(String seriesTitle) {
        return em.createQuery("SELECT DISTINCT v.seasonNumber FROM Video v WHERE v.seriesTitle = :seriesTitle AND v.mediaType = 'Episode' AND v.seasonNumber IS NOT NULL ORDER BY v.seasonNumber", Integer.class)
                .setParameter("seriesTitle", seriesTitle)
                .getResultList();
    }

    public List<Video> findEpisodesForSeason(String seriesTitle, Integer seasonNumber) {
        return em.createQuery("SELECT v FROM Video v WHERE v.seriesTitle = :seriesTitle AND v.seasonNumber = :seasonNumber AND v.mediaType = 'Episode' ORDER BY v.episodeNumber", Video.class)
                .setParameter("seriesTitle", seriesTitle)
                .setParameter("seasonNumber", seasonNumber)
                .getResultList();
    }

    @Transactional
    public Video findByPath(String path) {
        try {
            return em.createQuery("SELECT v FROM Video v WHERE v.path = :path", Video.class)
                    .setParameter("path", path)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }
}
