package Controllers;

import API.WS.VideoSocket; // Use VideoSocket
import Models.Video;
import Models.VideoState; // Use VideoState
import Models.Settings;
import Models.VideoHistory;
import Services.VideoHistoryService; // Use VideoHistoryService
import Services.VideoService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoController {

    private VideoState memoryState;

    @Inject VideoPersistenceController videoPersistenceController; // Use VideoPersistenceController
    @Inject VideoQueueController videoQueueController;           // Use VideoQueueController
    @Inject SettingsController currentSettings;
    @Inject VideoService videoService;
    @Inject VideoHistoryService videoHistoryService;
    @Inject VideoSocket ws; // Use VideoSocket

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> playbackTask;
    private static final long PLAYBACK_UPDATE_INTERVAL_MS = 300; // Update every 300ms

    private static final Logger LOGGER = Logger.getLogger(VideoController.class.getName());

    public VideoController() {
        System.out.println("[VideoController] VideoController instance created");
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        memoryState = videoPersistenceController.loadState();
        if (memoryState == null) {
            memoryState = new VideoState();
        }

        // Ensure a valid initial state if cue is empty or currentVideoId is null
        if (memoryState.getCue().isEmpty()) {
            List<Video> allVideos = getVideos();
            if (!allVideos.isEmpty()) {
                memoryState.setCue(allVideos.stream().map(v -> v.id).collect(Collectors.toList()));
                memoryState.setCueIndex(0);
                memoryState.setCurrentVideoId(allVideos.get(0).id);
            }
        }

        if (memoryState.getCurrentVideoId() == null && !memoryState.getCue().isEmpty()) {
            memoryState.setCurrentVideoId(memoryState.getCue().get(0));
            memoryState.setCueIndex(0);
        }

        memoryState.setPlaying(false);

        System.out.println("[VideoController] Initial state loaded: " + safeSummary(memoryState));

        if (memoryState.isPlaying()) {
            startPlaybackTimer();
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        if (playbackTask != null) {
            playbackTask.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private synchronized void startPlaybackTimer() {
        if (playbackTask != null && !playbackTask.isDone()) {
            playbackTask.cancel(false); // Allow current task to complete if running
        }

        playbackTask = scheduler.scheduleAtFixedRate(() -> {
            processPlaybackTick();
        }, 0, PLAYBACK_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOGGER.info("Video playback timer started.");
    }

    @jakarta.transaction.Transactional // Ensure database operations run in a transaction
    protected void processPlaybackTick() {
        try {
            VideoState st = getState();
            if (st.isPlaying() && st.getCurrentVideoId() != null) {
                double newTime = st.getCurrentTime() + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
                if (newTime >= st.getDuration() && st.getDuration() > 0) {
                    // Video ended naturally, advance to next
                    handleVideoEnded();
                } else {
                    st.setCurrentTime(newTime);
                    updateState(st, true); // Broadcast updated time
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error in video playback timer task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private synchronized void stopPlaybackTimer() {
        if (playbackTask != null && !playbackTask.isDone()) {
            playbackTask.cancel(false); // Allow current task to complete if running
            LOGGER.info("Video playback timer stopped.");
        }
    }

    // -----------------------------
    // Playback state methods
    // -----------------------------
    public synchronized VideoState getState() {
        if (memoryState == null) {
            memoryState = videoPersistenceController.loadState();
            if (memoryState == null) {
                memoryState = new VideoState();
            }
        }
        return memoryState;
    }

    public synchronized void updateState(VideoState newState, boolean shouldBroadcast) {
        if (memoryState == null) {
            memoryState = videoPersistenceController.loadState();
        }

        if (newState.getCurrentVideoId() != null) {
            Video currentVideo = videoService.find(newState.getCurrentVideoId());
            if (currentVideo != null) {
                newState.setVideoTitle(currentVideo.getTitle());
                newState.setSeriesTitle(currentVideo.getSeriesTitle());
                newState.setEpisodeTitle(currentVideo.getEpisodeTitle());
                newState.setDuration(currentVideo.getDurationSeconds());
            } else {
                newState.setVideoTitle("Unknown Title");
                newState.setSeriesTitle(null);
                newState.setEpisodeTitle(null);
                newState.setDuration(0);
            }
        }

        if (newState.getCurrentVideoId() != null
                && newState.getCurrentVideoId().equals(memoryState.getCurrentVideoId())
                && newState.getCurrentTime() == 0) {
            newState.setCurrentTime(memoryState.getCurrentTime());
        }

        if (newState.getCue() == null) {
            newState.setCue(new ArrayList<>());
        }
        if (newState.getLastVideos() == null) {
            newState.setLastVideos(new ArrayList<>());
        }

        memoryState = newState;

        newState.setLastUpdateTime(System.currentTimeMillis()); // Set timestamp for latency compensation
        videoPersistenceController.maybePersist(memoryState); // persist only

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll(newState);
        }
    }

    public synchronized void selectVideo(Long id) {
        VideoState st = getState();
        Video current = getCurrentVideo();

        if (current != null && current.id.equals(id)) {
            // Toggle play/pause
            st.setPlaying(!st.isPlaying());
            currentSettings.addLog("Video playback toggled for video: " + current.getTitle());
            if (st.isPlaying()) {
                startPlaybackTimer();
            } else {
                stopPlaybackTimer();
            }
        } else {
            st.setCurrentVideoId(id);
            Video newVideo = findVideo(id);
            st.setVideoTitle(newVideo != null ? newVideo.getTitle() : "Unknown Title");
            st.setSeriesTitle(newVideo != null ? newVideo.getSeriesTitle() : null);
            st.setEpisodeTitle(newVideo != null ? newVideo.getEpisodeTitle() : null);
            st.setDuration(newVideo != null ? newVideo.getDurationSeconds() : 0);
            st.setPlaying(true);
            st.setCurrentTime(0);
            currentSettings.addLog("Video selected: " + (newVideo != null ? newVideo.getTitle() : "Unknown Title"));
            videoQueueController.videoSelected(id);

            addVideoToCueIfNotPresent(st, id);

            // FIX: Update the cue index to match the selected video
            if (st.getCue() != null) {
                st.setCueIndex(st.getCue().indexOf(id));
            }
            startPlaybackTimer(); // Start timer for new video
        }

        updateState(st, true);
        System.out.println("Updated Video Selection");
    }

    private void addVideoToCueIfNotPresent(VideoState st, Long videoId) {
        List<Long> videoIds = new ArrayList<>();
        videoIds.add(videoId);
        videoQueueController.addToQueue(st, videoIds, false);
    }

    private synchronized void stopPlayback() {
        VideoState st = getState();
        videoQueueController.clear(st);
        stopPlaybackTimer(); // Stop the timer when playback is stopped
        updateState(st, true);
    }

    private void handleAction(com.fasterxml.jackson.databind.JsonNode node) {
        String action = node.get("type").asText();
        VideoState state = getState();

        switch (action) {
            case "seek":
                if (node.has("value")) {
                    state.setCurrentTime(node.get("value").asDouble());
                }
                break;
            case "volume":
                if (node.has("value")) {
                    state.setVolume((float) node.get("value").asDouble());
                }
                break;
            case "toggle-play":
                togglePlay();
                break;
            case "next":
                next();
                break;
            case "previous":
                previous();
                break;
            case "video_ended": // New case for natural video end
                handleVideoEnded();
                break;
            // Shuffle and Repeat removed for video
        }

        // Persist throttled to DB
        videoPersistenceController.maybePersist(state);
    }

    // -----------------------------
    // Video helpers
    // -----------------------------
    // Playlist related methods are omitted for now as there is no VideoPlaylistService

    public List<Video> getVideos() {
        return videoService.findAll();
    }

    public VideoService.PaginatedVideos getVideos(int page, int limit) {
        return videoService.findPaginatedByMediaType(null, page, limit); // mediaType will be handled in VideoService if passed as null
    }

    public Video findVideo(Long id) {
        return videoService.find(id);
    }

    public Settings getSettings() {
        return currentSettings.getOrCreateSettings();
    }

    private String safeSummary(VideoState s) {
        if (s == null) {
            return "null";
        }
        return String.format("{playing=%s, videoId=%s, time=%.2f, vol=%.2f}",
                s.isPlaying(), s.getCurrentVideoId(), s.getCurrentTime(), s.getVolume());
    }

    // Helper method to advance video
    private synchronized void advanceVideo(boolean forward, boolean fromVideoEnd) { // Added fromVideoEnd parameter
        VideoState st = getState();

        // Populate cue if empty (initial state)
        if (st.getCue() == null || st.getCue().isEmpty()) {
            List<Video> allVideos = getVideos();
            List<Long> allVideoIds = allVideos.stream().map(v -> v.id).toList();
            videoQueueController.populateCue(st, allVideoIds);
            if (st.getCue().isEmpty()) {
                stopPlayback();
                return;
            }
        }



        // Persist the video that just finished playing to history
        if (st.getCurrentVideoId() != null) {
            Video finishedVideo = findVideo(st.getCurrentVideoId());
            if (finishedVideo != null) {
                videoHistoryService.add(finishedVideo);
            }
        }

        Long nextVideoId = videoQueueController.advance(st, forward);

        if (nextVideoId == null) {
            stopPlayback();
            return;
        }

        st.setCurrentVideoId(nextVideoId);
        Video newVideo = findVideo(nextVideoId);
        st.setVideoTitle(newVideo != null ? newVideo.getTitle() : "Unknown Title");
        st.setSeriesTitle(newVideo != null ? newVideo.getSeriesTitle() : null);
        st.setEpisodeTitle(newVideo != null ? newVideo.getEpisodeTitle() : null);
        st.setDuration(newVideo != null ? newVideo.getDurationSeconds() : 0);
        st.setPlaying(true);
        st.setCurrentTime(0); // Always reset time for a new video
        startPlaybackTimer(); // Ensure timer is running for new video

        updateState(st, true); // persists + broadcasts
    }

    public synchronized void next() {
        currentSettings.addLog("Skipped to next video.");
        System.out.println("Next Video");
        advanceVideo(true, false); // Explicit user action
    }

    public synchronized void previous() {
        currentSettings.addLog("Skipped to previous video.");
        System.out.println("Previous Video");
        VideoState st = getState();

        // If video has been playing for more than 3 seconds, just restart it.
        if (st.getCurrentTime() > 3) {
            st.setCurrentTime(0);
            updateState(st, true);
            return;
        }

        // Try to go to previous in queue first
        if (st.getCueIndex() > 0) {
            advanceVideo(false, false);
            return;
        }

        // We are at the beginning of the queue, try history.
        List<Long> historyIds = videoHistoryService.getRecentlyPlayedVideoIds(2);

        Long videoIdToPlay = null;
        if (!historyIds.isEmpty()) {
            if (historyIds.get(0).equals(st.getCurrentVideoId()) && historyIds.size() > 1) {
                videoIdToPlay = historyIds.get(1);
            } else if (!historyIds.get(0).equals(st.getCurrentVideoId())) {
                videoIdToPlay = historyIds.get(0);
            }
        }

        if (videoIdToPlay != null) {
            Video videoFromHistory = findVideo(videoIdToPlay);
            if (videoFromHistory != null) {
                // We found a video in history. Let's play it.
                // We should also probably put it at the beginning of the cue.
                List<Long> cue = st.getCue();
                if (cue == null) {
                    cue = new ArrayList<>();
                    st.setCue(cue);
                }

                Long videoId = videoFromHistory.id;

                // remove from cue if it exists
                cue.remove(videoId);
                // add to beginning
                cue.add(0, videoId);
                st.setCueIndex(0);

                st.setCurrentVideoId(videoId);
                st.setCurrentTime(0);
                st.setPlaying(true);

                // Need to update video details in state
                st.setVideoTitle(videoFromHistory.getTitle());
                st.setSeriesTitle(videoFromHistory.getSeriesTitle());
                st.setEpisodeTitle(videoFromHistory.getEpisodeTitle());
                st.setDuration(videoFromHistory.getDurationSeconds());

                updateState(st, true);
                startPlaybackTimer();
                return; // We are done
            }
        }

        // No previous video in queue and no suitable history, just restart current video.
        st.setCurrentTime(0);
        updateState(st, true);
    }

    public synchronized void handleVideoEnded() {
        currentSettings.addLog("Video ended naturally.");
        System.out.println("Video Ended");
        advanceVideo(true, true); // Automatic advance due to video end
    }

    public synchronized void togglePlay() {
        currentSettings.addLog("Video playback toggled.");
        System.out.println("Toggle Video Play");
        VideoState state = getState();
        videoQueueController.togglePlay(state);
        if (state.isPlaying()) {
            startPlaybackTimer();
        } else {
            stopPlaybackTimer();
        }
        updateState(state, true);
    }

    // Shuffle methods are omitted for video



    /**
     * Sets the playback volume (0.0 - 1.0) and persists state
     */
    public synchronized void changeVolume(float level) {
        VideoState st = getState();
        videoQueueController.changeVolume(st, level);
        updateState(st, true);
    }

    /**
     * Sets the playback position in seconds and persists state
     */
    public synchronized void setSeconds(double seconds) {
        System.out.println("[VideoController] setSeconds called with: " + seconds);
        VideoState st = getState();
        videoQueueController.setSeconds(st, seconds);
        System.out.println("[VideoController] VideoState currentTime after setSeconds: " + st.getCurrentTime());
        updateState(st, true);
    }

    /**
     * Returns the currently playing video, or null if none
     */
    public synchronized Video getCurrentVideo() {
        VideoState st = getState();
        Long currentId = st.getCurrentVideoId();
        if (currentId != null) {
            return findVideo(currentId);
        }
        // If no currentId, fallback to first video in cue
        List<Long> cue = st.getCue();
        if (cue != null && !cue.isEmpty()) {
            return findVideo(cue.get(0));
        }
        List<Video> allVideos = getVideos();
        return allVideos.isEmpty() ? null : allVideos.get(0);
    }

    /**
     * Returns the previous video in the queue, or null if none.
     */
    public synchronized Video getPreviousVideo() {
        VideoState st = getState();
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();

        if (cue == null || cue.isEmpty() || cueIndex <= 0) {
            // If no previous video in queue, try to get from history
            List<Long> historyIds = videoHistoryService.getRecentlyPlayedVideoIds(1); // Get the very last played
            if (!historyIds.isEmpty()) {
                return findVideo(historyIds.get(0));
            }
            return null; // No previous video in queue or history
        }
        Long prevVideoId = cue.get(cueIndex - 1);
        return findVideo(prevVideoId);
    }

    /**
     * Returns the next video in the queue, or null if none.
     */
    public synchronized Video getNextVideo() {
        VideoState st = getState();
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();

        if (cue == null || cue.isEmpty() || cueIndex >= cue.size() - 1) {
            return null; // No next video
        }
        Long nextVideoId = cue.get(cueIndex + 1);
        return findVideo(nextVideoId);
    }

    // Playlist related methods are omitted for video

    // -----------------------------
    // Queue management helpers
    // -----------------------------
    /**
     * Adds one or more videos to the current queue. If `playNext` is true, videos
     * are inserted after the current index. Otherwise, appended to the end.
     */
    public synchronized void addToQueue(List<Long> videoIds, boolean playNext) {
        if (videoIds == null || videoIds.isEmpty()) {
            return;
        }

        VideoState st = getState();
        videoQueueController.addToQueue(st, videoIds, playNext);
        updateState(st, true);
    }

    /**
     * Removes a specific video from the queue. If it's currently playing, moves
     * to next or stops.
     */
    public synchronized void removeFromQueue(Long videoId) {
        VideoState st = getState();
        videoQueueController.removeFromQueue(st, videoId);
        updateState(st, true);
    }

    /**
     * Clears the entire queue and stops playback.
     */
    public synchronized void clearQueue() {
        VideoState st = getState();
        videoQueueController.clear(st);
        updateState(st, true);
    }

    /**
     * Moves a video within the queue (drag-and-drop style reordering).
     */
    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        VideoState st = getState();
        videoQueueController.moveInQueue(st, fromIndex, toIndex);
        updateState(st, true);
    }

    public synchronized void skipToQueueIndex(int index) {
        LOGGER.info("skipToQueueIndex called with index: " + index);
        VideoState st = getState();
        LOGGER.info("skipToQueueIndex: Original cue: " + st.getCue());
        videoQueueController.skipToQueueIndex(st, index);

        Long videoId = st.getCurrentVideoId();
        LOGGER.info("skipToQueueIndex: Setting current videoId to: " + videoId);
        Video newVideo = findVideo(videoId);
        LOGGER.info("skipToQueueIndex: Found video for ID " + videoId + ": " + (newVideo != null ? newVideo.getTitle() : "null"));
        st.setVideoTitle(newVideo != null ? newVideo.getTitle() : "Unknown Title");
        st.setSeriesTitle(newVideo != null ? newVideo.getSeriesTitle() : null);
        st.setEpisodeTitle(newVideo != null ? newVideo.getEpisodeTitle() : null);
        st.setDuration(newVideo != null ? newVideo.getDurationSeconds() : 0);
        updateState(st, true);
    }

    public List<VideoHistory> getHistory() {
        return videoHistoryService.getHistory(1, 100); // Default to first page, 100 items for now
    }

    public record PaginatedQueue(List<Video> videos, int totalSize) {}

    public PaginatedQueue getQueuePage(int page, int limit) {
        VideoState st = getState();
        List<Long> cueIds = st.getCue();
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
        List<Video> videos = videoService.findByIds(pageOfIds); // Need findByIds in VideoService

        return new PaginatedQueue(videos, totalSize);
    }
}