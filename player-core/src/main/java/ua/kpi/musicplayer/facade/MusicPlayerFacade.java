package ua.kpi.musicplayer.facade;

import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.memento.PlayerMemento;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.observer.PlayerObserver;
import ua.kpi.musicplayer.observer.PlayerState;
import ua.kpi.musicplayer.playback.PlaybackQueue;
import ua.kpi.musicplayer.storage.TrackStorage;
import ua.kpi.musicplayer.stream.RadioBrowserClient;
import ua.kpi.musicplayer.stream.RadioStation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class MusicPlayerFacade {

    private final SimpleAudioPlayer player = new SimpleAudioPlayer();
    private final PlaybackQueue queue = new PlaybackQueue();
    private final TrackStorage storage = new TrackStorage();
    private final RadioBrowserClient radioBrowserClient = new RadioBrowserClient();
    private final List<PlayerObserver> observers = new CopyOnWriteArrayList<>();

    private volatile String currentStreamUrl;
    private Consumer<Boolean> onTrackFinished;

    public void addObserver(PlayerObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public void removeObserver(PlayerObserver observer) {
        observers.remove(observer);
    }

    public PlayerState getState() {
        return new PlayerState(
                queue.getCurrentTrack(),
                queue.getCurrentIndex(),
                player.isPlaying(),
                player.isPaused(),
                currentStreamUrl);
    }

    private void notifyObservers() {
        if (observers.isEmpty()) {
            return;
        }

        PlayerState state = getState();
        for (PlayerObserver observer : observers) {
            observer.onPlayerStateChanged(state);
        }
    }

    public List<Track> loadSavedTracks() {
        List<Track> savedTracks = storage.loadTracks();
        queue.addTracks(savedTracks);
        notifyObservers();
        return savedTracks;
    }

    public void addTracksAndSave(List<Track> tracks) {
        queue.addTracks(tracks);
        storage.saveTracks(queue.getTracks());
        notifyObservers();
    }

    public void clearQueueAndSave() {
        boolean streamPlaying = currentStreamUrl != null;
        queue.clear();
        if (!streamPlaying) {
            player.stop();
        }
        storage.saveTracks(queue.getTracks());
        notifyObservers();
    }

    public Track playTrackAt(int index) {
        queue.select(index);
        return playCurrentTrack();
    }

    public Track playNext() {
        if (!queue.hasNext()) {
            return null;
        }
        queue.next();
        return playCurrentTrack();
    }

    public Track playPrevious() {
        if (!queue.hasPrevious()) {
            return null;
        }
        queue.previous();
        return playCurrentTrack();
    }

    private Track playCurrentTrack() {
        Track track = queue.getCurrentTrack();
        if (track == null) {
            return null;
        }
        currentStreamUrl = null;
        player.play(track);
        notifyObservers();
        return track;
    }

    public boolean playStream(String url) {
        currentStreamUrl = null;
        if (url == null || url.isBlank()) {
            return false;
        }

        String streamUrl = url.trim();
        boolean started = player.playUrl(streamUrl);
        if (started) {
            currentStreamUrl = streamUrl;
        }
        notifyObservers();
        return started;
    }

    public String getCurrentStreamUrl() {
        return currentStreamUrl;
    }

    public void togglePause() {
        player.togglePause();
        notifyObservers();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isPaused() {
        return player.isPaused();
    }

    public void stop() {
        currentStreamUrl = null;
        player.stop();
        notifyObservers();
    }

    public void setOnTrackFinished(Consumer<Boolean> onTrackFinished) {
        this.onTrackFinished = onTrackFinished;
        player.setOnTrackFinished(this::handleTrackFinished);
    }

    private void handleTrackFinished() {
        boolean streamFinished = currentStreamUrl != null;
        currentStreamUrl = null;

        if (!streamFinished) {
            try {
                playNext();
            } catch (RuntimeException ignored) {
                // Завершення все одно передаємо в UI
            }
        }

        Consumer<Boolean> callback = onTrackFinished;
        if (callback != null) {
            callback.accept(streamFinished);
        }
        notifyObservers();
    }

    public List<Track> getTracks() {
        return queue.getTracks();
    }

    public Track getCurrentTrack() {
        return queue.getCurrentTrack();
    }

    public int getCurrentIndex() {
        return queue.getCurrentIndex();
    }

    public void setVolume(float volume) {
        player.setVolume(volume);
    }

    public float getVolume() {
        return player.getVolume();
    }

    public PlaybackQueue.RepeatMode getRepeatMode() {
        return queue.getRepeatMode();
    }

    public PlaybackQueue.RepeatMode cycleRepeatMode() {
        PlaybackQueue.RepeatMode mode = queue.cycleRepeatMode();
        notifyObservers();
        return mode;
    }

    public boolean isShuffle() {
        return queue.isShuffle();
    }

    public void toggleShuffle() {
        queue.toggleShuffle();
        notifyObservers();
    }

    public void applyEqualizerPreset(Equalizer.Preset preset) {
        player.getEqualizer().applyPreset(preset);
    }

    public void resetEqualizer() {
        player.getEqualizer().reset();
    }

    public float getBandGainDb(int band) {
        return player.getEqualizer().getBandGainDb(band);
    }

    public void setBandGainDb(int band, float gainDb) {
        player.getEqualizer().setBandGainDb(band, gainDb);
    }

    public PlayerMemento createMemento() {
        float[] bands = new float[Equalizer.BAND_COUNT];
        for (int band = 0; band < bands.length; band++) {
            bands[band] = player.getEqualizer().getBandGainDb(band);
        }
        return new PlayerMemento(
                queue.getCurrentIndex(),
                queue.getRepeatMode(),
                queue.isShuffle(),
                player.getVolume(),
                bands,
                player.getPositionSeconds());
    }

    public void restore(PlayerMemento memento) {
        if (memento == null) {
            return;
        }

        queue.setCurrentIndex(memento.getCurrentIndex());
        queue.setRepeatMode(memento.getRepeatMode());
        queue.setShuffle(memento.isShuffle());
        player.setVolume(memento.getVolume());

        float[] bands = memento.getEqualizerBands();
        for (int band = 0; band < Equalizer.BAND_COUNT && band < bands.length; band++) {
            player.getEqualizer().setBandGainDb(band, bands[band]);
        }

        player.preparePosition(
                queue.getCurrentTrack(),
                queue.getCurrentIndex() == memento.getCurrentIndex()
                        ? memento.getPositionSeconds()
                        : 0);
        notifyObservers();
    }

    public double getCurrentPositionSeconds() {
        if (currentStreamUrl != null) {
            return 0;
        }
        return player.getPositionSeconds();
    }

    public double getDurationSeconds() {
        if (currentStreamUrl != null) {
            return 0;
        }

        double duration = player.getDurationSeconds();
        if (duration > 0) {
            return duration;
        }

        Track track = queue.getCurrentTrack();
        return track == null ? 0 : track.getDurationMs() / 1000.0;
    }

    public boolean canSeek() {
        return currentStreamUrl == null && player.canSeek();
    }

    public void seekToSeconds(double seconds) {
        player.seekToSeconds(seconds);
    }

    public List<RadioStation> searchStations(String query) {
        return radioBrowserClient.search(query);
    }
}
