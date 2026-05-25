package Services;

import Models.CollectionEntry;
import Models.MediaCollection;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CollectionService {

    @Inject
    EntityManager em;

    @Transactional
    public List<MediaCollection> listCollections() {
        return MediaCollection.listAll();
    }

    @Transactional
    public MediaCollection getCollection(Long id) {
        return MediaCollection.findById(id);
    }

    @Transactional
    public MediaCollection create(String name, String description) {
        MediaCollection c = new MediaCollection();
        c.name = name;
        c.description = description;
        c.sortOrder = 0;
        c.createdDate = LocalDateTime.now();
        c.persist();
        return c;
    }

    @Transactional
    public MediaCollection update(Long id, String name, String description) {
        MediaCollection c = MediaCollection.findById(id);
        if (c == null) return null;
        if (name != null) c.name = name;
        if (description != null) c.description = description;
        em.merge(c);
        return c;
    }

    @Transactional
    public boolean delete(Long id) {
        MediaCollection c = MediaCollection.findById(id);
        if (c == null) return false;
        CollectionEntry.delete("collection = ?1", c);
        c.delete();
        return true;
    }

    @Transactional
    public List<CollectionEntry> getEntries(Long collectionId) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return List.of();
        return CollectionEntry.list("collection = ?1 order by orderIndex asc", c);
    }

    @Transactional
    public CollectionEntry addEntry(Long collectionId, Long videoId, int orderIndex, String notes) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return null;
        Video v = Video.findById(videoId);
        if (v == null) return null;
        CollectionEntry e = new CollectionEntry();
        e.collection = c;
        e.video = v;
        e.orderIndex = orderIndex;
        e.notes = notes;
        e.persist();
        return e;
    }

    @Transactional
    public CollectionEntry updateEntry(Long entryId, Integer orderIndex, String notes) {
        CollectionEntry e = CollectionEntry.findById(entryId);
        if (e == null) return null;
        if (orderIndex != null) e.orderIndex = orderIndex;
        if (notes != null) e.notes = notes;
        em.merge(e);
        return e;
    }

    @Transactional
    public boolean removeEntry(Long entryId) {
        CollectionEntry e = CollectionEntry.findById(entryId);
        if (e == null) return false;
        e.delete();
        return true;
    }

    @Transactional
    public boolean reorderEntries(Long collectionId, Map<Long, Integer> entryOrderMap) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return false;
        for (Map.Entry<Long, Integer> entry : entryOrderMap.entrySet()) {
            CollectionEntry e = CollectionEntry.findById(entry.getKey());
            if (e != null && e.collection.id.equals(collectionId)) {
                e.orderIndex = entry.getValue();
                em.merge(e);
            }
        }
        return true;
    }

    @Transactional
    public List<Video> getAllActiveVideos() {
        return Video.list("isActive", true);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> organizeActiveVideos(Map<Long, Long> videoEntryMap) {
        List<Video> videos = Video.list("isActive", true);

        List<Map<String, Object>> movies = new ArrayList<>();
        Map<String, List<Map<String, Object>>> seriesEps = new LinkedHashMap<>();

        for (Video v : videos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", v.id);
            item.put("title", v.title != null ? v.title : "");
            item.put("seriesTitle", v.seriesTitle != null ? v.seriesTitle : "");
            item.put("type", v.type != null ? v.type : "");
            item.put("seasonNumber", v.seasonNumber != null ? v.seasonNumber : 0);
            item.put("episodeNumber", v.episodeNumber != null ? v.episodeNumber : 0);
            item.put("releaseYear", v.releaseYear);
            Long entryId = videoEntryMap != null ? videoEntryMap.get(v.id) : null;
            item.put("inCollection", entryId != null);
            item.put("entryId", entryId);

            boolean isEpisode = v.type != null && v.type.equalsIgnoreCase("episode");
            boolean hasSeries = v.seriesTitle != null && !v.seriesTitle.isBlank();

            if (isEpisode && hasSeries) {
                seriesEps.computeIfAbsent(v.seriesTitle, k -> new ArrayList<>()).add(item);
            } else {
                movies.add(item);
            }
        }

        List<Map<String, Object>> seriesList = new ArrayList<>();
        List<String> sortedKeys = new ArrayList<>(seriesEps.keySet());
        sortedKeys.sort(String.CASE_INSENSITIVE_ORDER);

        for (String key : sortedKeys) {
            List<Map<String, Object>> eps = seriesEps.get(key);
            eps.sort(Comparator.comparingInt((Map<String, Object> e) -> (int) e.get("seasonNumber"))
                    .thenComparingInt(e -> (int) e.get("episodeNumber")));

            Map<Integer, List<Map<String, Object>>> seasonMap = new LinkedHashMap<>();
            for (Map<String, Object> ep : eps) {
                int sn = (int) ep.get("seasonNumber");
                seasonMap.computeIfAbsent(sn, k -> new ArrayList<>()).add(ep);
            }

            List<Map<String, Object>> seasons = new ArrayList<>();
            List<Integer> seasonKeys = new ArrayList<>(seasonMap.keySet());
            seasonKeys.sort(Integer::compareTo);
            int totalEpisodes = 0;
            for (int sn : seasonKeys) {
                List<Map<String, Object>> episodeList = seasonMap.get(sn);
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("seasonNumber", sn);
                sm.put("episodes", episodeList);
                sm.put("total", episodeList.size());
                sm.put("thumbnailId", episodeList.isEmpty() ? null : episodeList.get(0).get("id"));
                seasons.add(sm);
                totalEpisodes += episodeList.size();
            }

            Map<String, Object> sg = new LinkedHashMap<>();
            sg.put("seriesTitle", key);
            sg.put("seasons", seasons);
            sg.put("total", totalEpisodes);
            sg.put("thumbnailId", seasons.isEmpty() ? null : ((Map<String, Object>) seasons.get(0)).get("thumbnailId"));
            seriesList.add(sg);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (!movies.isEmpty()) result.put("movies", movies);
        if (!seriesList.isEmpty()) result.put("seriesList", seriesList);
        return result;
    }
}
