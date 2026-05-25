package Controllers;

import Models.ProfileSessionState;
import Services.VideoHistoryService;
import Services.VideoService;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoQueueController {

    @Inject
    private VideoHistoryService videoHistoryService;

    @Inject
    private VideoService videoService;

    public void populateCue(ProfileSessionState state, List<Long> videoIds) {
        state.cue = new ArrayList<>(videoIds);
        state.originalCue = new ArrayList<>();
        state.cueIndex = videoIds.isEmpty() ? -1 : 0;
        state.currentVideoId = videoIds.isEmpty() ? null : videoIds.get(0);
        state.playing = !videoIds.isEmpty();
        state.currentTime = 0;

        if (state.currentVideoId != null) {
            Video currentVideo = videoService.find(state.currentVideoId);
            if (currentVideo != null) {
                // ProfileSessionState doesn't store title info anymore
                // This info is fetched when needed in VideoController
            }
        }
    }

    public Long advance(ProfileSessionState state, boolean forward) {
        if (!forward) {
            List<Long> cue = state.cue;
            if (cue == null || cue.isEmpty()) return null;
            int prevIndex = state.cueIndex - 1;
            if (prevIndex < 0) {
                prevIndex = 0;
            }
            state.cueIndex = prevIndex;
            return cue.get(prevIndex);
        }

        List<Long> cue = state.cue;
        if (cue == null || cue.isEmpty()) return null;

        int nextIndex = state.cueIndex + 1;
        if (nextIndex >= cue.size()) {
            state.playing = false;
            return null;
        }

        state.cueIndex = nextIndex;
        return cue.get(nextIndex);
    }

    public void clearShuffle(ProfileSessionState state) {
        List<Long> originalCue = state.originalCue;
        if (originalCue != null && !originalCue.isEmpty()) {
            state.cue = new ArrayList<>(originalCue);
            state.originalCue = new ArrayList<>();
        }
        if (state.cue != null && state.currentVideoId != null) {
            state.cueIndex = state.cue.indexOf(state.currentVideoId);
        } else {
            state.cueIndex = -1;
        }
    }

    public void addToQueue(ProfileSessionState state, List<Long> videoIds, boolean playNext) {
        if (videoIds == null || videoIds.isEmpty()) return;

        List<Long> cue = state.cue;
        if (cue == null) {
            cue = new ArrayList<>();
            state.cue = cue;
        }

        int insertIndex = playNext && state.cueIndex >= 0 ? state.cueIndex + 1 : cue.size();

        for (Long id : videoIds) {
            if (!cue.contains(id)) {
                cue.add(insertIndex++, id);
            }
        }
    }

    public void removeFromQueue(ProfileSessionState state, Long videoId) {
        List<Long> cue = state.cue;
        if (cue == null || !cue.contains(videoId)) return;

        int index = cue.indexOf(videoId);
        cue.remove(index);

        if (Objects.equals(videoId, state.currentVideoId)) {
            if (cue.isEmpty()) {
                state.currentVideoId = null;
                state.playing = false;
                state.cueIndex = -1;
            } else {
                int nextIndex = Math.min(index, cue.size() - 1);
                state.cueIndex = nextIndex;
                state.currentVideoId = cue.get(nextIndex);
                state.currentTime = 0;
            }
        } else if (index < state.cueIndex) {
            state.cueIndex = state.cueIndex - 1;
        }
    }

    public void clear(ProfileSessionState state) {
        state.cue = new ArrayList<>();
        state.originalCue = new ArrayList<>();
        state.cueIndex = -1;
        state.currentVideoId = null;
        state.playing = false;
        state.currentTime = 0;
    }

    public void moveInQueue(ProfileSessionState state, int fromIndex, int toIndex) {
        List<Long> cue = state.cue;
        if (cue == null || fromIndex < 0 || fromIndex >= cue.size() || toIndex < 0 || toIndex > cue.size()) return;

        Long videoId = cue.remove(fromIndex);
        cue.add(toIndex, videoId);

        // Adjust current cue index
        int currentIndex = state.cueIndex;
        if (currentIndex == fromIndex) {
            state.cueIndex = toIndex;
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            state.cueIndex = currentIndex - 1;
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            state.cueIndex = currentIndex + 1;
        }
    }

    public void togglePlay(ProfileSessionState state) {
        state.playing = !state.playing;
    }

    public void changeVolume(ProfileSessionState state, float level) {
        state.volume = Math.max(0f, Math.min(1f, level));
    }

    public void setSeconds(ProfileSessionState state, double seconds) {
        state.currentTime = Math.max(0, seconds);
    }

    public void videoSelected(Long videoId, ProfileSessionState state) {
    }

    public void skipToQueueIndex(ProfileSessionState state, int index) {
        List<Long> cue = state.cue;
        if (cue == null || index < 0 || index >= cue.size()) return;

        if (state.currentVideoId != null) {
            videoHistoryService.addFromVideoId(state.currentVideoId);
        }

        state.cueIndex = index;
        state.currentVideoId = cue.get(index);
        state.currentTime = 0;
        state.playing = true;
        videoHistoryService.addFromVideoId(state.currentVideoId);
    }
}