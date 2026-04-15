package Services;

import Models.MediaDirectory;
import Models.MediaDirectory.MediaType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.io.File;

@ApplicationScoped
public class MediaDirectoryService {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public MediaDirectory addDirectory(String path, MediaType type) {
        // Check if already exists
        MediaDirectory existing = MediaDirectory.find("path", path).firstResult();
        if (existing != null) {
            return existing;
        }
        
        MediaDirectory dir = new MediaDirectory();
        dir.path = path;
        dir.mediaType = type;
        dir.enabled = true;
        dir.dateAdded = java.time.Instant.now();
        dir.fileCount = 0L;
        dir.scanInProgress = false;
        
        em.persist(dir);
        return dir;
    }

    @Transactional
    public void removeDirectory(Long id) {
        MediaDirectory dir = em.find(MediaDirectory.class, id);
        if (dir != null) {
            em.remove(dir);
        }
    }

    @Transactional
    public void removeDirectoryByPath(String path) {
        MediaDirectory dir = MediaDirectory.find("path", path).firstResult();
        if (dir != null) {
            em.remove(dir);
        }
    }

    public List<MediaDirectory> listAll() {
        return MediaDirectory.listAll();
    }

    public List<MediaDirectory> listByType(MediaType type) {
        return MediaDirectory.findByType(type);
    }

    public List<MediaDirectory> listEnabledByType(MediaType type) {
        return MediaDirectory.findEnabledByType(type);
    }

    public MediaDirectory findById(Long id) {
        return MediaDirectory.findById(id);
    }

    public Optional<MediaDirectory> findByPath(String path) {
        return Optional.ofNullable(MediaDirectory.findByPath(path));
    }

    @Transactional
    public MediaDirectory updateEnabled(Long id, boolean enabled) {
        MediaDirectory dir = MediaDirectory.findById(id);
        if (dir != null) {
            dir.enabled = enabled;
            em.merge(dir);
        }
        return dir;
    }

    @Transactional
    public void updateScanStatus(Long id, boolean inProgress) {
        MediaDirectory dir = MediaDirectory.findById(id);
        if (dir != null) {
            dir.scanInProgress = inProgress;
            if (inProgress) {
                dir.lastScanStart = java.time.Instant.now();
            }
            em.merge(dir);
        }
    }

    @Transactional
    public void updateScanResult(Long id, Long fileCount, int durationSeconds) {
        MediaDirectory dir = MediaDirectory.findById(id);
        if (dir != null) {
            dir.fileCount = fileCount;
            dir.lastScan = java.time.Instant.now();
            dir.scanDurationSeconds = durationSeconds;
            dir.scanInProgress = false;
            dir.lastScanStart = null;
            em.merge(dir);
        }
    }

    public boolean validatePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File folder = new File(path);
        return folder.exists() && folder.isDirectory();
    }

    public List<MediaDirectory> getMusicDirectories() {
        return listByType(MediaType.MUSIC);
    }

    public List<MediaDirectory> getVideoDirectories() {
        return listByType(MediaType.VIDEO);
    }

    public List<MediaDirectory> getEnabledMusicDirectories() {
        return listEnabledByType(MediaType.MUSIC);
    }

    public List<MediaDirectory> getEnabledVideoDirectories() {
        return listEnabledByType(MediaType.VIDEO);
    }

    @Transactional
    public MediaDirectory addMusicDirectory(String path) {
        return addDirectory(path, MediaType.MUSIC);
    }

    @Transactional
    public MediaDirectory addVideoDirectory(String path) {
        return addDirectory(path, MediaType.VIDEO);
    }

    @Transactional
    public void removeMusicDirectory(String path) {
        removeDirectoryByPath(path);
    }

    @Transactional
    public void removeVideoDirectory(String path) {
        removeDirectoryByPath(path);
    }

    // Migration helper - create directories from legacy settings
    @Transactional
    public void migrateFromSettings(String musicPath, String videoPath) {
        if (musicPath != null && !musicPath.isBlank() && validatePath(musicPath)) {
            addMusicDirectory(musicPath);
        }
        if (videoPath != null && !videoPath.isBlank() && validatePath(videoPath)) {
            addVideoDirectory(videoPath);
        }
    }
}