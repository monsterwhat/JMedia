package Controllers;

import API.WS.VideoSocket;
import Models.ProfileSessionState;
import Models.Settings;
import Models.Video;
import Models.VideoHistory;
import Models.VideoState;
import Services.ProfileSessionStateService;
import Services.VideoHistoryService;
import Services.VideoService;
import Services.VideoStateService;
import Services.CollectionWatchProgressService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoController {

    private ProfileSessionState memoryState;

    @Inject VideoPersistenceController videoPersistenceController;
    @Inject VideoQueueController videoQueueController;
    @Inject SettingsController currentSettings;
    @Inject VideoService videoService;
    @Inject VideoHistoryService videoHistoryService;
    @Inject VideoStateService videoStateService;
    @Inject ProfileSessionStateService profileSessionStateService;
    @Inject CollectionWatchProgressService collectionWatchProgressService;
    @Inject VideoSocket ws;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> playbackTask;
    private static final long PLAYBACK_UPDATE_INTERVAL_MS = 300;

    private static final Logger LOGGER = Logger.getLogger(VideoController.class.getName());

    public VideoController() {}

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        memoryState = videoPersistenceController.loadState();
        if (memoryState == null) {
            memoryState = new ProfileSessionState();
        }

        if (memoryState.cue.isEmpty()) {
            List<Long> videoIds = videoService.findAllVideoIds();
            if (!videoIds.isEmpty()) {
                memoryState.cue = videoIds;
                memoryState.cueIndex = 0;
                memoryState.currentVideoId = videoIds.get(0);
            }
        }

        if (memoryState.currentVideoId == null && !memoryState.cue.isEmpty()) {
            memoryState.currentVideoId = memoryState.cue.get(0);
            memoryState.cueIndex = 0;
        }

        memoryState.playing = false;

        if (memoryState.playing) {
            startPlaybackTimer();
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        if (playbackTask != null) playbackTask.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
    }

    private synchronized void startPlaybackTimer() {
        if (playbackTask == null || playbackTask.isDone()) {
            playbackTask = scheduler.scheduleAtFixedRate(this::processPlaybackTick, 0, PLAYBACK_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @jakarta.transaction.Transactional
    protected void processPlaybackTick() {
        ProfileSessionState st = getState();
        if (st.playing && st.currentVideoId != null) {
            double newTime = st.currentTime + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
            Video currentVideo = findVideo(st.currentVideoId);
            double duration = currentVideo != null && currentVideo.duration != null ? currentVideo.duration / 1000.0 : 0;
            if (newTime >= duration && duration > 0) {
                handleVideoEnded();
            } else {
                st.currentTime = newTime;
                updateState(st, true);
            }
        }
    }

    private synchronized void stopPlaybackTimer() {
        if (playbackTask != null && !playbackTask.isDone()) {
            playbackTask.cancel(false);
        }
    }

    public synchronized ProfileSessionState getState() {
        if (memoryState == null) {
            memoryState = videoPersistenceController.loadState();
            if (memoryState == null) memoryState = new ProfileSessionState();
        }
        return memoryState;
    }

    public synchronized void updateState(ProfileSessionState newState, boolean shouldBroadcast) {
        if (newState.currentVideoId != null) {
            Video currentVideo = videoService.find(newState.currentVideoId);
            if (currentVideo != null) {
                newState.currentTime = newState.currentTime;
            }
        }

        memoryState = profileSessionStateService.save(newState);

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll(newState);
        }
    }

    public synchronized void selectVideo(Long id) {
        selectVideo(id, null);
    }

    public synchronized void selectVideo(Long id, Double startTime) {
        ProfileSessionState st = getState();
        Video current = getCurrentVideo();

        if (current != null && current.id.equals(id)) {
            st.playing = !st.playing;
            if (st.playing) startPlaybackTimer();
            else stopPlaybackTimer();
        } else {
            st.currentVideoId = id;
            Video newVideo = findVideo(id);
            if (newVideo != null) {
                // Record history when a new video is selected
                videoHistoryService.addFromVideoId(id);

                // Use provided startTime (> 0) or get per-profile resume time from VideoState
                if (startTime != null && startTime > 0) {
                    st.currentTime = startTime;
                } else {
                    // Get per-profile progress
                    VideoState progress = videoStateService.getOrCreate(newVideo);
                    if (progress != null && progress.currentTime > 0) {
                        st.currentTime = progress.currentTime;
                    } else {
                        st.currentTime = 0;
                    }
                }
                
                // Include audio preferences for frontend to restore
                st.preferredAudioLanguage = newVideo.preferredAudioLanguage;
                st.defaultAudioTrackId = newVideo.defaultAudioTrackId;
            } else {
                st.currentTime = 0;
            }
            st.playing = true;
            addVideoToCueIfNotPresent(st, id);
            if (st.cue != null) {
                st.cueIndex = st.cue.indexOf(id);
            }
            startPlaybackTimer();
        }
        updateState(st, true);
    }

    private void addVideoToCueIfNotPresent(ProfileSessionState st, Long videoId) {
        if (st.cue == null || !st.cue.contains(videoId)) {
            videoQueueController.addToQueue(st, List.of(videoId), false);
        }
    }

    private synchronized void stopPlayback() {
        ProfileSessionState st = getState();
        videoQueueController.clear(st);
        st.collectionId = null;
        stopPlaybackTimer();
        updateState(st, true);
    }
    
    private synchronized void advanceVideo(boolean forward, boolean fromVideoEnd) {
        ProfileSessionState st = getState();

        if (st.currentVideoId != null) {
            videoHistoryService.addFromVideoId(st.currentVideoId);
        }

        if (st.cue == null || st.cue.isEmpty()) {
            if (st.collectionId != null) {
                collectionWatchProgressService.markCompleted(st.collectionId);
                st.collectionId = null;
            }
            stopPlayback();
            return;
        }

        Long nextVideoId = videoQueueController.advance(st, forward);

        if (nextVideoId == null) {
            if (st.collectionId != null) {
                collectionWatchProgressService.markCompleted(st.collectionId);
                st.collectionId = null;
            }
            stopPlayback();
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
        startPlaybackTimer();
        updateState(st, true);
    }
    
    public synchronized void next() {
        advanceVideo(true, false);
    }

    public synchronized void previous() {
        ProfileSessionState st = getState();
        if (st.currentTime > 3) {
            st.currentTime = 0;
            updateState(st, true);
            return;
        }
        advanceVideo(false, false);
    }

    public synchronized void handleVideoEnded() {
        ProfileSessionState st = getState();
        stopPlaybackTimer();
        st.playing = false;
        updateState(st, true);

        if (st.collectionId != null && st.cue != null && st.cueIndex + 1 < st.cue.size()) {
            advanceVideo(true, true);
        } else if (st.collectionId != null && st.cue != null && st.cueIndex + 1 >= st.cue.size()) {
            collectionWatchProgressService.markCompleted(st.collectionId);
            st.collectionId = null;
            updateState(st, false);
        }
    }

    public synchronized void togglePlay() {
        ProfileSessionState state = getState();
        videoQueueController.togglePlay(state);
        if (state.playing) startPlaybackTimer();
        else stopPlaybackTimer();
        updateState(state, true);
    }
    
    public List<Video> getVideos() {
        return Models.Video.listAll();
    }

    public List<Video> getVideos(int page, int limit) {
        return Models.Video.findAll().page(page - 1, limit).list();
    }

    public Video findVideo(Long id) {
        return Models.Video.findById(id);
    }
    
    public synchronized void changeVolume(float level) {
        ProfileSessionState st = getState();
        videoQueueController.changeVolume(st, level);
        updateState(st, true);
    }

    public synchronized void setSeconds(double seconds) {
        ProfileSessionState st = getState();
        videoQueueController.setSeconds(st, seconds);
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
            Video video = Models.Video.findById(id);
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
    // - All settings-related methods like getSettings(), addLog() are kept via SettingsController injection.
    
    // A simplified history mechanism would be needed. The VideoHistoryService must be updated.
    public List<VideoHistory> getHistory() {
        return videoHistoryService.getHistory(1, 100);
    }
    
    @PreDestroy
    public void shutdown() {
        if (playbackTask != null) {
            playbackTask.cancel(true);
        }
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
