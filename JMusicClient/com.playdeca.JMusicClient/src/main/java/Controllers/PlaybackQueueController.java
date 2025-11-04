package Controllers;

import Models.PlaybackState; 
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlaybackQueueController {

    public void populateCue(PlaybackState state, List<Long> songIds) {
        state.setCue(new ArrayList<>(songIds));
        state.setCueIndex(songIds.isEmpty() ? -1 : 0);
        state.setCurrentSongId(songIds.isEmpty() ? null : songIds.get(0));
        state.setPlaying(!songIds.isEmpty());
        state.setCurrentTime(0);
    }

    public Long advance(PlaybackState state, boolean forward) {
        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty()) {
            return null; // No songs in cue
        }

        Long oldCurrentSongId = state.getCurrentSongId();
        int currentSongIndex = state.getCueIndex();

        // Handle RepeatMode.ONE
        if (state.getRepeatMode() == PlaybackState.RepeatMode.ONE) {
            state.setCurrentTime(0); // Reset time for repeat one
            return oldCurrentSongId; // Play the same song again
        }

        // Determine next index based on shuffle and direction
        int nextIndex;
        if (state.isShuffleEnabled()) {
            // If shuffle is enabled, we need to manage a shuffled version of the cue
            // For simplicity, let's re-shuffle the remaining songs if we reach the end
            // or if it's the first time advancing in shuffle mode.
            List<Long> shuffled = new ArrayList<>(cue);
            Collections.shuffle(shuffled);

            int currentShuffledIndex = shuffled.indexOf(oldCurrentSongId);
            if (currentShuffledIndex == -1) {
                // Current song not in shuffled list, pick a random one
                nextIndex = 0;
            } else {
                nextIndex = currentShuffledIndex + (forward ? 1 : -1);
            }

            if (nextIndex >= shuffled.size()) {
                if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                    Collections.shuffle(shuffled); // Reshuffle and start again
                    nextIndex = 0;
                } else {
                    return null; // End of shuffled queue, no repeat
                }
            } else if (nextIndex < 0) {
                if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                    Collections.shuffle(shuffled); // Reshuffle and go to the end
                    nextIndex = shuffled.size() - 1;
                } else {
                    nextIndex = 0; // Stay at the beginning
                }
            }
            state.setCue(shuffled); // Update the main cue with the shuffled order for consistency
            state.setCueIndex(nextIndex);
            return shuffled.get(nextIndex);

        } else {
            // Sequential playback
            nextIndex = currentSongIndex + (forward ? 1 : -1);

            if (nextIndex >= cue.size()) {
                if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                    nextIndex = 0; // Wrap around to the beginning
                } else {
                    return null; // End of queue, no repeat
                }
            } else if (nextIndex < 0) {
                if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                    nextIndex = cue.size() - 1; // Wrap around to the end
                }
            }
            state.setCueIndex(nextIndex);
            return cue.get(nextIndex);
        }
    }

    public void initShuffle(PlaybackState state) {
        List<Long> originalCue = state.getCue();
        Long currentSongId = state.getCurrentSongId();

        if (originalCue == null || originalCue.isEmpty() || currentSongId == null) {
            // If no current song or empty cue, just shuffle all songs
            List<Long> shuffled = new ArrayList<>(originalCue != null ? originalCue : Collections.emptyList());
            Collections.shuffle(shuffled);
            state.setCue(shuffled);
            state.setCueIndex(shuffled.isEmpty() ? -1 : 0);
        } else {
            List<Long> remainingSongs = new ArrayList<>(originalCue);
            remainingSongs.remove(currentSongId);
            Collections.shuffle(remainingSongs);

            List<Long> newShuffledCue = new ArrayList<>();
            newShuffledCue.add(currentSongId);
            newShuffledCue.addAll(remainingSongs);

            state.setCue(newShuffledCue);
            state.setCueIndex(0);
        }
    }

    public void clearShuffle(PlaybackState state) {
        // When shuffle is disabled, revert to the original order if possible
        // For now, we'll just clear the shuffled state and let the main cue be used directly.
        // A more sophisticated approach might store the original cue when shuffle is enabled.
        // For this migration, we assume the 'cue' in PlaybackState always holds the active queue.
        // If shuffle was active, the 'cue' would have been modified by initShuffle.
        // To truly revert, we'd need a 'pristineCue' in PlaybackState.
        // For now, we just ensure the index is valid for the current (potentially shuffled) cue.
        if (state.getCue() != null && !state.getCue().isEmpty() && state.getCurrentSongId() != null) {
            state.setCueIndex(state.getCue().indexOf(state.getCurrentSongId()));
        } else {
            state.setCueIndex(-1);
        }
    }

    public void addToQueue(PlaybackState state, List<Long> songIds, boolean playNext) {
        if (songIds == null || songIds.isEmpty()) return;
        List<Long> cue = state.getCue();
        if (cue == null) {
            cue = new ArrayList<>();
            state.setCue(cue);
        }

        int insertIndex = playNext && state.getCueIndex() >= 0
                ? state.getCueIndex() + 1
                : cue.size();

        for (Long id : songIds) {
            if (!cue.contains(id)) {
                cue.add(insertIndex, id);
                insertIndex++;
            }
        }
    }

    public void removeFromQueue(PlaybackState state, Long songId) {
        List<Long> cue = state.getCue();
        if (cue == null || !cue.contains(songId)) return;

        int index = cue.indexOf(songId);
        cue.remove(songId);

        if (Objects.equals(songId, state.getCurrentSongId())) {
            if (cue.isEmpty()) {
                state.setCurrentSongId(null);
                state.setPlaying(false);
                state.setCueIndex(-1);
            } else {
                int nextIndex = Math.min(index, cue.size() - 1);
                state.setCueIndex(nextIndex);
                state.setCurrentSongId(cue.get(nextIndex));
                state.setCurrentTime(0);
            }
        } else if (index < state.getCueIndex()) {
            state.setCueIndex(state.getCueIndex() - 1);
        }
    }

    public void clear(PlaybackState state) {
        state.setCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setCurrentSongId(null);
        state.setPlaying(false);
        state.setCurrentTime(0);
    }

    public void moveInQueue(PlaybackState state, int fromIndex, int toIndex) {
        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty() || fromIndex < 0 || fromIndex >= cue.size() || toIndex < 0 || toIndex >= cue.size()) {
            return;
        }

        Long songId = cue.remove(fromIndex);
        cue.add(toIndex, songId);

        // Adjust cue index if needed
        int currentIdx = state.getCueIndex();
        if (currentIdx == fromIndex) {
            state.setCueIndex(toIndex);
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            state.setCueIndex(currentIdx - 1);
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            state.setCueIndex(currentIdx + 1);
        }
    }

    public void togglePlay(PlaybackState state) {
        state.setPlaying(!state.isPlaying());
    }

    public void toggleRepeat(PlaybackState state) {
        PlaybackState.RepeatMode currentMode = state.getRepeatMode();
        PlaybackState.RepeatMode nextMode;

        switch (currentMode) {
            case OFF:
                nextMode = PlaybackState.RepeatMode.ONE;
                break;
            case ONE:
                nextMode = PlaybackState.RepeatMode.ALL;
                break;
            case ALL:
                nextMode = PlaybackState.RepeatMode.OFF;
                break;
            default:
                nextMode = PlaybackState.RepeatMode.OFF;
                break;
        }
        state.setRepeatMode(nextMode);
    }

    public void changeVolume(PlaybackState state, float level) {
        state.setVolume(Math.max(0f, Math.min(1f, level)));
    }

    public void setSeconds(PlaybackState state, double seconds) {
        state.setCurrentTime(Math.max(0, seconds));
    }

    public void skipToQueueIndex(PlaybackState state, int index) {
        List<Long> cue = state.getCue();
        if (cue == null || index < 0 || index >= cue.size()) {
            return;
        }
        state.setCueIndex(index);
        state.setCurrentSongId(cue.get(index));
        state.setCurrentTime(0);
        state.setPlaying(true);
    }
}
