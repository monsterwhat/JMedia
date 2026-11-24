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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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

    /*
     * The playing browser is the source of truth for position and play/pause.
     * The server used to run a 300 ms "phantom clock" per profile that advanced
     * currentTime whenever the player had been quiet for 1.5 s and broadcast it;
     * players then seeked to it ("drift correction"). Two clocks fought: forward
     * yanks, seek storms, inflated resume points, force-plays after a pause - each
     * patched with another guard. Now the server records what the player reports
     * (reportClientState) and relays it to the profile's other devices, and every
     * remote action reaches the player as an explicit command (sendCommand).
     */

    /** A client report arriving within this window after a command, whose position
     *  diverges beyond the tolerance, is stale — the commanding client has already
     *  received the new position via broadcast. */
    static final long COMMAND_STALE_WINDOW_MS = 2000;
    static final double COMMAND_STALE_TOLERANCE = 1.5;

    private static final Logger LOGGER = Logger.getLogger(VideoController.class.getName());

    public VideoController() {}

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
        st.currentVideoId = videoId;
        st.playing = playing;
        st.currentTime = currentTime;
        // Relay the player's own state to the profile's other devices (remote
        // views). Players ignore position/playing in state broadcasts, so this
        // can never move the player that sent it.
        updateState(st, true);
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

        boolean toggledSameVideo = current != null && current.id.equals(id);
        if (toggledSameVideo) {
            st.playing = !st.playing;
        } else {
            st.currentVideoId = id;
            Video newVideo = findVideo(id);
            if (newVideo != null) {
                // Record history when a new video is selected
                videoHistoryService.addFromVideoId(id, st.profileId != null ? st.profileId : profileId);

                // Resume from the per-profile saved position unless an explicit start
                // time was provided. An explicit 0 must be honored (fresh play from
                // the episode list) — treating 0 as "no start" made selectVideo
                // ignore playVideo's ?startTime=0 and resume the previous episode's
                // saved position instead of the clicked one. The WS phantom re-seek
                // is prevented by the player-side drift-yank guard.
                st.currentTime = (startTime != null) ? startTime : videoService.getResumeTime(newVideo);
                
                // Include audio preferences for frontend to restore
                st.preferredAudioLanguage = newVideo.preferredAudioLanguage;
                st.defaultAudioTrackId = newVideo.defaultAudioTrackId;
            } else {
                st.currentTime = (startTime != null) ? startTime : 0;
            }
            st.playing = true;
            addVideoToCueIfNotPresent(st, id);
            if (st.cue != null) {
                st.cueIndex = st.cue.indexOf(id);
            }
        }
        st.lastUpdateTime = System.currentTimeMillis();
        updateState(st, true);
        if (toggledSameVideo) {
            sendPlayingCommand(st, profileId);
        }
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
            sendSeekCommand(0, st, profileId);
            return;
        }
        advanceVideo(false, false, profileId);
    }

    public synchronized void handleVideoEnded() {
        handleVideoEnded(null);
    }

    public synchronized void handleVideoEnded(Long profileId) {
        ProfileSessionState st = getState(profileId);
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
        state.lastUpdateTime = System.currentTimeMillis();
        updateState(state, true);
        // The player applies play/pause only from commands, not from state.
        sendPlayingCommand(state, profileId);
    }

    /** Persists the resume position once no video session for a profile remains.
     *  st.currentTime is the player's last report (no server clock advances it). */
    public synchronized void onClientDisconnect(Long profileId) {
        if (profileId == null) return;
        ProfileSessionState st = profileSessionStateService.findByProfileId(profileId);
        if (st == null) return;
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
        sendSeekCommand(st.currentTime, st, profileId);
    }

    private Long commandProfile(ProfileSessionState st, Long profileId) {
        return st.profileId != null ? st.profileId : profileId;
    }

    /** Tells the profile's player to seek; a state broadcast no longer moves it. */
    private void sendSeekCommand(double seconds, ProfileSessionState st, Long profileId) {
        if (ws == null) return;
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("value", seconds);
        ws.broadcastCommand("seek", payload, commandProfile(st, profileId));
    }

    /** Tells the profile's player to play or pause (absolute, so it can never double-toggle). */
    private void sendPlayingCommand(ProfileSessionState st, Long profileId) {
        if (ws == null) return;
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("playing", st.playing);
        ws.broadcastCommand("set-playing", payload, commandProfile(st, profileId));
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
    }
