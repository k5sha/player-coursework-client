package ua.kpi.musicplayer.memento;

import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.playback.PlaybackQueue;

public final class PlayerMemento {

    private final int currentIndex;
    private final PlaybackQueue.RepeatMode repeatMode;
    private final boolean shuffle;
    private final float volume;
    private final float[] equalizerBands;
    private final double positionSeconds;

    public PlayerMemento(int currentIndex,
                         PlaybackQueue.RepeatMode repeatMode,
                         boolean shuffle,
                         float volume,
                         float[] equalizerBands,
                         double positionSeconds) {
        this.currentIndex = currentIndex;
        this.repeatMode = repeatMode == null ? PlaybackQueue.RepeatMode.OFF : repeatMode;
        this.shuffle = shuffle;
        this.volume = volume;
        this.equalizerBands = equalizerBands == null
                ? new float[Equalizer.BAND_COUNT]
                : equalizerBands.clone();
        this.positionSeconds = Math.max(0.0, positionSeconds);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public PlaybackQueue.RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public float getVolume() {
        return volume;
    }

    public float[] getEqualizerBands() {
        return equalizerBands.clone();
    }

    public double getPositionSeconds() {
        return positionSeconds;
    }
}
