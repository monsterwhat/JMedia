package Controllers;

import Models.VideoState;
import Models.Video;
import Services.VideoHistoryService;
import Services.VideoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class VideoQueueController {

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoService videoService;

    private void addVideoToHistory(Long videoId) {
        if (videoId == null) {
            return;
        }
        Video video = videoService.find(videoId);
        if (video != null) {
            videoHistoryService.add(video);
        }
    }

    public void populateCue(VideoState state, List<Long> videoIds) {
        state.setCue(new ArrayList<>(videoIds));
        state.setOriginalCue(new ArrayList<>()); // Clear any previously saved original cue
        state.setCueIndex(videoIds.isEmpty() ? -1 : 0);
        state.setCurrentVideoId(videoIds.isEmpty() ? null : videoIds.get(0));
        state.setPlaying(!videoIds.isEmpty());
        state.setCurrentTime(0);
        // Update video title, series title, episode title
        if (state.getCurrentVideoId() != null) {
            Video currentVideo = videoService.find(state.getCurrentVideoId());
            if (currentVideo != null) {
                state.setVideoTitle(currentVideo.getTitle());
                state.setSeriesTitle(currentVideo.getSeriesTitle());
                state.setEpisodeTitle(currentVideo.getEpisodeTitle());
            } else {
                state.setVideoTitle("Unknown Title");
                state.setSeriesTitle(null);
                state.setEpisodeTitle(null);
            }
        }
    }

    public Long advance(VideoState state, boolean forward) {
        if (!forward) {
            // 'previous' logic
            List<Long> cue = state.getCue();
            if (cue == null || cue.isEmpty()) return null;
            int prevIndex = state.getCueIndex() - 1;
            if (prevIndex < 0) {
                prevIndex = 0; // Stay at the beginning
            }
            state.setCueIndex(prevIndex);
            return cue.get(prevIndex);
        }

        // --- FORWARD ADVANCEMENT ---
        addVideoToHistory(state.getCurrentVideoId());

        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty()) return null;

        int nextIndex = state.getCueIndex() + 1;
        if (nextIndex >= cue.size()) {
            state.setPlaying(false); // Stop playback if at the end of the queue
            return null;
        }

        state.setCueIndex(nextIndex);
        return cue.get(nextIndex);
    }

    // Shuffle logic is omitted for now as discussed.

    public void clearShuffle(VideoState state) {
        // Shuffle logic is omitted, so this method will simply clear original cue
        List<Long> originalCue = state.getOriginalCue();
        if (originalCue != null && !originalCue.isEmpty()) {
            state.setCue(new ArrayList<>(originalCue));
            state.setOriginalCue(new ArrayList<>());
        }
        if (state.getCue() != null && state.getCurrentVideoId() != null) {
            int newIndex = state.getCue().indexOf(state.getCurrentVideoId());
            state.setCueIndex(newIndex);
        } else {
            state.setCueIndex(-1);
        }
    }

    public void addToQueue(VideoState state, List<Long> videoIds, boolean playNext) {
        if (videoIds == null || videoIds.isEmpty()) {
            return;
        }
        List<Long> cue = state.getCue();
        if (cue == null) {
            cue = new ArrayList<>();
            state.setCue(cue);
        }

        int insertIndex = playNext && state.getCueIndex() >= 0
                ? state.getCueIndex() + 1
                : cue.size();

        for (Long id : videoIds) {
            if (!cue.contains(id)) {
                cue.add(insertIndex, id);
                insertIndex++;
            }
        }
    }

    public void removeFromQueue(VideoState state, Long videoId) {
        List<Long> cue = state.getCue();
        if (cue == null || !cue.contains(videoId)) {
            return;
        }

        int index = cue.indexOf(videoId);
        cue.remove(videoId);

        if (Objects.equals(videoId, state.getCurrentVideoId())) {
            if (cue.isEmpty()) {
                state.setCurrentVideoId(null);
                state.setPlaying(false);
                state.setCueIndex(-1);
            } else {
                int nextIndex = Math.min(index, cue.size() - 1);
                state.setCueIndex(nextIndex);
                state.setCurrentVideoId(cue.get(nextIndex));
                state.setCurrentTime(0);
            }
        } else if (index < state.getCueIndex()) {
            state.setCueIndex(state.getCueIndex() - 1);
        }
    }

    public void clear(VideoState state) {
        state.setCue(new ArrayList<>());
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setCurrentVideoId(null);
        state.setPlaying(false);
        state.setCurrentTime(0);
    }

    public void moveInQueue(VideoState state, int fromIndex, int toIndex) {
        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty() || fromIndex < 0 || fromIndex >= cue.size() || toIndex < 0 || toIndex >= cue.size()) {
            return;
        }

        Long videoId = cue.remove(fromIndex);
        cue.add(toIndex, videoId);

        int currentIdx = state.getCueIndex();
        if (currentIdx == fromIndex) {
            state.setCueIndex(toIndex);
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            state.setCueIndex(currentIdx - 1);
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            state.setCueIndex(currentIdx + 1);
        }
    }

    public void togglePlay(VideoState state) {
        state.setPlaying(!state.isPlaying());
    }



    public void changeVolume(VideoState state, float level) {
        state.setVolume(Math.max(0f, Math.min(1f, level)));
    }

    public void setSeconds(VideoState state, double seconds) {
        state.setCurrentTime(Math.max(0, seconds));
    }

    public void videoSelected(Long videoId) {
        addVideoToHistory(videoId);
    }

    public void skipToQueueIndex(VideoState state, int index) {
        List<Long> cue = state.getCue();
        if (cue == null || index < 0 || index >= cue.size()) {
            return;
        }
        addVideoToHistory(state.getCurrentVideoId()); // Add the video we are skipping from

        List<Long> newCue = new ArrayList<>(cue.subList(index, cue.size()));

        List<Long> originalCue = state.getOriginalCue();
        if (originalCue != null && !originalCue.isEmpty()) {
            java.util.Set<Long> remainingIds = new java.util.HashSet<>(newCue);
            List<Long> newOriginalCue = originalCue.stream()
                    .filter(remainingIds::contains)
                    .collect(java.util.stream.Collectors.toList());
            state.setOriginalCue(newOriginalCue);
        }

        state.setCue(newCue);
        state.setCueIndex(0);
        state.setCurrentVideoId(newCue.get(0));
        state.setCurrentTime(0);
        state.setPlaying(true);
    }
}
