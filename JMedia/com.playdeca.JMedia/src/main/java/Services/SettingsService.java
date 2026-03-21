package Services;

import Models.Profile;
import Models.Settings;
import Models.SettingsLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class SettingsService {

    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());
    
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    @PersistenceContext
    private EntityManager em;

    private static final int LOG_FLUSH_THRESHOLD = 20;
    private static final long LOG_FLUSH_INTERVAL_MS = 5000;
    private static final long LOG_CLEAR_INTERVAL_HOURS = 48;

    private final List<String> logBuffer = new ArrayList<>();
    private long lastFlushTime = System.currentTimeMillis();
    private ScheduledExecutorService scheduler;
    
    // Cache the settings ID to avoid repeated findAll queries
    private Long cachedSettingsId = null;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::clearOldLogs, LOG_CLEAR_INTERVAL_HOURS, LOG_CLEAR_INTERVAL_HOURS, TimeUnit.HOURS);
        LOGGER.info("Log cleanup scheduled to run every " + LOG_CLEAR_INTERVAL_HOURS + " hours");
    }

    @Transactional
    public void save(Settings settings) {
        if (settings.id == null || em.find(Settings.class, settings.id) == null) {
            em.persist(settings);
            cachedSettingsId = settings.id;
        } else {
            em.merge(settings);
        }
    }

    @Transactional
    public void delete(Settings settings) {
        if (settings != null) {
            Settings managed = em.contains(settings) ? settings : em.merge(settings);
            em.remove(managed);
            if (managed.id.equals(cachedSettingsId)) {
                cachedSettingsId = null;
            }
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
        if (message == null || message.isBlank()) {
            return;
        }

        synchronized (logBuffer) {
            logBuffer.add(message);
            long now = System.currentTimeMillis();
            // Flush if buffer is full or enough time has passed
            if (logBuffer.size() >= LOG_FLUSH_THRESHOLD || (now - lastFlushTime) >= LOG_FLUSH_INTERVAL_MS) {
                flushLogs(settings != null ? settings.id : cachedSettingsId);
                lastFlushTime = now;
            }
        }
    }

    @Transactional
    public void flushLogs(Long settingsId) {
        synchronized (logBuffer) {
            if (logBuffer.isEmpty()) {
                return;
            }

            // If we don't have a settingsId yet, try to find one
            if (settingsId == null) {
                Settings settings = getSettingsOrNull();
                if (settings != null) {
                    settingsId = settings.id;
                }
            }

            if (settingsId == null) {
                return;
            }

            for (String msg : logBuffer) {
                SettingsLog log = new SettingsLog();
                log.setMessage(msg);
                log.settingsId = settingsId;
                em.persist(log);
            }

            logBuffer.clear();
        }
    }

    @Transactional
    public void clearLogs(Settings settings) {
        Long settingsId = settings != null ? settings.id : cachedSettingsId;
        if (settingsId != null) {
            em.createQuery("DELETE FROM SettingsLog l WHERE l.settingsId = :sid")
              .setParameter("sid", settingsId)
              .executeUpdate();
        }
    }

    @Transactional
    public void clearOldLogs() {
        try {
            Long settingsId = cachedSettingsId;
            if (settingsId == null) {
                Settings s = getSettingsOrNull();
                if (s != null) settingsId = s.id;
            }
            
            if (settingsId != null) {
                int deleted = em.createQuery("DELETE FROM SettingsLog l WHERE l.settingsId = :sid")
                  .setParameter("sid", settingsId)
                  .executeUpdate();
                LOGGER.info("Cleared " + deleted + " old log entries");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to clear old logs: " + e.getMessage());
        }
    }

    @Transactional
    public void setLibraryPath(Settings settings, String path) {
        if (settings != null) {
            Settings managed = em.find(Settings.class, settings.id);
            if (managed != null) {
                managed.setLibraryPath(path);
                em.merge(managed);
            }
        }
    }

    // Non-transactional: safe to call anywhere
    @Transactional
    public Settings getSettingsOrNull() {
        if (cachedSettingsId != null) {
            Settings settings = em.find(Settings.class, cachedSettingsId);
            if (settings != null) {
                return initializeDefaultFields(settings);
            }
        }

        List<Settings> all = findAll();
        if (all.isEmpty()) {
            return null;
        }

        Settings settings = all.get(0);
        cachedSettingsId = settings.id;

        return initializeDefaultFields(settings);
    }

    private Settings initializeDefaultFields(Settings settings) {
        // initialize default fields if missing (no DB write yet)
        if (settings.getLibraryPath() == null || settings.getLibraryPath().isBlank()) {
            settings.setLibraryPath(System.getProperty("user.home") + File.separator + "Music");
        }
        if (settings.getVideoLibraryPath() == null || settings.getVideoLibraryPath().isBlank()) {
            settings.setVideoLibraryPath(System.getProperty("user.home") + File.separator + "Videos");
        }

        return settings;
    }

// Transactional helper: creates and persists a new Settings
    @Transactional
    protected Settings createAndSaveDefaultSettings() {
        Settings settings = new Settings();
        settings.setLibraryPath(System.getProperty("user.home") + File.separator + "Music");
        settings.setVideoLibraryPath(System.getProperty("user.home") + File.separator + "Videos");
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
        return em.createQuery("SELECT l FROM SettingsLog l ORDER BY l.timestamp ASC, l.id ASC", SettingsLog.class)
                .getResultList();
    }

    @Transactional
    public List<SettingsLog> getLogs(Settings settings) {
        Long settingsId = settings != null ? settings.id : cachedSettingsId;
        if (settingsId != null) {
            return em.createQuery("SELECT l FROM SettingsLog l WHERE l.settingsId = :sid ORDER BY l.timestamp ASC, l.id ASC", SettingsLog.class)
                    .setParameter("sid", settingsId)
                    .getResultList();
        }
        return List.of();
    }
    
    @Transactional
    public List<String> getRecentLogMessages(int limit) {
        Long settingsId = cachedSettingsId;
        if (settingsId == null) {
            Settings s = getSettingsOrNull();
            if (s != null) settingsId = s.id;
        }
        
        if (settingsId != null) {
            return em.createQuery("SELECT l.message FROM SettingsLog l WHERE l.settingsId = :sid ORDER BY l.timestamp DESC, l.id DESC", String.class)
                    .setParameter("sid", settingsId)
                    .setMaxResults(limit)
                    .getResultList()
                    .stream()
                    .collect(Collectors.collectingAndThen(Collectors.toList(), list -> {
                        java.util.Collections.reverse(list);
                        return list;
                    }));
        }
        return List.of();
    }

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }
    
    public static Long getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }
    
    public static void clearCurrentUserId() {
        CURRENT_USER_ID.remove();
    }

    @Transactional
    public Profile getActiveProfile() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return null;
        }
        return getActiveProfile(userId);
    }

    @Transactional
    public Profile getActiveProfile(Long userId) {
        if (userId == null) {
            return getActiveProfile();
        }
        
        Profile userMainProfile = Profile.findMainProfileByUser(userId);
        if (userMainProfile == null) {
            return null;
        }
        
        Settings settings = getOrCreateSettings();
        Long activeProfileId = settings.getActiveProfileId();
        
        if (activeProfileId == null) {
            return userMainProfile;
        }
        
        Profile activeProfile = em.find(Profile.class, activeProfileId);
        
        if (activeProfile != null && activeProfile.userId != null && activeProfile.userId.equals(userId)) {
            return activeProfile;
        }
        
        return userMainProfile;
    }

    @Transactional
    public Profile getActiveProfileFromHeaders(jakarta.ws.rs.core.HttpHeaders headers) {
        if (headers == null) {
            return getActiveProfile();
        }
        
        String sessionId = getSessionId(headers);
        if (sessionId == null) {
            return getActiveProfile();
        }
        
        Models.Session session = Models.Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return getActiveProfile();
        }
        
        try {
            Long userId = Long.parseLong(session.userId);
            return getActiveProfile(userId);
        } catch (NumberFormatException e) {
            return getActiveProfile();
        }
    }

    private String getSessionId(jakarta.ws.rs.core.HttpHeaders headers) {
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            return headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        return null;
    }

    @Transactional
    public void setActiveProfile(Profile profile, Long userId) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null.");
        }
        
        if (userId != null) {
            if (profile.userId != null && !profile.userId.equals(userId)) {
                throw new IllegalArgumentException("Profile does not belong to user.");
            }
        }
        
        Settings settings = getOrCreateSettings();
        settings.setActiveProfileId(profile.id);
        em.merge(settings);
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @PreDestroy
    public void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            LOGGER.info("Shutting down SettingsService scheduler");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                     System.err.println("SettingsService scheduler did not terminate gracefully, forcing shutdown");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupted while waiting for SettingsService scheduler to terminate");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}