package Services;

import Models.CollectionEntry;
import Models.ExternalVideo;
import Models.ExistingVideo;
import Models.MediaCollection;
import Models.Profile;
import Models.Video;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CollectionService {

    @Inject
    EntityManager em;

    @Inject
    SettingsService settingsService;

    @Transactional
    public List<MediaCollection> listCollections(Profile activeProfile, boolean isAdmin) {
        if (isAdmin) return MediaCollection.listAll();
        if (activeProfile == null) return MediaCollection.list("profile is null");
        return MediaCollection.list("profile is null or isPublic = true or profile = ?1", activeProfile);
    }

    @Transactional
    public List<MediaCollection> findPaginatedCollections(int page, int limit, Profile activeProfile, boolean isAdmin) {
        if (isAdmin) {
            return MediaCollection.findAll()
                    .page(Page.of(page - 1, limit))
                    .list();
        }
        if (activeProfile == null) {
            return MediaCollection.find("profile is null")
                    .page(Page.of(page - 1, limit))
                    .list();
        }
        return MediaCollection.find("profile is null or isPublic = true or profile = ?1", activeProfile)
                .page(Page.of(page - 1, limit))
                .list();
    }

    @Transactional
    public long countCollections(Profile activeProfile, boolean isAdmin) {
        if (isAdmin) return MediaCollection.count();
        if (activeProfile == null) return MediaCollection.count("profile is null");
        return MediaCollection.count("profile is null or isPublic = true or profile = ?1", activeProfile);
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
    public MediaCollection create(String name, String description, Profile profile, boolean isPublic) {
        MediaCollection c = new MediaCollection();
        c.name = name;
        c.description = description;
        c.profile = profile;
        c.isPublic = isPublic;
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
    public CollectionEntry addEntryWithExternalVideo(Long collectionId, Long externalVideoId, int orderIndex, String notes) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return null;
        ExternalVideo ev = ExternalVideo.findById(externalVideoId);
        if (ev == null) return null;
        CollectionEntry e = new CollectionEntry();
        e.collection = c;
        e.externalVideo = ev;
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
        updateCoverVideo(c);
        return true;
    }

    @Transactional
    public List<MediaCollection> findCollectionsForProfile(Profile profile) {
        if (profile == null) return List.of();
        return MediaCollection.list("profile is null or isPublic = true or profile = ?1", profile);
    }

    @Transactional
    public List<MediaCollection> findMyCollections(Profile profile) {
        if (profile == null) return List.of();
        return MediaCollection.list("profile = ?1", profile);
    }

    @Transactional
    public List<Video> getAllActiveVideos() {
        return Video.list("isActive", true);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> organizeActiveVideos(Map<Long, Long> videoEntryMap, Map<Long, Long> externalVideoEntryMap) {
        List<Video> videos = Video.list("isActive", true);
        List<ExternalVideo> externalVideos = ExternalVideo.listAll();

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
            item.put("mediaType", "video");
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

        for (ExternalVideo ev : externalVideos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ev.id);
            item.put("title", ev.title != null ? ev.title : "");
            item.put("seriesTitle", ev.seriesTitle != null ? ev.seriesTitle : "");
            item.put("type", ev.entryType == ExistingVideo.EPISODE ? "episode" : ev.entryType == ExistingVideo.MOVIE ? "movie" : "");
            item.put("seasonNumber", ev.seasonNumber != null ? ev.seasonNumber : 0);
            item.put("episodeNumber", ev.episodeNumber != null ? ev.episodeNumber : 0);
            item.put("releaseYear", 0);
            item.put("mediaType", "external");
            item.put("sourceType", ev.sourceType);
            Long entryId = externalVideoEntryMap != null ? externalVideoEntryMap.get(ev.id) : null;
            item.put("inCollection", entryId != null);
            item.put("entryId", entryId);

            boolean isEpisode = ev.entryType == ExistingVideo.EPISODE;
            boolean hasSeries = ev.seriesTitle != null && !ev.seriesTitle.isBlank();

            if (isEpisode && hasSeries) {
                seriesEps.computeIfAbsent(ev.seriesTitle, k -> new ArrayList<>()).add(item);
            } else {
                movies.add(item);
            }
        }

        // Build series structure
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

    @Transactional
    public void updateCoverVideo(MediaCollection collection) {
        if (collection == null) return;
        if (collection.coverVideoId != null) return; // Manual override preserved
        List<CollectionEntry> entries = CollectionEntry.list("collection = ?1 order by orderIndex asc", collection);
        if (entries.isEmpty()) return;
        CollectionEntry first = entries.get(0);
        if (first.video != null) {
            collection.coverVideoId = first.video.id;
            em.merge(collection);
        }
        // ExternalVideo entries have no thumbnail system — leave coverVideoId null for gradient fallback
    }
}
