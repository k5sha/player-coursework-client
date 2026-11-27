package ua.kpi.musicplayer.facade;

import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.memento.PlayerMemento;
import ua.kpi.musicplayer.model.Playlist;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.observer.PlayerObserver;
import ua.kpi.musicplayer.observer.PlayerState;
import ua.kpi.musicplayer.playback.PlaybackQueue;
import ua.kpi.musicplayer.storage.TrackStorage;
import ua.kpi.musicplayer.stream.RadioBrowserClient;
import ua.kpi.musicplayer.stream.RadioStation;
import ua.kpi.musicplayer.sync.PlaylistSyncClient;
import ua.kpi.musicplayer.sync.PlaylistSyncResult;
import ua.kpi.musicplayer.sync.TrackSyncClient;
import ua.kpi.musicplayer.sync.TrackSyncResult;
import ua.kpi.musicplayer.sync.TrackUploadResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class MusicPlayerFacade {

    private final SimpleAudioPlayer player = new SimpleAudioPlayer();
    private final PlaybackQueue queue = new PlaybackQueue();
    private final TrackStorage storage = new TrackStorage();
    private final RadioBrowserClient radioBrowserClient = new RadioBrowserClient();
    private final TrackSyncClient syncClient = new TrackSyncClient();
    private final PlaylistSyncClient playlistSyncClient = new PlaylistSyncClient();
    private final List<PlayerObserver> observers = new CopyOnWriteArrayList<>();

    private volatile String currentStreamUrl;
    private Consumer<Boolean> onTrackFinished;
    private long activePlaylistId = -1;
    private long defaultPlaylistId = -1;

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
        long playlistId = ensureActivePlaylist();
        List<Track> savedTracks = storage.loadTracks(playlistId);
        queue.addTracks(savedTracks);
        notifyObservers();
        return savedTracks;
    }

    public List<Playlist> loadPlaylists() {
        return storage.loadPlaylists();
    }

    public long getActivePlaylistId() {
        return ensureActivePlaylist();
    }

    public long getDefaultPlaylistId() {
        if (defaultPlaylistId <= 0) {
            defaultPlaylistId = storage.getDefaultPlaylistId();
        }
        return defaultPlaylistId;
    }

    public boolean isDefaultPlaylist(long playlistId) {
        return playlistId == getDefaultPlaylistId();
    }

    public Playlist createPlaylist(String name) {
        Playlist playlist = storage.createPlaylist(name);
        notifyObservers();
        return playlist;
    }

    public void deletePlaylist(long playlistId) {
        if (isDefaultPlaylist(playlistId)) {
            throw new IllegalStateException("Базовий плейлист не можна видалити");
        }

        List<Playlist> playlists = storage.loadPlaylists();
        if (playlists.size() <= 1) {
            throw new IllegalStateException("Не можна видалити єдиний плейлист");
        }

        storage.deletePlaylist(playlistId);

        List<Playlist> remaining = storage.loadPlaylists();
        if (activePlaylistId == playlistId && !remaining.isEmpty()) {
            switchPlaylist(remaining.get(0).getId());
        } else {
            notifyObservers();
        }
    }

    public void switchPlaylist(long playlistId) {
        if (playlistId <= 0) {
            return;
        }

        List<Track> playlistTracks = storage.loadTracks(playlistId);
        activePlaylistId = playlistId;
        currentStreamUrl = null;
        player.stop();
        queue.clear();
        queue.addTracks(playlistTracks);
        notifyObservers();
    }

    private long ensureActivePlaylist() {
        if (activePlaylistId > 0) {
            return activePlaylistId;
        }

        activePlaylistId = storage.getDefaultPlaylistId();
        return activePlaylistId;
    }

    public void addTracksAndSave(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }

        queue.addTracks(tracks);
        long defaultId = getDefaultPlaylistId();
        storage.addTracksToPlaylist(defaultId, tracks);
        if (ensureActivePlaylist() != defaultId) {
            storage.addTracksToPlaylist(activePlaylistId, tracks);
        }
        notifyObservers();
    }

    public void addTracksToPlaylist(long playlistId, List<Track> tracks) {
        if (playlistId <= 0 || tracks == null || tracks.isEmpty()) {
            return;
        }

        storage.addTracksToPlaylist(playlistId, tracks);
        notifyObservers();
    }

    public void moveTracksToPlaylist(long targetPlaylistId, List<Track> tracks) {
        if (targetPlaylistId <= 0 || tracks == null || tracks.isEmpty()) {
            return;
        }

        long sourcePlaylistId = ensureActivePlaylist();
        if (targetPlaylistId == sourcePlaylistId) {
            return;
        }

        storage.addTracksToPlaylist(targetPlaylistId, tracks);
        if (sourcePlaylistId != getDefaultPlaylistId()) {
            boolean currentRemoved = tracks.contains(queue.getCurrentTrack());
            storage.removeTracksFromPlaylist(sourcePlaylistId, tracks);
            queue.removeTracks(tracks);
            if (currentRemoved) {
                currentStreamUrl = null;
                player.stop();
            }
        }
        notifyObservers();
    }

    public void clearQueueAndSave() {
        boolean streamPlaying = currentStreamUrl != null;
        queue.clear();
        if (!streamPlaying) {
            player.stop();
        }
        storage.saveTracks(ensureActivePlaylist(), queue.getTracks());
        notifyObservers();
    }

    public Track removeTrackAt(int index) {
        boolean removeCurrent = index == queue.getCurrentIndex();
        Track removed = queue.removeAt(index);
        if (removed == null) {
            return null;
        }

        if (removeCurrent) {
            currentStreamUrl = null;
            player.stop();
        }

        storage.saveTracks(ensureActivePlaylist(), queue.getTracks());
        deleteRemoteTrack(removed);
        notifyObservers();
        return removed;
    }

    private void deleteRemoteTrack(Track track) {
        if (track == null || track.getServerId() <= 0) {
            return;
        }

        long serverId = track.getServerId();
        CompletableFuture.runAsync(() -> {
            try {
                syncClient.deleteTrack(serverId);
            } catch (RuntimeException ignored) {
                // Сервер недоступний або трек уже видалено — локальне видалення вже виконано
            }
        });
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
        prepareTrackFile(track);
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

    /**
     * Синхронізує плейлисти з сервером. Якщо сервер недоступний, повертає
     * результат з ознакою недоступності, не порушуючи локальну роботу.
     */
    public PlaylistSyncResult synchronizePlaylists() {
        try {
            List<PlaylistSyncClient.RemotePlaylist> remotePlaylists =
                    playlistSyncClient.fetchPlaylists();
            List<Playlist> localPlaylists = storage.loadPlaylists();

            int uploaded = 0;
            for (Playlist playlist : localPlaylists) {
                playlistSyncClient.uploadPlaylist(playlist.getName(), locationsOf(playlist.getId()));
                uploaded++;
            }

            int downloaded = 0;
            for (PlaylistSyncClient.RemotePlaylist remote : remotePlaylists) {
                Playlist local = findPlaylistByName(localPlaylists, remote.getName());
                if (local == null) {
                    local = storage.createPlaylist(remote.getName());
                    localPlaylists.add(local);
                    downloaded++;
                }

                List<String> knownLocations = locationsOf(local.getId());
                List<Track> missingTracks = new ArrayList<>();
                for (String location : remote.getLocations()) {
                    if (location != null && !location.isBlank()
                            && !knownLocations.contains(location)) {
                        missingTracks.add(new Track(null, null, 0, location));
                        knownLocations.add(location);
                    }
                }
                if (!missingTracks.isEmpty()) {
                    storage.addTracksToPlaylist(local.getId(), missingTracks);
                }
            }

            return new PlaylistSyncResult(uploaded, downloaded, true);
        } catch (RuntimeException e) {
            return PlaylistSyncResult.unavailable();
        }
    }

    private List<String> locationsOf(long playlistId) {
        List<String> locations = new ArrayList<>();
        for (Track track : storage.loadTracks(playlistId)) {
            String location = track.getLocation();
            if (location != null && !location.isBlank() && !locations.contains(location)) {
                locations.add(location);
            }
        }
        return locations;
    }

    private static Playlist findPlaylistByName(List<Playlist> playlists, String name) {
        for (Playlist playlist : playlists) {
            if (playlist.getName().equals(name)) {
                return playlist;
            }
        }
        return null;
    }

    private static Track findMatchingTrack(List<Track> tracks, Track track) {
        if (track.getServerId() > 0) {
            for (Track candidate : tracks) {
                if (candidate.getServerId() == track.getServerId()) {
                    return candidate;
                }
            }
        }

        int index = tracks.indexOf(track);
        return index >= 0 ? tracks.get(index) : null;
    }

    public TrackSyncResult synchronizeTracks() {
        List<Track> remoteTracks = syncClient.fetchTracks();
        List<Track> localTracks = storage.loadTracks(getDefaultPlaylistId());

        Set<Long> remoteServerIds = new HashSet<>();
        for (Track remote : remoteTracks) {
            if (remote.getServerId() > 0) {
                remoteServerIds.add(remote.getServerId());
            }
        }

        // Якщо локальний трек посилається на id, якого вже немає на сервері,
        // знімаємо позначку, щоб UI не показував хибно «На сервері».
        int cleared = 0;
        Set<Track> clearedTracks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Track track : localTracks) {
            if (track.getServerId() > 0 && !remoteServerIds.contains(track.getServerId())) {
                track.setServerId(0);
                storage.clearTrackServerId(track.getLocation());
                clearedTracks.add(track);
                cleared++;
            }
        }

        // Синхронізація лише додає дані: локальні треки надсилаються, якщо їх немає
        // на сервері, а серверні треки ніколи не видаляються. Тому видалення на
        // іншому клієнті не затирає треки цього клієнта.
        int uploaded = 0;
        for (Track track : localTracks) {
            if (clearedTracks.contains(track)) {
                continue;
            }

            Track remote = findMatchingTrack(remoteTracks, track);
            if (remote == null) {
                syncClient.uploadTrack(track);
                uploaded++;
                storage.updateTrackServerId(track.getLocation(), track.getServerId());
            } else {
                track.setServerId(remote.getServerId());
                track.setFileName(remote.getFileName());
                storage.updateTrackServerId(track.getLocation(), track.getServerId());
                prepareTrackFile(track);
            }
        }

        List<Track> downloadedTracks = new ArrayList<>();
        for (Track track : remoteTracks) {
            if (findMatchingTrack(localTracks, track) == null
                    && !downloadedTracks.contains(track)) {
                prepareTrackFile(track);
                downloadedTracks.add(track);
            }
        }

        if (!downloadedTracks.isEmpty()) {
            addTracksAndSave(downloadedTracks);
        }
        applyServerIds(localTracks);

        return new TrackSyncResult(uploaded, downloadedTracks.size(), cleared);
    }

    /**
     * Завантажує на сервер усі треки поточного списку, які ще не мають serverId.
     * Помилка окремого треку не зупиняє завантаження решти.
     */
    public TrackUploadResult uploadUnsyncedTracks() {
        List<Track> tracks = new ArrayList<>(queue.getTracks());
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;

        for (Track track : tracks) {
            if (track.getServerId() > 0) {
                skipped++;
                continue;
            }

            try {
                uploadTrackFile(track);
                uploaded++;
            } catch (RuntimeException e) {
                failed++;
            }
        }

        return new TrackUploadResult(uploaded, skipped, failed);
    }

    /**
     * Якщо локального файлу немає, намагається скачати його з сервера в кеш.
     * Відсутність сервера не перериває роботу — трек лишається зі старим шляхом.
     */
    private boolean prepareTrackFile(Track track) {
        String location = track.getLocation();
        Path localFile = localPath(location);
        if (localFile != null && Files.isRegularFile(localFile)) {
            return false;
        }
        if (track.getServerId() <= 0) {
            return false;
        }

        try {
            Path downloaded = syncClient.downloadTrackFile(
                    track.getServerId(), track.getFileName());
            track.setLocation(downloaded.toString());
            if (location != null && !location.isBlank()) {
                storage.updateTrackLocation(location, downloaded.toString());
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public long uploadTrackFile(Track track) {
        if (track == null) {
            throw new IllegalArgumentException("Трек не вибрано");
        }

        prepareTrackFile(track);
        Path file = localPath(track.getLocation());
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalStateException("Локальний файл треку не знайдено");
        }

        int durationSeconds = (int) Math.max(0, Math.round(track.getDurationMs() / 1000.0));
        long serverId = syncClient.uploadTrackFile(
                file, track.getTitle(), track.getArtist(), durationSeconds);
        if (serverId > 0) {
            track.setServerId(serverId);
            storage.updateTrackServerId(track.getLocation(), serverId);
        }
        return serverId;
    }

    public void deleteTrackFromServer(Track track) {
        if (track == null || track.getServerId() <= 0) {
            throw new IllegalArgumentException("Трек не завантажено на сервер");
        }

        syncClient.deleteTrack(track.getServerId());
        track.setServerId(0);
        storage.clearTrackServerId(track.getLocation());
    }

    private static Path localPath(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            return Path.of(location);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void applyServerIds(List<Track> tracksWithIds) {
        for (Track queued : queue.getTracks()) {
            int index = tracksWithIds.indexOf(queued);
            if (index >= 0) {
                Track source = tracksWithIds.get(index);
                queued.setServerId(source.getServerId());
                queued.setFileName(source.getFileName());
                queued.setLocation(source.getLocation());
            }
        }
    }
}
