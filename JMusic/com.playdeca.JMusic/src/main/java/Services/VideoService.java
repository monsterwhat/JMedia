package Services;

import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoService {

    @PersistenceContext
    private EntityManager em;

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

    public Video find(Long id) {
        return em.find(Video.class, id);
    }

    public List<Video> findAll() {
        return em.createQuery("SELECT v FROM Video v", Video.class)
                .getResultList();
    }

    public List<Video> findByMediaType(String mediaType) {
        return em.createQuery("SELECT v FROM Video v WHERE v.mediaType = :mediaType ORDER BY v.seriesTitle, v.releaseYear, v.seasonNumber, v.episodeNumber", Video.class)
                .setParameter("mediaType", mediaType)
                .getResultList();
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
