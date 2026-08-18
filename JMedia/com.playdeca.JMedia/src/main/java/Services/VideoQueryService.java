package Services;

import Models.Settings.Profile;
import Models.Video.Video;
import Models.Video.VideoHistory;
import Services.SmartNamingService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

@ApplicationScoped
public class VideoQueryService {

    @PersistenceContext(unitName = "video")
    private EntityManager em;

    @Inject
    SettingsService settingsService;

    // ========== PAGINATION METHODS ==========
    
    private static final java.util.Set<String> ALLOWED_SORT_FIELDS = java.util.Set.of(
        "title", "dateAdded", "lastWatched", "type", "duration", "seriesTitle",
        "episodeTitle", "seasonNumber", "episodeNumber", "filename"
    );

    @Transactional
    public PaginatedVideos findPaginatedByMediaType(String mediaType, int page, int limit, String sortBy, String sortDirection, String search) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.Descending : Sort.Direction.Ascending;
        String sortField = sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "dateAdded";
        
        if (search == null || search.trim().isEmpty()) {
            List<Video> videos = Video.<Video>find("type = ?1 AND isActive = true",
                    Sort.by(sortField, direction), mediaType)
                    .page(Page.of(page - 1, limit))
                    .list();
            
            long totalCount = Video.count("type = ?1 AND isActive = true", mediaType);
            return new PaginatedVideos(videos, totalCount);
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.type = :type AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s OR " +
                         "EXISTS (SELECT 1 FROM v.cast c WHERE LOWER(c) LIKE :s) OR " +
                         "EXISTS (SELECT 1 FROM v.directors d WHERE LOWER(d) LIKE :s) OR " +
                         "EXISTS (SELECT 1 FROM v.writers w WHERE LOWER(w) LIKE :s))";
            
            List<Video> videos = em.createQuery("SELECT v " + hql + " ORDER BY v." + sortField + " " + (sortDirection.equalsIgnoreCase("desc") ? "DESC" : "ASC"), Video.class)
                    .setParameter("type", mediaType)
                    .setParameter("s", s)
                    .setFirstResult((page - 1) * limit)
                    .setMaxResults(limit)
                    .getResultList();
            
            long totalCount = em.createQuery("SELECT COUNT(v) " + hql, Long.class)
                    .setParameter("type", mediaType)
                    .setParameter("s", s)
                    .getSingleResult();
            
            return new PaginatedVideos(videos, totalCount);
        }
    }

    @Transactional
    public PaginatedSeries findPaginatedSeriesTitles(int page, int limit, String sortBy, String sortDirection, String search) {
        String baseHql = "SELECT v.seriesTitle, v.dateAdded, v.lastWatched FROM Video v WHERE v.type = 'episode' AND v.seriesTitle IS NOT NULL AND v.isActive = true";
        TypedQuery<Object[]> query;
        
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.toLowerCase() + "%";
            String searchHql = " AND (LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s OR " +
                               "EXISTS (SELECT 1 FROM v.cast c WHERE LOWER(c) LIKE :s) OR " +
                               "EXISTS (SELECT 1 FROM v.directors d WHERE LOWER(d) LIKE :s) OR " +
                               "EXISTS (SELECT 1 FROM v.writers w WHERE LOWER(w) LIKE :s))";
            query = em.createQuery(baseHql + searchHql, Object[].class).setParameter("s", s);
        } else {
            query = em.createQuery(baseHql, Object[].class);
        }

        List<Object[]> episodesData = query.getResultList();

        // Group by normalized seriesTitle using SmartNamingService
        Map<String, SeriesSortData> seriesMap = new LinkedHashMap<>();
        Map<String, String> normalizedToOriginalMap = new HashMap<>();

        for (Object[] row : episodesData) {
            String seriesTitle = (String) row[0];
            LocalDateTime dateAdded = (LocalDateTime) row[1];
            LocalDateTime lastWatched = (LocalDateTime) row[2];
            
            // Use SmartNamingService to get a normalized key for grouping
            String normalizedKey = SmartNamingService.cleanShowName(seriesTitle).toLowerCase().trim();
            if (normalizedKey.isEmpty()) normalizedKey = seriesTitle.toLowerCase().trim();

            SeriesSortData existing = seriesMap.get(normalizedKey);
            if (existing == null) {
                seriesMap.put(normalizedKey, new SeriesSortData(dateAdded, lastWatched));
                normalizedToOriginalMap.put(normalizedKey, seriesTitle);
            } else {
                // Update based on sort field
                if ("dateAdded".equals(sortBy)) {
                    if (dateAdded != null && (existing.dateAdded == null || dateAdded.isAfter(existing.dateAdded))) {
                        existing.dateAdded = dateAdded;
                        normalizedToOriginalMap.put(normalizedKey, seriesTitle);
                    }
                } else if ("lastWatched".equals(sortBy)) {
                    if (lastWatched != null && (existing.lastWatched == null || lastWatched.isAfter(existing.lastWatched))) {
                        existing.lastWatched = lastWatched;
                        normalizedToOriginalMap.put(normalizedKey, seriesTitle);
                    }
                }
            }
        }

        List<String> groupKeys = new ArrayList<>(seriesMap.keySet());
        boolean desc = "desc".equalsIgnoreCase(sortDirection);

        Comparator<String> comparator;
        if ("dateAdded".equals(sortBy)) {
            comparator = Comparator.comparing(key -> seriesMap.get(key).dateAdded, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("lastWatched".equals(sortBy)) {
            comparator = Comparator.comparing(key -> seriesMap.get(key).lastWatched, Comparator.nullsFirst(Comparator.naturalOrder()));
        } else {
            comparator = Comparator.comparing(key -> normalizedToOriginalMap.get(key), String.CASE_INSENSITIVE_ORDER);
        }

        if (desc) comparator = comparator.reversed();
        groupKeys.sort(comparator);

        long totalCount = groupKeys.size();
        List<String> pagedTitles = groupKeys.stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .map(normalizedToOriginalMap::get)
                .collect(Collectors.toList());

        return new PaginatedSeries(pagedTitles, totalCount);
    }

    @Transactional
    public List<Video> findHistory(String search, int limit) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();

        String hql = "SELECT h FROM VideoHistory h JOIN h.mediaFile mf JOIN Video v ON v.path = mf.path WHERE v.isActive = true AND h.profileId = :profileId";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY h.playedAt DESC";

        TypedQuery<Models.Video.VideoHistory> query = em.createQuery(hql, Models.Video.VideoHistory.class);
        query.setParameter("profileId", activeProfile.id);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }

        List<Models.Video.VideoHistory> history = query.getResultList();

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        List<Models.Video.Video> videos = new ArrayList<>();

        for (Models.Video.VideoHistory h : history) {
            if (h.mediaFile != null && seenPaths.add(h.mediaFile.path)) {
                Models.Video.Video v = Video.find("path", h.mediaFile.path).firstResult();
                if (v != null) videos.add(v);
            }
            if (videos.size() >= limit) break;
        }
        return videos;
    }

    @Transactional
    public List<Video> findWatchlist(String search) {
        if (search == null || search.trim().isEmpty()) {
            return Video.list("favorite = true AND isActive = true");
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.favorite = true AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s)";
            return em.createQuery("SELECT v " + hql + " ORDER BY v.favoritedAt DESC", Video.class)
                    .setParameter("s", s)
                    .getResultList();
        }
    }
    
    public record VideoHistoryEntry(Video video, Models.Video.VideoHistory history, Long profileId) {}
    
    @Transactional
    public List<VideoHistoryEntry> findAllHistory(String search, int limit) {
        String hql = "SELECT vh FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY vh.playedAt DESC";
        
        TypedQuery<Models.Video.VideoHistory> query = em.createQuery(hql, Models.Video.VideoHistory.class);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        query.setMaxResults(limit);
        
        List<Models.Video.VideoHistory> historyList = query.getResultList();
        List<VideoHistoryEntry> entries = new ArrayList<>();
        
        for (Models.Video.VideoHistory vh : historyList) {
            if (vh.mediaFile != null) {
                Video video = Video.find("path", vh.mediaFile.path).firstResult();
                if (video != null) {
                    entries.add(new VideoHistoryEntry(video, vh, vh.profileId));
                }
            }
        }
        return entries;
    }

    @Transactional
    public PaginatedVideos findHistoryPaginated(String search, int page, int limit) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return new PaginatedVideos(List.of(), 0);

        String hql = "SELECT h FROM VideoHistory h JOIN h.mediaFile mf JOIN Video v ON v.path = mf.path WHERE v.isActive = true AND h.profileId = :profileId";
        if (search != null && !search.trim().isEmpty()) {
            hql += " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
        }
        hql += " ORDER BY h.playedAt DESC";

        TypedQuery<Models.Video.VideoHistory> query = em.createQuery(hql, Models.Video.VideoHistory.class);
        query.setParameter("profileId", activeProfile.id);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }

        List<Models.Video.VideoHistory> allHistory = query.getResultList();

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        List<Video> allVideos = new ArrayList<>();
        for (Models.Video.VideoHistory h : allHistory) {
            if (h.mediaFile != null && seenPaths.add(h.mediaFile.path)) {
                Video v = Video.find("path", h.mediaFile.path).firstResult();
                if (v != null) allVideos.add(v);
            }
        }

        long totalCount = allVideos.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, allVideos.size());
        List<Video> pageVideos = fromIndex >= allVideos.size() ? List.of() : allVideos.subList(fromIndex, toIndex);
        return new PaginatedVideos(pageVideos, totalCount);
    }

    @Transactional
    public PaginatedVideos findWatchlistPaginated(String search, int page, int limit) {
        List<Video> all;
        if (search == null || search.trim().isEmpty()) {
            all = Video.list("favorite = true AND isActive = true");
        } else {
            String s = "%" + search.toLowerCase() + "%";
            String hql = "FROM Video v WHERE v.favorite = true AND v.isActive = true AND (" +
                         "LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR " +
                         "LOWER(v.description) LIKE :s OR LOWER(v.overview) LIKE :s OR LOWER(v.filename) LIKE :s)";
            all = em.createQuery("SELECT v " + hql + " ORDER BY v.favoritedAt DESC", Video.class)
                    .setParameter("s", s)
                    .getResultList();
        }

        long totalCount = all.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, all.size());
        List<Video> pageVideos = fromIndex >= all.size() ? List.of() : all.subList(fromIndex, toIndex);
        return new PaginatedVideos(pageVideos, totalCount);
    }

    public static class PaginatedHistoryEntries {
        public final List<VideoHistoryEntry> entries;
        public final long totalCount;
        public PaginatedHistoryEntries(List<VideoHistoryEntry> entries, long totalCount) {
            this.entries = entries;
            this.totalCount = totalCount;
        }
    }

    @Transactional
    public PaginatedHistoryEntries findAllHistoryPaginated(String search, int page, int limit) {
        String countHql = "SELECT COUNT(vh) FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        String hql = "SELECT vh FROM VideoHistory vh JOIN Video v ON v.path = vh.mediaFile.path WHERE v.isActive = true";
        if (search != null && !search.trim().isEmpty()) {
            String searchClause = " AND (LOWER(v.title) LIKE :s OR LOWER(v.seriesTitle) LIKE :s OR LOWER(v.episodeTitle) LIKE :s OR LOWER(v.description) LIKE :s)";
            countHql += searchClause;
            hql += searchClause;
        }
        hql += " ORDER BY vh.playedAt DESC";

        TypedQuery<Long> countQ = em.createQuery(countHql, Long.class);
        if (search != null && !search.trim().isEmpty()) {
            countQ.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        long totalCount = countQ.getSingleResult();

        TypedQuery<Models.Video.VideoHistory> query = em.createQuery(hql, Models.Video.VideoHistory.class);
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("s", "%" + search.toLowerCase() + "%");
        }
        query.setFirstResult((page - 1) * limit);
        query.setMaxResults(limit);

        List<Models.Video.VideoHistory> historyList = query.getResultList();
        List<VideoHistoryEntry> entries = new ArrayList<>();
        for (Models.Video.VideoHistory vh : historyList) {
            if (vh.mediaFile != null) {
                Video video = Video.find("path", vh.mediaFile.path).firstResult();
                if (video != null) {
                    entries.add(new VideoHistoryEntry(video, vh, vh.profileId));
                }
            }
        }
        return new PaginatedHistoryEntries(entries, totalCount);
    }

    /**
     * Find videos that need manual attention from the metadata enrichment worker.
     * Returns videos with missing external IDs, missing season/episode numbers,
     * or that likely failed enrichment (active for >1 hour with no IDs).
     */
    @Transactional
    public List<Video> findVideosNeedingAttention(int maxResults) {
        String jpql = "SELECT v FROM Video v WHERE v.isActive = true AND v.titleManuallyEdited = false AND (" +
                "v.imdbId IS NULL OR v.tmdbId IS NULL OR " +
                "(v.type = 'episode' AND v.showImdbId IS NULL) OR " +
                "(v.type = 'episode' AND (v.seasonNumber IS NULL OR v.episodeNumber IS NULL))" +
                ") ORDER BY v.dateAdded DESC";
        return em.createQuery(jpql, Video.class)
                .setMaxResults(maxResults)
                .getResultList();
    }

    /**
     * Find videos that have been enriched (have external IDs) for verification.
     * These are candidates where re-running enrichment might produce different
     * values worth human review — as opposed to findVideosNeedingAttention which
     * finds videos with missing data.
     */
    @Transactional
    public List<Video> findVideosForVerification(int maxResults) {
        String jpql = "SELECT v FROM Video v WHERE v.isActive = true AND v.titleManuallyEdited = false AND (" +
                "(v.type = 'movie' AND v.imdbId IS NOT NULL AND v.imdbId != '' AND v.tmdbId IS NOT NULL AND v.tmdbId != '')" +
                " OR " +
                "(v.type = 'episode' AND v.imdbId IS NOT NULL AND v.imdbId != '' AND " +
                " v.tmdbId IS NOT NULL AND v.tmdbId != '' AND " +
                " v.showImdbId IS NOT NULL AND v.showImdbId != '' AND " +
                " v.seasonNumber IS NOT NULL AND v.episodeNumber IS NOT NULL)" +
                ") ORDER BY v.dateModified DESC";
        return em.createQuery(jpql, Video.class)
                .setMaxResults(maxResults)
                .getResultList();
    }

    private static class SeriesSortData {
        public LocalDateTime dateAdded;
        public LocalDateTime lastWatched;
        public SeriesSortData(LocalDateTime dateAdded, LocalDateTime lastWatched) {
            this.dateAdded = dateAdded;
            this.lastWatched = lastWatched;
        }
    }
    // ========== PAGINATION HELPER ==========

    public static class PaginatedVideos {
        public final List<Video> videos;
        public final long totalCount;

        public PaginatedVideos(List<Video> videos, long totalCount) {
            this.videos = videos;
            this.totalCount = totalCount;
        }
    }

    public static class PaginatedSeries {
        public final List<String> titles;
        public final long totalCount;

        public PaginatedSeries(List<String> titles, long totalCount) {
            this.titles = titles;
            this.totalCount = totalCount;
        }
    }
}
