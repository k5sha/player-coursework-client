package ua.kpi.musicplayer.facade;

import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.playback.PlaybackQueue;
import ua.kpi.musicplayer.storage.TrackStorage;
import ua.kpi.musicplayer.stream.RadioBrowserClient;
import ua.kpi.musicplayer.stream.RadioStation;

import java.util.List;
import java.util.function.Consumer;

public class MusicPlayerFacade {

    private final SimpleAudioPlayer player = new SimpleAudioPlayer();
    private final PlaybackQueue queue = new PlaybackQueue();
    private final TrackStorage storage = new TrackStorage();
    private final RadioBrowserClient radioBrowserClient = new RadioBrowserClient();

    private volatile String currentStreamUrl;
    private Consumer<Boolean> onTrackFinished;

    public List<Track> loadSavedTracks() {
        List<Track> savedTracks = storage.loadTracks();
        queue.addTracks(savedTracks);
        return savedTracks;
    }

    public void addTracksAndSave(List<Track> tracks) {
        queue.addTracks(tracks);
        storage.saveTracks(queue.getTracks());
    }

    public void clearQueueAndSave() {
        boolean streamPlaying = currentStreamUrl != null;
        queue.clear();
        if (!streamPlaying) {
            player.stop();
        }
        storage.saveTracks(queue.getTracks());
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
        return started;
    }

    public String getCurrentStreamUrl() {
        return currentStreamUrl;
    }

    public void togglePause() {
        player.togglePause();
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
        return queue.cycleRepeatMode();
    }

    public boolean isShuffle() {
        return queue.isShuffle();
    }

    public void toggleShuffle() {
        queue.toggleShuffle();
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

    public List<RadioStation> searchStations(String query) {
        return radioBrowserClient.search(query);
    }
}
