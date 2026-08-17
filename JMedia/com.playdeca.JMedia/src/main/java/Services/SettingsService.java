package Services;

import Models.Settings.Profile;
import Models.Settings.Settings;
import Models.Settings.SettingsLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
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
        if (settings.id == null || Settings.findById(settings.id) == null) {
            settings.persist();
            cachedSettingsId = settings.id;
        } else {
            settings.persistAndFlush();
        }
    }

    @Transactional
    public void delete(Settings settings) {
        if (settings != null) {
            Settings.deleteById(settings.id);
            if (settings.id.equals(cachedSettingsId)) {
                cachedSettingsId = null;
            }
        }
    }

    @Transactional
    public Settings find(Long id) {
        return Settings.findById(id);
    }

    @Transactional
    public List<Settings> findAll() {
        return Settings.findAll().list();
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
                log.persist();
            }

            logBuffer.clear();
        }
    }

    @Transactional
    public void clearLogs(Settings settings) {
        Long settingsId = settings != null ? settings.id : cachedSettingsId;
        if (settingsId != null) {
            SettingsLog.delete("settingsId = ?1", settingsId);
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
                SettingsLog.delete("settingsId = ?1", settingsId);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to clear old logs: " + e.getMessage());
        }
    }

    @Transactional
    public void setLibraryPath(Settings settings, String path) {
        if (settings != null) {
            Settings managed = Settings.findById(settings.id);
            if (managed != null) {
                managed.setLibraryPath(path);
                managed.persist();
            }
        }
    }

    // Non-transactional: safe to call anywhere
    @Transactional
    public Settings getSettingsOrNull() {
        if (cachedSettingsId != null) {
            Settings settings = Settings.findById(cachedSettingsId);
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

        if (settings.getTmdbEnabled() == null) settings.setTmdbEnabled(true);
        if (settings.getOmdbEnabled() == null) settings.setOmdbEnabled(true);
        if (settings.getImdbDevEnabled() == null) settings.setImdbDevEnabled(true);
        if (settings.getTvmazeEnabled() == null) settings.setTvmazeEnabled(true);
        if (settings.getIntroDbEnabled() == null) settings.setIntroDbEnabled(true);
        if (settings.getMusicBrainzEnabled() == null) settings.setMusicBrainzEnabled(true);
        if (settings.getAcousticBrainzEnabled() == null) settings.setAcousticBrainzEnabled(true);
        if (settings.getDeezerEnabled() == null) settings.setDeezerEnabled(true);
        if (settings.getTheAudioDbEnabled() == null) settings.setTheAudioDbEnabled(true);
        if (settings.getOpenSubtitlesEnabled() == null) settings.setOpenSubtitlesEnabled(true);

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
    @Transactional(Transactional.TxType.REQUIRES_NEW)
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
        return SettingsLog.findAll().list();
    }

    @Transactional
    public List<SettingsLog> getLogs(Settings settings) {
        Long settingsId = settings != null ? settings.id : cachedSettingsId;
        if (settingsId != null) {
            return SettingsLog.list("settingsId = ?1 ORDER BY timestamp ASC, id ASC", settingsId);
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
            return SettingsLog.list("settingsId = ?1 ORDER BY timestamp DESC, id DESC", settingsId)
                    .stream()
                    .limit(limit)
                    .map(e -> ((SettingsLog) e).getMessage())
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

    // NOT_SUPPORTED on purpose: settings-DB read called from inside video/music-DB
    // transactions; REQUIRED would fail JTA enlistment. Returns a detached Profile.
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Profile getActiveProfile() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return null;
        }
return getActiveProfile(userId);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Profile getActiveProfile(Long userId) {
        if (userId == null) {
            return getActiveProfile();
        }

        Profile userMainProfile = Profile.findMainProfileByUser(userId);
        if (userMainProfile == null) {
            return null;
        }

        Settings settings = getOrCreateSettings();
        Long activeProfileId = getActiveProfileIdForUser(settings, userId);

        if (activeProfileId == null) {
            return userMainProfile;
        }

        Profile activeProfile = Profile.findById(activeProfileId);

        if (activeProfile != null && activeProfile.userId != null && activeProfile.userId.equals(userId)) {
            return activeProfile;
        }

        return userMainProfile;
    }

    private Long getActiveProfileIdForUser(Settings settings, Long userId) {
        if (settings == null || userId == null) {
            return null;
        }
        String json = settings.getActiveProfileIdsJson();
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Long> profileMap = mapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Long>>() {});
            return profileMap.get(userId.toString());
        } catch (Exception e) {
            LOGGER.warning("Error parsing activeProfileIdsJson: " + e.getMessage());
            return null;
        }
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
        
        Models.Settings.Session session = Models.Settings.Session.findBySessionId(sessionId);
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
        setActiveProfileIdForUser(settings, userId, profile.id);
        settings.persist();
    }

    private void setActiveProfileIdForUser(Settings settings, Long userId, Long profileId) {
        if (settings == null || userId == null) {
            return;
        }
        String json = settings.getActiveProfileIdsJson();
        java.util.Map<String, Long> profileMap;
        try {
            if (json == null || json.isEmpty() || json.equals("{}")) {
                profileMap = new java.util.HashMap<>();
            } else {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                profileMap = mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Long>>() {});
            }
        } catch (Exception e) {
            LOGGER.warning("Error parsing activeProfileIdsJson: " + e.getMessage());
            profileMap = new java.util.HashMap<>();
        }
        profileMap.put(userId.toString(), profileId);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            settings.setActiveProfileIdsJson(mapper.writeValueAsString(profileMap));
        } catch (Exception e) {
            LOGGER.warning("Error serializing activeProfileIdsJson: " + e.getMessage());
        }
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
