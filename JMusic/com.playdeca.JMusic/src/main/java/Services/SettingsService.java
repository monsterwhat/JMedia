package Services;

import Models.Profile;
import Models.Settings;
import Models.SettingsLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.io.File;

@ApplicationScoped
public class SettingsService {

    @PersistenceContext
    private EntityManager em;

    private static final int LOG_FLUSH_THRESHOLD = 20;
    private static final long LOG_FLUSH_INTERVAL_MS = 5000;

    private final List<String> logBuffer = new ArrayList<>();
    private long lastFlushTime = System.currentTimeMillis();

    @Transactional
    public void save(Settings settings) {
        if (settings.id == null || em.find(Settings.class, settings.id) == null) {
            em.persist(settings);
        } else {
            em.merge(settings);
        }
    }

    @Transactional
    public void delete(Settings settings) {
        if (settings != null) {
            Settings managed = em.contains(settings) ? settings : em.merge(settings);
            em.remove(managed);
        }
    }

    @Transactional
    public Settings find(Long id) {
        return em.find(Settings.class, id);
    }

    @Transactional
    public List<Settings> findAll() {
        return em.createQuery("SELECT s FROM Settings s", Settings.class)
                .getResultList();
    }

    // ---------------- LOGS ----------------
    public void addLog(Settings settings, String message) {
        if (settings == null || message == null || message.isBlank()) {
            return;
        }

        synchronized (logBuffer) {
            logBuffer.add(message);
            long now = System.currentTimeMillis();
            if (logBuffer.size() >= LOG_FLUSH_THRESHOLD || (now - lastFlushTime) >= LOG_FLUSH_INTERVAL_MS) {
                flushLogs(settings);
                lastFlushTime = now;
            }
        }
    }

    @Transactional
    public void flushLogs(Settings settings) {
        synchronized (logBuffer) {
            if (logBuffer.isEmpty() || settings == null) {
                return;
            }

            List<SettingsLog> logs = settings.getLogs();
            if (logs == null) {
                logs = new ArrayList<>();
                settings.setLogs(logs);
            }

            for (String msg : logBuffer) {
                SettingsLog log = new SettingsLog();
                log.setMessage(msg);
                logs.add(log);
            }

            em.merge(settings); // persist the updated logs
            logBuffer.clear();
        }
    }

    @Transactional
    public void clearLogs(Settings settings) {
        if (settings != null) {
            List<SettingsLog> logs = settings.getLogs();
            if (logs != null) {
                for (SettingsLog log : logs) {
                    em.remove(em.contains(log) ? log : em.merge(log));
                }
                logs.clear();
            }
            em.merge(settings);
        }
    }

    @Transactional
    public void setLibraryPath(Settings settings, String path) {
        if (settings != null) {
            settings.setLibraryPath(path);
            em.merge(settings);
        }
    }

    // Non-transactional: safe to call anywhere
    @Transactional
    public Settings getSettingsOrNull() {
        List<Settings> all = findAll(); // just read, no transaction required
        if (all.isEmpty()) {
            return null; // indicate that nothing exists
        }

        Settings settings = all.get(0);

        // initialize default fields if missing (no DB write yet)
        if (settings.getLibraryPath() == null || settings.getLibraryPath().isBlank()) {
            settings.setLibraryPath(System.getProperty("user.home") + File.separator + "Music");
        }
        if (settings.getVideoLibraryPath() == null || settings.getVideoLibraryPath().isBlank()) {
            settings.setVideoLibraryPath(System.getProperty("user.home") + File.separator + "Videos");
        }
        if (settings.getLogs() == null) {
            settings.setLogs(new ArrayList<>());
        }

        return settings;
    }

// Transactional helper: creates and persists a new Settings
    @Transactional
    protected Settings createAndSaveDefaultSettings() {
        Settings settings = new Settings();
        settings.setLibraryPath(System.getProperty("user.home") + File.separator + "Music");
        settings.setVideoLibraryPath(System.getProperty("user.home") + File.separator + "Videos");
        settings.setLogs(new ArrayList<>());
        save(settings); // persist in DB
        return settings;
    }

// Public method combining both
    @Transactional
    public Settings getOrCreateSettings() {
        Settings settings = getSettingsOrNull();
        if (settings == null) {
            settings = createAndSaveDefaultSettings();
        }
        return settings;
    }

    // ------------------- GET ALL LOGS -------------------
    @Transactional
    public List<SettingsLog> getAllLogs() {
        return em.createQuery("SELECT l FROM SettingsLog l ORDER BY l.id ASC", SettingsLog.class)
                .getResultList();
    }

    @Transactional
    public List<SettingsLog> getLogs(Settings settings) {
        if (settings != null) {
            // ensure managed state
            Settings managed = em.contains(settings) ? settings : em.merge(settings);
            return managed.getLogs();
        }
        return List.of();
    }

    @Transactional
    public Profile getActiveProfile() {
        Settings settings = getOrCreateSettings();
        Long activeProfileId = settings.getActiveProfileId();
        if (activeProfileId == null) {
            Profile mainProfile = Profile.findMainProfile();
            if (mainProfile != null) {
                settings.setActiveProfileId(mainProfile.id);
                em.merge(settings);
                return mainProfile;
            }
            return null; // Should not happen if ProfileService onStart works
        }
        return em.find(Profile.class, activeProfileId);
    }

    @Transactional
    public void setActiveProfile(Profile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null.");
        }
        Settings settings = getOrCreateSettings();
        settings.setActiveProfileId(profile.id);
        em.merge(settings);
    }
}