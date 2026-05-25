package Services;

import Models.CollectionWatchProgress;
import Models.MediaCollection;
import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class CollectionWatchProgressService {

    @Inject
    SettingsService settingsService;

    @Inject
    EntityManager em;

    @Transactional
    public CollectionWatchProgress getOrCreate(MediaCollection collection) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || collection == null) return null;

        CollectionWatchProgress p = CollectionWatchProgress.find(
            "profile = ?1 AND collection = ?2", activeProfile, collection
        ).firstResult();
        if (p == null) {
            p = new CollectionWatchProgress();
            p.profile = activeProfile;
            p.collection = collection;
            p.persist();
        }
        return p;
    }

    public CollectionWatchProgress get(MediaCollection collection) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || collection == null) return null;
        return CollectionWatchProgress.find(
            "profile = ?1 AND collection = ?2", activeProfile, collection
        ).firstResult();
    }

    @Transactional
    public void updateProgress(Long collectionId, Long videoId, int entryIndex, int totalEntries, int completedEntries) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return;
        CollectionWatchProgress p = getOrCreate(c);
        if (p == null) return;
        p.lastVideoId = videoId;
        p.lastEntryIndex = entryIndex;
        p.totalEntries = totalEntries;
        p.completedEntries = completedEntries;
        p.progress = totalEntries > 0 ? Math.min(1.0, (double) completedEntries / totalEntries) : 0.0;
        p.lastUpdated = LocalDateTime.now();
        em.merge(p);
    }

    public List<CollectionWatchProgress> getInProgress() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return List.of();
        return CollectionWatchProgress.list(
            "profile = ?1 AND progress > 0 AND progress < 1.0 ORDER BY lastUpdated DESC", activeProfile
        );
    }

    @Transactional
    public void markCompleted(Long collectionId) {
        MediaCollection c = MediaCollection.findById(collectionId);
        if (c == null) return;
        CollectionWatchProgress p = get(c);
        if (p == null) return;
        p.completedEntries = p.totalEntries;
        p.progress = 1.0;
        p.lastUpdated = LocalDateTime.now();
        em.merge(p);
    }

    @Transactional
    public void delete(MediaCollection collection) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || collection == null) return;
        CollectionWatchProgress.delete("profile = ?1 AND collection = ?2", activeProfile, collection);
    }

    @Transactional
    public void deleteForProfile(Profile profile) {
        if (profile != null) {
            CollectionWatchProgress.delete("profile = ?1", profile);
        }
    }
}
