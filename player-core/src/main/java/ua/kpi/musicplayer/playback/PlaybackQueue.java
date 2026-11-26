package ua.kpi.musicplayer.playback;

import ua.kpi.musicplayer.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PlaybackQueue {

    public enum RepeatMode {
        OFF, ALL, ONE
    }

    private final List<Track> tracks = new ArrayList<>();
    private final Random random = new Random();
    private int currentIndex = -1;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private boolean shuffle;

    public void addTracks(List<Track> newTracks) {
        if (newTracks == null || newTracks.isEmpty()) {
            return;
        }

        tracks.addAll(newTracks);
        if (currentIndex < 0) {
            currentIndex = 0;
        }
    }

    public void clear() {
        tracks.clear();
        currentIndex = -1;
    }

    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public Track getCurrentTrack() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return null;
        }
        return tracks.get(currentIndex);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void select(int index) {
        if (index >= 0 && index < tracks.size()) {
            currentIndex = index;
        }
    }

    public RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(RepeatMode repeatMode) {
        this.repeatMode = repeatMode == null ? RepeatMode.OFF : repeatMode;
    }

    public RepeatMode cycleRepeatMode() {
        switch (repeatMode) {
            case OFF:
                repeatMode = RepeatMode.ALL;
                break;
            case ALL:
                repeatMode = RepeatMode.ONE;
                break;
            default:
                repeatMode = RepeatMode.OFF;
                break;
        }
        return repeatMode;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
    }

    public void next() {
        if (!hasNext()) {
            return;
        }

        if (shuffle && tracks.size() > 1) {
            currentIndex = randomOtherIndex();
        } else if (repeatMode == RepeatMode.ONE) {
            // Поточний трек повторюється індекс не змінюється
        } else if (currentIndex >= tracks.size() - 1) {
            currentIndex = 0;
        } else {
            currentIndex++;
        }
    }

    public void previous() {
        if (!hasPrevious()) {
            return;
        }

        if (shuffle && tracks.size() > 1) {
            currentIndex = randomOtherIndex();
        } else if (currentIndex <= 0) {
            currentIndex = tracks.size() - 1;
        } else {
            currentIndex--;
        }
    }

    public boolean hasNext() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return false;
        }
        if (repeatMode == RepeatMode.ONE) {
            return true;
        }
        if (shuffle) {
            return tracks.size() > 1;
        }
        if (repeatMode == RepeatMode.ALL) {
            return true;
        }
        return currentIndex < tracks.size() - 1;
    }

    public boolean hasPrevious() {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return false;
        }
        if (shuffle) {
            return tracks.size() > 1;
        }
        if (repeatMode == RepeatMode.ALL) {
            return true;
        }
        return currentIndex > 0;
    }

    private int randomOtherIndex() {
        int index;
        do {
            index = random.nextInt(tracks.size());
        } while (index == currentIndex);
        return index;
    }
}
