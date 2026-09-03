package Controllers;

import API.WS.VideoSocket;
import Models.Settings.Profile;
import Models.Video.ProfileSessionState;
import Models.Settings.Settings;
import Models.Video.Video;
import Models.Video.VideoHistory;
import Models.Video.VideoState;
import Services.ProfileSessionStateService;
import Services.SettingsService;
import Services.VideoHistoryService;
import Services.VideoService;
import Services.VideoStateService;
import Services.CollectionWatchProgressService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoController {

    private volatile Long activePlayingProfileId;

    @Inject VideoQueueController videoQueueController;
    @Inject SettingsController currentSettings;
    @Inject VideoService videoService;
    @Inject VideoHistoryService videoHistoryService;
    @Inject VideoStateService videoStateService;
    @Inject ProfileSessionStateService profileSessionStateService;
    @Inject CollectionWatchProgressService collectionWatchProgressService;
    @Inject VideoSocket ws;

    private ScheduledExecutorService scheduler;
    // Per-profile playback timers keyed by profileId, mirroring PlaybackController
    // (music). Before this, a single global timer + activePlayingProfileId meant
    // only ONE profile could ever be "playing" at a time — the second profile's
    // WS reports were written into the first's state row or dropped entirely.
    private final Map<Long, ScheduledFuture<?>> playbackTasks = new ConcurrentHashMap<>();
    private static final long PLAYBACK_UPDATE_INTERVAL_MS = 300;

    /** A client report arriving within this window after a command, whose position
     *  diverges beyond the tolerance, is stale — the commanding client has already
     *  received the new position via broadcast. */
    static final long COMMAND_STALE_WINDOW_MS = 2000;
    static final double COMMAND_STALE_TOLERANCE = 1.5;

    private static final Logger LOGGER = Logger.getLogger(VideoController.class.getName());

    public VideoController() {}

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void shutdownScheduler() {
        playbackTasks.values().forEach(task -> task.cancel(true));
        if (scheduler != null) scheduler.shutdownNow();
    }

    private synchronized void startPlaybackTimer(Long profileId) {
        if (profileId == null) return;
        ScheduledFuture<?> existing = playbackTasks.get(profileId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> processPlaybackTick(profileId), 0, PLAYBACK_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        playbackTasks.put(profileId, task);
    }

    /** Window after a client report during which the tick mirrors the client's truth
     *  instead of advancing the phantom clock, so the server never overrides a local action. */
    private static final long CLIENT_REPORT_MIRROR_WINDOW_MS = 1500;
    private final Map<Long, Long> lastClientReportAt = new ConcurrentHashMap<>();

    // No @Transactional here: this runs on a plain scheduler thread (startPlaybackTimer).
    // A JTA tx on it would enlist BOTH persistence units (Profile via default PU, session
    // state via video PU) and nest a REQUIRES_NEW suspend/resume inside - the same overlap
    // that produced "Enlisted connection used without active transaction" on WS threads
    // (see reportClientState note). Each service call below opens its own short single-PU tx.
    protected synchronized void processPlaybackTick(Long profileId) {
        if (profileId == null) return;

        Profile playingProfile = Profile.findById(profileId);
        if (playingProfile == null || playingProfile.userId == null) return;

        SettingsService.setCurrentUserId(playingProfile.userId);
        try {
            ProfileSessionState st = getState(profileId);
            if (st.playing && st.currentVideoId != null) {
                /* A client just reported its own truth (seek/play/pause): mirror it to every
                   session WITHOUT advancing the phantom clock, so the server can never
                   override what the user just did on the player. The phantom clock resumes
                   only after the client has been silent for the full mirror window. */
                if (System.currentTimeMillis() - lastClientReportAt.getOrDefault(profileId, 0L) < CLIENT_REPORT_MIRROR_WINDOW_MS) {
                    updateState(st, true);
                } else {
                    double newTime = st.currentTime + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
                    Video currentVideo = findVideo(st.currentVideoId);
                    double duration = currentVideo != null && currentVideo.duration != null ? currentVideo.duration / 1000.0 : 0;
                    if (newTime >= duration && duration > 0) {
                        handleVideoEnded(profileId);
                    } else {
                        st.currentTime = newTime;
                        updateState(st, true);
                    }
                }
            }
        } finally {
            SettingsService.clearCurrentUserId();
        }
    }

    private synchronized void stopPlaybackTimer(Long profileId) {
        if (profileId == null) return;
        ScheduledFuture<?> task = playbackTasks.remove(profileId);
        lastClientReportAt.remove(profileId);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    public synchronized ProfileSessionState getState() {
        ProfileSessionState state = profileSessionStateService.getOrCreate();
        if (state != null) return state;

        if (activePlayingProfileId != null) {
            state = profileSessionStateService.findByProfileId(activePlayingProfileId);
            if (state != null) return state;
        }

        return new ProfileSessionState();
    }

    /**
     * Profile-explicit state read for WebSocket threads, which carry NO HTTP user
     * context (VideoSocket dispatches via CompletableFuture.runAsync on the common
     * pool). Falls back to the context-based getState() when profileId is null so
     * REST callers keep their current behavior.
     */
    public synchronized ProfileSessionState getState(Long profileId) {
        if (profileId == null) return getState();
        ProfileSessionState state = profileSessionStateService.findByProfileId(profileId);
        if (state != null) return state;
        return new ProfileSessionState();
    }

    /** Returns true when a client-reported time is stale relative to a recent command. */
    public boolean isReportStale(ProfileSessionState st, double reportedTime) {
        return st.lastUpdateTime > 0
            && System.currentTimeMillis() - st.lastUpdateTime < COMMAND_STALE_WINDOW_MS
            && Math.abs(reportedTime - st.currentTime) > COMMAND_STALE_TOLERANCE;
    }

    public synchronized void updateState(ProfileSessionState newState, boolean shouldBroadcast) {
        if (newState.currentVideoId != null) {
            Video currentVideo = videoService.find(newState.currentVideoId);
            if (currentVideo != null) {
                newState.currentTime = newState.currentTime;
            }
        }

        profileSessionStateService.save(newState);

        if (newState.playing && newState.profileId != null) {
            activePlayingProfileId = newState.profileId;
        } else if (!newState.playing && newState.profileId != null && activePlayingProfileId != null
                && activePlayingProfileId.equals(newState.profileId)) {
            activePlayingProfileId = null;
        }

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll(newState);
        }
    }

    public synchronized void reportClientState(Long profileId, Long videoId, boolean playing, double currentTime) {
        if (videoId == null) return;
        ProfileSessionState st;
        // Pause must land on the row the 300ms tick broadcasts, or the timer never stops and keeps force-playing.
        // Reads go through the service: a @Transactional here would open a JTA tx on the WebSocket
        // common-pool thread (VideoSocket dispatches via CompletableFuture.runAsync), enlist BOTH
        // persistence units, and hold the tx while this synchronized body waits on the monitor -
        // concurrent WS messages then overlap transactions and Agroal rolls back a stale connection
        // ("Enlisted connection used without active transaction"). Each service call is a short
        // single-PU transaction instead.
        // Cross-profile: trust the SENDER's profileId first. Checking activePlayingProfileId
        // first was the bug — B's reports landed in A's row (same video) or were dropped.
        if (profileId != null) {
            st = profileSessionStateService.findByProfileId(profileId);
            if (st == null) st = getState(profileId);
        } else if (activePlayingProfileId != null) {
            st = profileSessionStateService.findByProfileId(activePlayingProfileId);
            if (st == null) st = getState();
        } else {
            st = getState();
        }
        if (st == null) return;
        // B2 fix: stamp profileId on fresh rows so broadcastAll routes and downstream
        // getState(profileId) lookups find this row.
        if (st.profileId == null && profileId != null) st.profileId = profileId;
        // T1 lock: drop reports from a stale player (videoId != commanded currentVideoId); adopt when null
        if (st.currentVideoId != null && !st.currentVideoId.equals(videoId)) {
            return; // DROP: do not write state or timers, do not broadcast
        }
        // Stale-report guard: a report arriving within COMMAND_STALE_WINDOW_MS after
        // a command whose position diverges beyond the tolerance is from a stale player.
        // A PAUSE (playing==false) report is NEVER dropped — it must always stop the
        // phantom-clock timer, or the server keeps broadcasting playing=true and
        // force-plays the video the user just paused.
        if (playing && isReportStale(st, currentTime)) {
            return; // DROP: the commanding client already has the new position via broadcast
        }
        lastClientReportAt.put(profileId != null ? profileId : activePlayingProfileId, System.currentTimeMillis());
        st.currentVideoId = videoId;
        st.playing = playing;
        st.currentTime = currentTime;
        Long timerProfile = st.profileId != null ? st.profileId : profileId;
        if (playing) startPlaybackTimer(timerProfile); else stopPlaybackTimer(timerProfile);
        updateState(st, false);
    }

    public synchronized void selectVideo(Long id) {
        selectVideo(id, null);
    }

    public synchronized void selectVideo(Long id, Double startTime) {
        selectVideo(id, startTime, null);
    }

    public synchronized void selectVideo(Long id, Double startTime, Long profileId) {
        ProfileSessionState st = getState(profileId);
        // Inline getCurrentVideo using st's own fields to avoid ThreadLocal mismatch
        Video current = null;
        if (st.currentVideoId != null) {
            current = findVideo(st.currentVideoId);
        } else if (st.cue != null && !st.cue.isEmpty()) {
            current = findVideo(st.cue.get(0));
        }

        if (current != null && current.id.equals(id)) {
            st.playing = !st.playing;
            if (st.playing) startPlaybackTimer(st.profileId);
            else stopPlaybackTimer(st.profileId);
        } else {
            st.currentVideoId = id;
            Video newVideo = findVideo(id);
            if (newVideo != null) {
                // Record history when a new video is selected
                videoHistoryService.addFromVideoId(id, st.profileId != null ? st.profileId : profileId);

                // Resume (at start) from the per-profile saved position unless an
                // explicit start time was provided. the WS phantom re-seek that
                // motivated "always start at 0:00" (312d035) is prevented by the
                // player-side drift-yank guard, so every playback path — initial
                // fragment, local select, WS/remote swap — agrees on the position.
                st.currentTime = (startTime != null && startTime > 0) ? startTime : videoService.getResumeTime(newVideo);
                
                // Include audio preferences for frontend to restore
                st.preferredAudioLanguage = newVideo.preferredAudioLanguage;
                st.defaultAudioTrackId = newVideo.defaultAudioTrackId;
            } else {
                st.currentTime = (startTime != null && startTime > 0) ? startTime : 0;
            }
            st.playing = true;
            addVideoToCueIfNotPresent(st, id);
            if (st.cue != null) {
                st.cueIndex = st.cue.indexOf(id);
            }
            startPlaybackTimer(st.profileId);
        }
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
    }

    private void addVideoToCueIfNotPresent(ProfileSessionState st, Long videoId) {
        if (st.cue == null || !st.cue.contains(videoId)) {
            videoQueueController.addToQueue(st, List.of(videoId), false);
        }
    }

    private synchronized void stopPlayback(Long profileId) {
        ProfileSessionState st = getState(profileId);
        videoQueueController.clear(st);
        st.collectionId = null;
        stopPlaybackTimer(st.profileId != null ? st.profileId : profileId);
        st.playing = false;
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
        activePlayingProfileId = null;
    }
    
    private synchronized void advanceVideo(boolean forward, boolean fromVideoEnd, Long profileId) {
        ProfileSessionState st = getState(profileId);

        if (st.currentVideoId != null) {
            videoHistoryService.addFromVideoId(st.currentVideoId, st.profileId != null ? st.profileId : profileId);
        }

        if (st.cue == null || st.cue.isEmpty()) {
            if (st.collectionId != null) {
                collectionWatchProgressService.markCompleted(st.collectionId);
                st.collectionId = null;
            }
            stopPlayback(profileId);
            return;
        }

        Long nextVideoId = videoQueueController.advance(st, forward);

        if (nextVideoId == null) {
            if (st.collectionId != null) {
                collectionWatchProgressService.markCompleted(st.collectionId);
                st.collectionId = null;
            }
            stopPlayback(profileId);
            return;
        }

        if (st.collectionId != null) {
            collectionWatchProgressService.updateProgress(
                st.collectionId, nextVideoId, st.cueIndex,
                st.cue != null ? st.cue.size() : 0, st.cueIndex
            );
        }

        st.currentVideoId = nextVideoId;
        st.currentTime = 0;
        st.playing = true;
        startPlaybackTimer(st.profileId != null ? st.profileId : profileId);
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
    }
    
    public synchronized void next() {
        advanceVideo(true, false, null);
    }

    public synchronized void next(Long profileId) {
        advanceVideo(true, false, profileId);
    }

    public synchronized void previous() {
        previous(null);
    }

    public synchronized void previous(Long profileId) {
        ProfileSessionState st = getState(profileId);
        if (st.currentTime > 3) {
            st.currentTime = 0;
            st.lastUpdateTime = System.currentTimeMillis();
            updateState(st, true);
            return;
        }
        advanceVideo(false, false, profileId);
    }

    public synchronized void handleVideoEnded() {
        handleVideoEnded(null);
    }

    public synchronized void handleVideoEnded(Long profileId) {
        ProfileSessionState st = getState(profileId);
        stopPlaybackTimer(st.profileId != null ? st.profileId : profileId);
        st.playing = false;
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);

        if (st.collectionId != null && st.cue != null && st.cueIndex + 1 < st.cue.size()) {
            advanceVideo(true, true, profileId);
        } else if (st.collectionId != null && st.cue != null && st.cueIndex + 1 >= st.cue.size()) {
            collectionWatchProgressService.markCompleted(st.collectionId);
            st.collectionId = null;
            st.lastUpdateTime = System.currentTimeMillis();
            updateState(st, false);
        }
    }

    public synchronized void togglePlay() {
        togglePlay(null);
    }

    public synchronized void togglePlay(Long profileId) {
        ProfileSessionState state = getState(profileId);
        videoQueueController.togglePlay(state);
        Long timerProfile = state.profileId != null ? state.profileId : profileId;
        if (state.playing) startPlaybackTimer(timerProfile);
        else stopPlaybackTimer(timerProfile);
        state.lastUpdateTime = System.currentTimeMillis();
        updateState(state, true);
    }

    /** Stops the phantom-clock timer and persists the resume position once no video
     *  session for a profile remains. Before this, a closed tab left the 300ms timer
     *  running (nothing stopped it), so the clock kept inflating st.currentTime and
     *  corrupted the saved resume point (movies resumed 30s-1min ahead). */
    public synchronized void onClientDisconnect(Long profileId) {
        if (profileId == null) return;
        ProfileSessionState st = profileSessionStateService.findByProfileId(profileId);
        if (st == null) return;
        stopPlaybackTimer(profileId);
        lastClientReportAt.remove(profileId);
        st.lastUpdateTime = System.currentTimeMillis();
        if (st.currentVideoId != null && st.currentTime > 0) {
            Video vid = findVideo(st.currentVideoId);
            if (vid != null) {
                videoStateService.updateProgress(vid, st.currentTime, profileId);
            }
        }
    }
    
    public List<Video> getVideos() {
        return Models.Video.Video.listAll();
    }

    public List<Video> getVideos(int page, int limit) {
        return Models.Video.Video.findAll().page(page - 1, limit).list();
    }

    public Video findVideo(Long id) {
        return Models.Video.Video.findById(id);
    }
    
    public synchronized void changeVolume(float level) {
        changeVolume(level, null);
    }

    public synchronized void changeVolume(float level, Long profileId) {
        ProfileSessionState st = getState(profileId);
        videoQueueController.changeVolume(st, level);
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
    }

    public synchronized void setSeconds(double seconds) {
        setSeconds(seconds, null);
    }

    public synchronized void setSeconds(double seconds, Long profileId) {
        ProfileSessionState st = getState(profileId);
        videoQueueController.setSeconds(st, seconds);
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
    }

    public synchronized Video getCurrentVideo() {
        ProfileSessionState st = getState();
        Long currentId = st.currentVideoId;
        if (currentId != null) {
            return findVideo(currentId);
        }
        List<Long> cue = st.cue;
        if (cue != null && !cue.isEmpty()) {
            return findVideo(cue.get(0));
        }
        List<Video> allVideos = getVideos();
        return allVideos.isEmpty() ? null : allVideos.get(0);
    }
    
    public synchronized void addToQueue(List<Long> videoIds, boolean playNext) {
        if (videoIds == null || videoIds.isEmpty()) return;
        ProfileSessionState st = getState();
        videoQueueController.addToQueue(st, videoIds, playNext);
        updateState(st, true);
    }

    public synchronized void removeFromQueue(Long videoId) {
        ProfileSessionState st = getState();
        videoQueueController.removeFromQueue(st, videoId);
        updateState(st, true);
    }
    
    public synchronized void clearQueue() {
        ProfileSessionState st = getState();
        videoQueueController.clear(st);
        updateState(st, true);
    }
    
    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        ProfileSessionState st = getState();
        videoQueueController.moveInQueue(st, fromIndex, toIndex);
        updateState(st, true);
    }

    public synchronized void skipToQueueIndex(int index) {
        ProfileSessionState st = getState();
        videoQueueController.skipToQueueIndex(st, index);
        updateState(st, true);
    }

    public record PaginatedQueue(List<Video> videos, int totalSize) {}

    public PaginatedQueue getQueuePage(int page, int limit) {
        ProfileSessionState st = getState();
        List<Long> cueIds = st.cue;
        if (cueIds == null || cueIds.isEmpty()) {
            return new PaginatedQueue(new ArrayList<>(), 0);
        }

        int totalSize = cueIds.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, totalSize);

        if (fromIndex >= totalSize) {
            return new PaginatedQueue(new ArrayList<>(), totalSize);
        }

        List<Long> pageOfIds = cueIds.subList(fromIndex, toIndex);
        List<Video> videos = new ArrayList<>();
        for (Long id : pageOfIds) {
            Video video = Models.Video.Video.findById(id);
            if (video != null) {
                videos.add(video);
            }
        }

        return new PaginatedQueue(videos, totalSize);
    }
    
    // The following methods from the old controller have been removed or need rethinking
    // as they were tightly coupled to the old Video entity structure or services
    // - getPreviousVideo(), getNextVideo() -> This logic is inside advanceVideo/VideoQueueController
    // - getHistory() -> VideoHistoryService needs to be refactored to work with MediaFile IDs.
    // - All settings-related methods like getSettings() are kept via SettingsController injection.
    
    // A simplified history mechanism would be needed. The VideoHistoryService must be updated.
    public List<VideoHistory> getHistory() {
        return videoHistoryService.getHistory(1, 100);
    }
    
    @PreDestroy
    public void shutdown() {
        playbackTasks.values().forEach(task -> task.cancel(true));
        if (scheduler != null && !scheduler.isShutdown()) {
            LOGGER.info("Shutting down VideoController scheduler");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warning("VideoController scheduler did not terminate gracefully, forcing shutdown");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupted while waiting for VideoController scheduler to terminate");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
