package ua.kpi.musicplayer.playback;

import ua.kpi.musicplayer.model.Track;

import java.util.List;
import java.util.Random;

public class QueueTrackIterator implements TrackIterator {

    private final List<Track> tracks;
    private final PlaybackQueue.RepeatMode repeatMode;
    private final boolean shuffle;
    private final Random random;
    private int currentIndex;

    public QueueTrackIterator(List<Track> tracks, int currentIndex,
                              PlaybackQueue.RepeatMode repeatMode, boolean shuffle, Random random) {
        this.tracks = tracks;
        this.currentIndex = currentIndex;
        this.repeatMode = repeatMode == null ? PlaybackQueue.RepeatMode.OFF : repeatMode;
        this.shuffle = shuffle;
        this.random = random;
    }

    @Override
    public boolean hasNext() {
        if (isCurrentIndexInvalid()) {
            return false;
        }
        if (repeatMode == PlaybackQueue.RepeatMode.ONE) {
            return true;
        }
        if (shuffle) {
            return tracks.size() > 1;
        }
        if (repeatMode == PlaybackQueue.RepeatMode.ALL) {
            return true;
        }
        return currentIndex < tracks.size() - 1;
    }

    @Override
    public Track next() {
        if (!hasNext()) {
            return null;
        }

        if (shuffle && tracks.size() > 1) {
            currentIndex = randomOtherIndex();
        } else if (repeatMode != PlaybackQueue.RepeatMode.ONE) {
            currentIndex = currentIndex >= tracks.size() - 1 ? 0 : currentIndex + 1;
        }
        return tracks.get(currentIndex);
    }

    @Override
    public boolean hasPrevious() {
        if (isCurrentIndexInvalid()) {
            return false;
        }
        if (shuffle) {
            return tracks.size() > 1;
        }
        if (repeatMode == PlaybackQueue.RepeatMode.ALL) {
            return true;
        }
        return currentIndex > 0;
    }

    @Override
    public Track previous() {
        if (!hasPrevious()) {
            return null;
        }

        if (shuffle && tracks.size() > 1) {
            currentIndex = randomOtherIndex();
        } else if (currentIndex <= 0) {
            currentIndex = tracks.size() - 1;
        } else {
            currentIndex--;
        }
        return tracks.get(currentIndex);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    private boolean isCurrentIndexInvalid() {
        return currentIndex < 0 || currentIndex >= tracks.size();
    }

    private int randomOtherIndex() {
        int index;
        do {
            index = random.nextInt(tracks.size());
        } while (index == currentIndex);
        return index;
    }
}
