package Controllers;

import API.WS.VideoSocket;
import Models.VideoState;
import Models.Settings;
import Models.VideoHistory;
import Services.VideoHistoryService;
import Services.VideoService;
import Services.VideoService.VideoDTO;
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

    private VideoState memoryState;

    @Inject VideoPersistenceController videoPersistenceController;
    @Inject VideoQueueController videoQueueController;
    @Inject SettingsController currentSettings;
    @Inject VideoService videoService;
    @Inject VideoHistoryService videoHistoryService; // Assuming this service can now work with MediaFile IDs
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
            memoryState = new VideoState();
        }

        if (memoryState.getCue().isEmpty()) {
            List<VideoDTO> allVideos = getVideos();
            if (!allVideos.isEmpty()) {
                memoryState.setCue(allVideos.stream().map(VideoDTO::id).collect(Collectors.toList()));
                memoryState.setCueIndex(0);
                memoryState.setCurrentVideoId(allVideos.get(0).id());
            }
        }

        if (memoryState.getCurrentVideoId() == null && !memoryState.getCue().isEmpty()) {
            memoryState.setCurrentVideoId(memoryState.getCue().get(0));
            memoryState.setCueIndex(0);
        }

        memoryState.setPlaying(false);

        if (memoryState.isPlaying()) {
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
        VideoState st = getState();
        if (st.isPlaying() && st.getCurrentVideoId() != null) {
            double newTime = st.getCurrentTime() + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
            if (newTime >= st.getDuration() && st.getDuration() > 0) {
                handleVideoEnded();
            } else {
                st.setCurrentTime(newTime);
                updateState(st, true);
            }
        }
    }

    private synchronized void stopPlaybackTimer() {
        if (playbackTask != null && !playbackTask.isDone()) {
            playbackTask.cancel(false);
        }
    }

    public synchronized VideoState getState() {
        if (memoryState == null) {
            memoryState = videoPersistenceController.loadState();
            if (memoryState == null) memoryState = new VideoState();
        }
        return memoryState;
    }

    public synchronized void updateState(VideoState newState, boolean shouldBroadcast) {
        if (newState.getCurrentVideoId() != null) {
            VideoDTO currentVideo = videoService.find(newState.getCurrentVideoId());
            if (currentVideo != null) {
                newState.setVideoTitle(currentVideo.title());
                newState.setSeriesTitle(currentVideo.seriesTitle());
                newState.setEpisodeTitle(currentVideo.type().equals("Episode") ? currentVideo.title() : null); // Simple mapping
                newState.setDuration(currentVideo.durationSeconds());
            } else {
                newState.setVideoTitle("Unknown Title");
                newState.setSeriesTitle(null);
                newState.setEpisodeTitle(null);
                newState.setDuration(0);
            }
        }

        memoryState = newState;
        newState.setLastUpdateTime(System.currentTimeMillis());
        videoPersistenceController.maybePersist(memoryState);

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll(newState);
        }
    }

    public synchronized void selectVideo(Long id) {
        VideoState st = getState();
        VideoDTO current = getCurrentVideo();

        if (current != null && current.id().equals(id)) {
            st.setPlaying(!st.isPlaying());
            if (st.isPlaying()) startPlaybackTimer();
            else stopPlaybackTimer();
        } else {
            st.setCurrentVideoId(id);
            VideoDTO newVideo = findVideo(id);
            if (newVideo != null) {
                st.setVideoTitle(newVideo.title());
                st.setSeriesTitle(newVideo.seriesTitle());
                st.setEpisodeTitle("Episode".equals(newVideo.type()) ? newVideo.title() : null);
                st.setDuration(newVideo.durationSeconds());
            }
            st.setPlaying(true);
            st.setCurrentTime(0);
            videoQueueController.videoSelected(id);
            addVideoToCueIfNotPresent(st, id);
            if (st.getCue() != null) {
                st.setCueIndex(st.getCue().indexOf(id));
            }
            startPlaybackTimer();
        }
        updateState(st, true);
    }

    private void addVideoToCueIfNotPresent(VideoState st, Long videoId) {
        if (st.getCue() == null || !st.getCue().contains(videoId)) {
            videoQueueController.addToQueue(st, List.of(videoId), false);
        }
    }

    private synchronized void stopPlayback() {
        VideoState st = getState();
        videoQueueController.clear(st);
        stopPlaybackTimer();
        updateState(st, true);
    }
    
    private synchronized void advanceVideo(boolean forward, boolean fromVideoEnd) {
        VideoState st = getState();
        if (st.getCue() == null || st.getCue().isEmpty()) {
            stopPlayback();
            return;
        }

        if (st.getCurrentVideoId() != null) {
            // videoHistoryService.add expects a Video object, this needs adjustment.
            // For now, we assume it can take the ID or has been refactored.
            // videoHistoryService.add(st.getCurrentVideoId());
        }

        Long nextVideoId = videoQueueController.advance(st, forward);
        if (nextVideoId == null) {
            stopPlayback();
            return;
        }

        st.setCurrentVideoId(nextVideoId);
        st.setCurrentTime(0);
        st.setPlaying(true);
        startPlaybackTimer();
        updateState(st, true);
    }
    
    public synchronized void next() {
        advanceVideo(true, false);
    }

    public synchronized void previous() {
        VideoState st = getState();
        if (st.getCurrentTime() > 3) {
            st.setCurrentTime(0);
            updateState(st, true);
            return;
        }
        advanceVideo(false, false);
    }

    public synchronized void handleVideoEnded() {
        advanceVideo(true, true);
    }

    public synchronized void togglePlay() {
        VideoState state = getState();
        videoQueueController.togglePlay(state);
        if (state.isPlaying()) startPlaybackTimer();
        else stopPlaybackTimer();
        updateState(state, true);
    }
    
    public List<VideoDTO> getVideos() {
        return videoService.findAll();
    }

    public VideoService.PaginatedVideos getVideos(int page, int limit) {
        return videoService.findPaginatedByMediaType(null, page, limit);
    }

    public VideoDTO findVideo(Long id) {
        return videoService.find(id);
    }
    
    public synchronized void changeVolume(float level) {
        VideoState st = getState();
        videoQueueController.changeVolume(st, level);
        updateState(st, true);
    }

    public synchronized void setSeconds(double seconds) {
        VideoState st = getState();
        videoQueueController.setSeconds(st, seconds);
        updateState(st, true);
    }

    public synchronized VideoDTO getCurrentVideo() {
        VideoState st = getState();
        Long currentId = st.getCurrentVideoId();
        if (currentId != null) {
            return findVideo(currentId);
        }
        List<Long> cue = st.getCue();
        if (cue != null && !cue.isEmpty()) {
            return findVideo(cue.get(0));
        }
        List<VideoDTO> allVideos = getVideos();
        return allVideos.isEmpty() ? null : allVideos.get(0);
    }
    
    public synchronized void addToQueue(List<Long> videoIds, boolean playNext) {
        if (videoIds == null || videoIds.isEmpty()) return;
        VideoState st = getState();
        videoQueueController.addToQueue(st, videoIds, playNext);
        updateState(st, true);
    }

    public synchronized void removeFromQueue(Long videoId) {
        VideoState st = getState();
        videoQueueController.removeFromQueue(st, videoId);
        updateState(st, true);
    }
    
    public synchronized void clearQueue() {
        VideoState st = getState();
        videoQueueController.clear(st);
        updateState(st, true);
    }
    
    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        VideoState st = getState();
        videoQueueController.moveInQueue(st, fromIndex, toIndex);
        updateState(st, true);
    }

    public synchronized void skipToQueueIndex(int index) {
        VideoState st = getState();
        videoQueueController.skipToQueueIndex(st, index);
        updateState(st, true); // updateState will fetch the new video details and broadcast
    }

    public record PaginatedQueue(List<VideoDTO> videos, int totalSize) {}

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
        List<VideoDTO> videos = videoService.findByIds(pageOfIds);

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
