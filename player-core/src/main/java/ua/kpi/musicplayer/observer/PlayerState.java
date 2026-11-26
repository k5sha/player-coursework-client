package ua.kpi.musicplayer.observer;

import ua.kpi.musicplayer.model.Track;

public class PlayerState {

    private final Track currentTrack;
    private final int currentIndex;
    private final boolean playing;
    private final boolean paused;
    private final String streamUrl;

    public PlayerState(Track currentTrack, int currentIndex, boolean playing,
                       boolean paused, String streamUrl) {
        this.currentTrack = currentTrack;
        this.currentIndex = currentIndex;
        this.playing = playing;
        this.paused = paused;
        this.streamUrl = streamUrl;
    }

    public Track getCurrentTrack() {
        return currentTrack;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public boolean isStream() {
        return streamUrl != null;
    }
}
