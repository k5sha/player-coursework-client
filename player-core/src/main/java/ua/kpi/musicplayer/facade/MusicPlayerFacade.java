package ua.kpi.musicplayer.facade;

import ua.kpi.musicplayer.audio.AudioMetadataReader;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private final Map<String, String> serverLocationByLocal = new HashMap<>();

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

        long defaultId = getDefaultPlaylistId();
        long activeId = ensureActivePlaylist();

        List<Track> candidates = new ArrayList<>(queue.getTracks());
        candidates.addAll(storage.loadTracks(defaultId));
        if (activeId != defaultId) {
            candidates.addAll(storage.loadTracks(activeId));
        }

        List<Track> pending = new ArrayList<>();
        for (Track track : tracks) {
            Track existing = findExistingTrack(candidates, track);
            if (existing == null) {
                pending.add(track);
                candidates.add(track);
                continue;
            }

            updateExistingTrack(existing, track);
            if (!containsSame(pending, existing) && !queue.getTracks().contains(existing)) {
                pending.add(existing);
            }
        }

        if (!pending.isEmpty()) {
            queue.addTracks(pending);
            storage.addTracksToPlaylist(defaultId, pending);
            if (activeId != defaultId) {
                storage.addTracksToPlaylist(activeId, pending);
            }
        }
        notifyObservers();
    }

    private static Track findExistingTrack(List<Track> candidates, Track track) {
        for (Track candidate : candidates) {
            if (isSameTrack(candidate, track)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsSame(List<Track> tracks, Track target) {
        for (Track track : tracks) {
            if (track == target) {
                return true;
            }
        }
        return false;
    }

    private void updateExistingTrack(Track existing, Track track) {
        String oldLocation = existing.getLocation();
        String newLocation = track.getLocation();

        existing.setLocation(newLocation);
        existing.setLocalOnly(false);
        if (hasText(track.getTitle())) {
            existing.setTitle(track.getTitle());
        }
        if (hasText(track.getArtist())) {
            existing.setArtist(track.getArtist());
        }
        if (hasText(track.getAlbum())) {
            existing.setAlbum(track.getAlbum());
        }
        if (track.getDurationMs() > 0) {
            existing.setDurationMs(track.getDurationMs());
        }
        if (track.getServerId() > 0) {
            existing.setServerId(track.getServerId());
        }

        for (Track queued : queue.getTracks()) {
            if (queued == existing || oldLocation == null
                    || !oldLocation.equals(queued.getLocation())) {
                continue;
            }
            queued.setLocation(newLocation);
            queued.setLocalOnly(false);
            queued.setServerId(existing.getServerId());
        }

        if (oldLocation != null && !oldLocation.equals(newLocation)) {
            storage.updateTrackLocation(oldLocation, newLocation);
        }
        storage.updateTrackLocalOnly(newLocation, false);
        if (existing.getServerId() > 0) {
            storage.updateTrackServerId(newLocation, existing.getServerId());
        }
        storage.updateTrackMetadata(existing);
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
        serverLocationByLocal.remove(removed.getLocation());
        deleteRemoteTrack(removed);
        deleteUnusedCacheFile(removed);
        notifyObservers();
        return removed;
    }

    private void deleteUnusedCacheFile(Track track) {
        String location = track.getLocation();
        if (!TrackSyncClient.isCacheFile(location) || storage.isTrackLocationUsed(location)) {
            return;
        }

        Path file = localPath(location);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Файл зайнятий або вже видалений — кеш лишається на диску
        }
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


    public PlaylistSyncResult synchronizePlaylists() {
        try {
            List<PlaylistSyncClient.RemotePlaylist> remotePlaylists =
                    playlistSyncClient.fetchPlaylists();
            List<Playlist> localPlaylists = storage.loadPlaylists();

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
                for (String remoteLocation : remote.getLocations()) {
                    String location = localLocationOf(remoteLocation, knownLocations);
                    if (location == null || location.isBlank()
                            || knownLocations.contains(location)) {
                        continue;
                    }
                    if (!serverLocationByLocal.containsValue(remoteLocation)
                            && !isLocalFile(location)) {
                        continue;
                    }
                    missingTracks.add(new Track(null, null, 0, location));
                    knownLocations.add(location);
                }
                if (!missingTracks.isEmpty()) {
                    storage.addTracksToPlaylist(local.getId(), missingTracks);
                }
            }

            int uploaded = 0;
            for (Playlist playlist : localPlaylists) {
                List<String> locations = serverLocationsOf(playlist.getId());
                if (!sameLocations(remoteLocationsOf(remotePlaylists, playlist.getName()), locations)) {
                    playlistSyncClient.uploadPlaylist(playlist.getName(), locations);
                    uploaded++;
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

    private List<String> serverLocationsOf(long playlistId) {
        List<String> locations = new ArrayList<>();
        for (String localLocation : locationsOf(playlistId)) {
            String serverLocation = serverLocationByLocal.getOrDefault(localLocation, localLocation);
            if (!locations.contains(serverLocation)) {
                locations.add(serverLocation);
            }
        }
        return locations;
    }

    private String localLocationOf(String serverLocation, List<String> preferredLocations) {
        if (preferredLocations.contains(serverLocation)) {
            return serverLocation;
        }

        String found = null;
        for (Map.Entry<String, String> entry : serverLocationByLocal.entrySet()) {
            if (!entry.getValue().equals(serverLocation)) {
                continue;
            }
            if (preferredLocations.contains(entry.getKey())) {
                return entry.getKey();
            }
            if (found == null) {
                found = entry.getKey();
            }
        }
        return found == null ? serverLocation : found;
    }

    private void rememberServerLocation(Track track, String serverLocation) {
        if (track == null || serverLocation == null || serverLocation.isBlank()
                || track.getLocation() == null || track.getLocation().isBlank()) {
            return;
        }
        serverLocationByLocal.put(track.getLocation(), serverLocation);
    }

    private static List<String> remoteLocationsOf(
            List<PlaylistSyncClient.RemotePlaylist> playlists, String name) {
        for (PlaylistSyncClient.RemotePlaylist remote : playlists) {
            if (remote.getName().equals(name)) {
                return remote.getLocations();
            }
        }
        return null;
    }

    private static boolean sameLocations(List<String> serverLocations, List<String> localLocations) {
        if (serverLocations == null) {
            return false;
        }
        return new HashSet<>(serverLocations).equals(new HashSet<>(localLocations));
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
        if (index >= 0) {
            return tracks.get(index);
        }

        String fileName = normalizedFileNameOf(track);
        if (!fileName.isEmpty()) {
            for (Track candidate : tracks) {
                if (fileName.equals(normalizedFileNameOf(candidate))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String normalizedFileNameOf(Track track) {
        if (track == null) {
            return "";
        }
        if (track.getFileName() != null && !track.getFileName().isBlank()) {
            return Track.normalizeFileName(track.getFileName());
        }
        return Track.normalizeFileName(track.getLocation());
    }

    private static boolean isSameTrack(Track first, Track second) {
        if (first == null || second == null) {
            return false;
        }

        String firstLocation = first.getLocation();
        if (hasText(firstLocation) && firstLocation.equals(second.getLocation())) {
            return true;
        }

        String firstFileName = normalizedFileNameOf(first);
        String secondFileName = normalizedFileNameOf(second);
        if (!firstFileName.isEmpty() && firstFileName.equalsIgnoreCase(secondFileName)) {
            return true;
        }

        return hasText(first.getTitle())
                && hasText(second.getTitle())
                && first.getDurationMs() > 0
                && second.getDurationMs() > 0
                && first.getTitle().trim().equalsIgnoreCase(second.getTitle().trim())
                && artistOf(first).equalsIgnoreCase(artistOf(second))
                && durationSecondsOf(first) == durationSecondsOf(second);
    }

    private static String artistOf(Track track) {
        return track.getArtist() == null ? "" : track.getArtist().trim();
    }

    private static long durationSecondsOf(Track track) {
        return Math.max(0, Math.round(track.getDurationMs() / 1000.0));
    }

    private static boolean needsMetadata(Track track) {
        if (track == null) {
            return false;
        }
        return !hasText(track.getArtist())
                || isUnknownArtist(track.getArtist())
                || !hasText(track.getTitle())
                || Track.hasTimestampPrefix(track.getTitle())
                || track.getDurationMs() <= 0;
    }

    private static boolean isUnknownArtist(String artist) {
        if (!hasText(artist)) {
            return true;
        }
        String value = artist.trim();
        return value.equalsIgnoreCase("unknown artist")
                || value.equalsIgnoreCase("unknown")
                || value.equalsIgnoreCase("невідомий виконавець");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void applyMetadataFromFile(Track track) {
        if (track == null) {
            return;
        }
        Path file = localPath(track.getLocation());
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }

        Track metadata = AudioMetadataReader.read(file.toFile());
        if (hasText(metadata.getTitle())) {
            track.setTitle(metadata.getTitle());
        }
        if (hasText(metadata.getArtist())) {
            track.setArtist(metadata.getArtist());
        }
        if (hasText(metadata.getAlbum())) {
            track.setAlbum(metadata.getAlbum());
        }
        if (metadata.getDurationMs() > 0) {
            track.setDurationMs(metadata.getDurationMs());
        }

        if (Track.hasTimestampPrefix(track.getTitle())) {
            track.setTitle(Track.normalizeFileName(track.getTitle()));
        }
    }

    private static void applyFallbackTitle(Track track) {
        if (track == null || hasText(track.getTitle())) {
            return;
        }

        String name = track.getFileName();
        if (!hasText(name)) {
            name = track.getLocation();
        }
        String title = Track.normalizeFileName(name);
        if (!title.isEmpty()) {
            track.setTitle(title);
        }
    }

    private List<Track> removeDuplicateLocalTracks(List<Track> localTracks) {
        List<Track> remaining = new ArrayList<>();
        for (Track track : localTracks) {
            if (track.getServerId() <= 0) {
                Track server = findServerDuplicate(track, localTracks);
                if (server != null && mergeLocalDuplicate(track, server)) {
                    continue;
                }
            }
            remaining.add(track);
        }
        return remaining;
    }

    private static Track findServerDuplicate(Track track, List<Track> tracks) {
        for (Track candidate : tracks) {
            if (candidate != track && candidate.getServerId() > 0
                    && isSameTrack(track, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean mergeLocalDuplicate(Track local, Track server) {
        String localLocation = local.getLocation();
        String serverLocation = server.getLocation();
        if (!hasText(localLocation) || localLocation.equals(serverLocation)) {
            return false;
        }

        boolean localQueued = queue.getTracks().contains(local);
        boolean serverQueued = queue.getTracks().contains(server);
        queue.removeTracks(List.of(local));
        storage.removeTrack(localLocation);
        storage.updateTrackLocation(serverLocation, localLocation);
        server.setLocation(localLocation);
        for (Track queued : queue.getTracks()) {
            if (queued != server && serverLocation != null
                    && serverLocation.equals(queued.getLocation())) {
                queued.setLocation(localLocation);
            }
        }
        if (localQueued && !serverQueued) {
            queue.addTracks(List.of(server));
        }
        return true;
    }

    public TrackSyncResult synchronizeTracks() {
        List<Track> remoteTracks = syncClient.fetchTracks();
        List<Track> localTracks = removeDuplicateLocalTracks(
                storage.loadTracks(getDefaultPlaylistId()));
        serverLocationByLocal.clear();

        Map<String, Track> queuedByLocation = new HashMap<>();
        for (Track queued : queue.getTracks()) {
            if (queued.getLocation() != null) {
                queuedByLocation.put(queued.getLocation(), queued);
            }
        }

        Set<Long> remoteServerIds = new HashSet<>();
        for (Track remote : remoteTracks) {
            if (remote.getServerId() > 0) {
                remoteServerIds.add(remote.getServerId());
            }
        }

        int cleared = 0;
        for (Track track : localTracks) {
            if (track.getServerId() > 0 && !remoteServerIds.contains(track.getServerId())) {
                track.setServerId(0);
                track.setLocalOnly(true);
                storage.clearTrackServerId(track.getLocation());
                storage.updateTrackLocalOnly(track.getLocation(), true);
                copyToQueued(queuedByLocation, track.getLocation(), track);
                cleared++;
            }
        }
        int uploaded = 0;
        int failed = 0;
        for (Track track : localTracks) {
            Track remote = findMatchingTrack(remoteTracks, track);
            if (remote == null) {
                if (track.isLocalOnly()) {
                    continue;
                }
                String localLocation = track.getLocation();
                try {
                    uploadTrackFile(track);
                    uploaded++;
                    copyToQueued(queuedByLocation, localLocation, track);
                } catch (RuntimeException e) {
                    failed++;
                }
                continue;
            }

            String localLocation = track.getLocation();
            String serverLocation = remote.getLocation();
            track.setServerId(remote.getServerId());
            if (hasText(remote.getFileName())) {
                track.setFileName(remote.getFileName());
            }
            storage.updateTrackServerId(track.getLocation(), track.getServerId());
            prepareTrackFile(track);

            if (needsMetadata(remote) || needsMetadata(track)) {
                applyMetadataFromFile(track);
            }
            storage.updateTrackMetadata(track);

            rememberServerLocation(track, serverLocation);
            copyToQueued(queuedByLocation, localLocation, track);
        }

        List<Track> downloadedTracks = new ArrayList<>();
        Set<Long> handledRemoteIds = new HashSet<>();
        for (Track remote : remoteTracks) {
            if (remote.getServerId() <= 0 || !handledRemoteIds.add(remote.getServerId())
                    || findMatchingTrack(localTracks, remote) != null) {
                continue;
            }

            String serverLocation = remote.getLocation();
            prepareTrackFile(remote);
            if (!hasLocalFile(remote)) {
                continue;
            }

            if (needsMetadata(remote)) {
                applyMetadataFromFile(remote);
            }
            applyFallbackTitle(remote);
            rememberServerLocation(remote, serverLocation);
            downloadedTracks.add(remote);
        }

        if (!downloadedTracks.isEmpty()) {
            addTracksAndSave(downloadedTracks);
        }

        return new TrackSyncResult(uploaded, downloadedTracks.size(), cleared, failed);
    }

    private static void copyToQueued(Map<String, Track> queuedByLocation,
                                     String location, Track source) {
        if (location == null) {
            return;
        }
        Track queued = queuedByLocation.get(location);
        if (queued == null) {
            return;
        }
        queued.setServerId(source.getServerId());
        queued.setLocalOnly(source.isLocalOnly());
        queued.setFileName(source.getFileName());
        queued.setLocation(source.getLocation());
        queued.setTitle(source.getTitle());
        queued.setArtist(source.getArtist());
        queued.setAlbum(source.getAlbum());
        queued.setDurationMs(source.getDurationMs());
    }

    public TrackUploadResult uploadUnsyncedTracks() {
        List<Track> tracks = new ArrayList<>(queue.getTracks());
        boolean serverAvailable = true;
        int cleared = 0;
        try {
            cleared = clearMissingServerIds(tracks);
        } catch (RuntimeException e) {
            // Сервер недоступний — позначки не звірено, завантаження неможливе
            serverAvailable = false;
        }

        if (!serverAvailable) {
            return new TrackUploadResult(0, 0, 0, 0, false);
        }

        int uploaded = 0;
        int skipped = 0;
        int failed = 0;

        for (Track track : tracks) {
            if (track.getServerId() > 0 || track.isLocalOnly()) {
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

        return new TrackUploadResult(uploaded, skipped, cleared, failed, serverAvailable);
    }

    private int clearMissingServerIds(List<Track> tracks) {
        Set<Long> remoteServerIds = new HashSet<>();
        for (Track remote : syncClient.fetchTracks()) {
            if (remote.getServerId() > 0) {
                remoteServerIds.add(remote.getServerId());
            }
        }

        int cleared = 0;
        Set<String> clearedLocations = new HashSet<>();
        for (Track track : tracks) {
            if (track.getServerId() <= 0 || remoteServerIds.contains(track.getServerId())) {
                continue;
            }

            track.setServerId(0);
            String location = track.getLocation();
            if (location != null && !location.isBlank() && clearedLocations.add(location)) {
                storage.clearTrackServerId(location);
            }
            cleared++;
        }
        return cleared;
    }

    private void prepareTrackFile(Track track) {
        String location = track.getLocation();
        if (hasLocalFile(track)) {
            return;
        }
        if (track.getServerId() <= 0) {
            return;
        }

        String nameHint = track.getFileName();
        if (nameHint == null || nameHint.isBlank()) {
            nameHint = location;
        }

        try {
            Path downloaded = syncClient.downloadTrackFile(track.getServerId(), nameHint);
            track.setLocation(downloaded.toString());
            if (location != null && !location.isBlank()) {
                storage.updateTrackLocation(location, downloaded.toString());
            }
        } catch (RuntimeException ignored) {
            // Сервер недоступний — трек лишається зі старим шляхом
        }
    }

    private static boolean hasLocalFile(Track track) {
        return track != null && isLocalFile(track.getLocation());
    }

    private static boolean isLocalFile(String location) {
        Path file = localPath(location);
        return file != null && Files.isRegularFile(file);
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
        Track uploaded = syncClient.uploadTrackFile(
                file, track.getTitle(), track.getArtist(), durationSeconds);
        track.setServerId(uploaded.getServerId());
        track.setFileName(uploaded.getFileName());
        track.setLocalOnly(false);
        storage.updateTrackServerId(track.getLocation(), uploaded.getServerId());
        storage.updateTrackLocalOnly(track.getLocation(), false);
        rememberServerLocation(track, uploaded.getLocation());
        return uploaded.getServerId();
    }

    public void deleteTrackFromServer(Track track) {
        if (track == null || track.getServerId() <= 0) {
            throw new IllegalArgumentException("Трек не завантажено на сервер");
        }

        syncClient.deleteTrack(track.getServerId());
        markTrackLocal(track);
    }

    private void markTrackLocal(Track track) {
        String location = track.getLocation();
        serverLocationByLocal.remove(location);

        track.setServerId(0);
        track.setLocalOnly(true);
        for (Track queued : queue.getTracks()) {
            if (queued != track && location != null && location.equals(queued.getLocation())) {
                queued.setServerId(0);
                queued.setLocalOnly(true);
            }
        }

        storage.clearTrackServerId(location);
        storage.updateTrackLocalOnly(location, true);
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
}
