package ua.kpi.musicplayer.playback;

import ua.kpi.musicplayer.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaybackQueue {

    private final List<Track> tracks = new ArrayList<>();
    private int currentIndex = -1;

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

    public void next() {
        if (hasNext()) {
            currentIndex++;
        }
    }

    public void previous() {
        if (hasPrevious()) {
            currentIndex--;
        }
    }

    public boolean hasNext() {
        return currentIndex >= 0 && currentIndex < tracks.size() - 1;
    }

    public boolean hasPrevious() {
        return currentIndex > 0;
    }
}
