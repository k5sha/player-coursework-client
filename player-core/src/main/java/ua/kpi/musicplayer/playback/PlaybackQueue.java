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

    public Track removeAt(int index) {
        if (index < 0 || index >= tracks.size()) {
            return null;
        }

        Track removed = tracks.remove(index);
        if (tracks.isEmpty()) {
            currentIndex = -1;
        } else if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex && currentIndex >= tracks.size()) {
            currentIndex = tracks.size() - 1;
        }
        return removed;
    }

    public void removeTracks(List<Track> tracksToRemove) {
        if (tracksToRemove == null || tracksToRemove.isEmpty()) {
            return;
        }

        Track current = getCurrentTrack();
        tracks.removeAll(tracksToRemove);
        if (tracks.isEmpty()) {
            currentIndex = -1;
        } else if (current != null) {
            int index = tracks.indexOf(current);
            currentIndex = index >= 0 ? index : Math.min(currentIndex, tracks.size() - 1);
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

    public void setCurrentIndex(int index) {
        if (index >= -1 && index < tracks.size()) {
            currentIndex = index;
        }
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
        if (this.repeatMode != RepeatMode.OFF) {
            shuffle = false;
        }
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
        if (repeatMode != RepeatMode.OFF) {
            shuffle = false;
        }
        return repeatMode;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
        if (shuffle) {
            repeatMode = RepeatMode.OFF;
        }
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        if (shuffle) {
            repeatMode = RepeatMode.OFF;
        }
    }

    public void next() {
        QueueTrackIterator iterator = createIterator();
        if (!iterator.hasNext()) {
            return;
        }
        iterator.next();
        currentIndex = iterator.getCurrentIndex();
    }

    public void previous() {
        QueueTrackIterator iterator = createIterator();
        if (!iterator.hasPrevious()) {
            return;
        }
        iterator.previous();
        currentIndex = iterator.getCurrentIndex();
    }

    public boolean hasNext() {
        return createIterator().hasNext();
    }

    public boolean hasPrevious() {
        return createIterator().hasPrevious();
    }

    private QueueTrackIterator createIterator() {
        return new QueueTrackIterator(tracks, currentIndex, repeatMode, shuffle, random);
    }
}
