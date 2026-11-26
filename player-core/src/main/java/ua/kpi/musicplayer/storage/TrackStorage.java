package ua.kpi.musicplayer.storage;

import ua.kpi.musicplayer.model.Playlist;
import ua.kpi.musicplayer.model.Track;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class TrackStorage {

    public static final String DEFAULT_PLAYLIST_NAME = "Базовий плейлист";

    private static final String DEFAULT_FILE_NAME = "musicplayer.db";

    private final Path databasePath;

    public TrackStorage() {
        this(Path.of(System.getProperty("user.home"), DEFAULT_FILE_NAME));
    }

    public TrackStorage(Path databasePath) {
        this.databasePath = databasePath;
    }

    public List<Playlist> loadPlaylists() {
        List<Playlist> playlists = new ArrayList<>();
        String sql = "SELECT id, name FROM playlists ORDER BY position, id";

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                playlists.add(new Playlist(resultSet.getLong("id"), resultSet.getString("name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося завантажити плейлисти з бази даних", e);
        }

        return playlists;
    }

    public Playlist createPlaylist(String name) {
        String playlistName = name == null ? "" : name.trim();
        if (playlistName.isEmpty()) {
            throw new IllegalArgumentException("Назва плейлиста не може бути порожньою");
        }

        try (Connection connection = connect()) {
            if (playlistExists(connection, playlistName)) {
                throw new IllegalStateException("Плейлист з такою назвою вже існує");
            }

            int position = nextPlaylistPosition(connection);
            String sql = "INSERT INTO playlists (name, position) VALUES (?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, playlistName);
                insert.setInt(2, position);
                insert.executeUpdate();

                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new Playlist(keys.getLong(1), playlistName);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося створити плейлист", e);
        }
    }

    public void deletePlaylist(long playlistId) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement deleteLinks = connection.prepareStatement(
                        "DELETE FROM playlist_tracks WHERE playlist_id = ?")) {
                    deleteLinks.setLong(1, playlistId);
                    deleteLinks.executeUpdate();
                }

                try (PreparedStatement deletePlaylist = connection.prepareStatement(
                        "DELETE FROM playlists WHERE id = ?")) {
                    deletePlaylist.setLong(1, playlistId);
                    deletePlaylist.executeUpdate();
                }

                deleteUnusedTracks(connection);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося видалити плейлист", e);
        }
    }

    public List<Track> loadTracks(long playlistId) {
        List<Track> tracks = new ArrayList<>();
        String sql = "SELECT tracks.title, tracks.artist, tracks.album, tracks.duration_ms, "
                + "tracks.location FROM tracks "
                + "JOIN playlist_tracks ON playlist_tracks.track_id = tracks.id "
                + "WHERE playlist_tracks.playlist_id = ? ORDER BY playlist_tracks.position";

        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setLong(1, playlistId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    Track track = new Track();
                    track.setTitle(resultSet.getString("title"));
                    track.setArtist(resultSet.getString("artist"));
                    track.setAlbum(resultSet.getString("album"));
                    track.setDurationMs(resultSet.getLong("duration_ms"));
                    track.setLocation(resultSet.getString("location"));
                    tracks.add(track);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося завантажити треки з бази даних", e);
        }

        return tracks;
    }

    public void saveTracks(long playlistId, List<Track> tracks) {
        if (tracks == null) {
            return;
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement deleteLinks = connection.prepareStatement(
                        "DELETE FROM playlist_tracks WHERE playlist_id = ?")) {
                    deleteLinks.setLong(1, playlistId);
                    deleteLinks.executeUpdate();
                }

                try (PreparedStatement findTrack = connection.prepareStatement(
                        "SELECT id FROM tracks WHERE location = ? LIMIT 1");
                     PreparedStatement insertTrack = connection.prepareStatement(
                             "INSERT INTO tracks (title, artist, album, duration_ms, location, position) "
                                     + "VALUES (?, ?, ?, ?, ?, ?)",
                             Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement insertLink = connection.prepareStatement(
                             "INSERT OR IGNORE INTO playlist_tracks (playlist_id, track_id, position) "
                                     + "VALUES (?, ?, ?)")) {
                    for (int i = 0; i < tracks.size(); i++) {
                        Track track = tracks.get(i);
                        long trackId = findTrackId(findTrack, track);
                        if (trackId < 0) {
                            trackId = insertTrack(insertTrack, track, i);
                        }

                        insertLink.setLong(1, playlistId);
                        insertLink.setLong(2, trackId);
                        insertLink.setInt(3, i);
                        insertLink.executeUpdate();
                    }
                }

                deleteUnusedTracks(connection);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося зберегти треки в базу даних", e);
        }
    }

    private long findTrackId(PreparedStatement findTrack, Track track) throws SQLException {
        findTrack.setString(1, track.getLocation());
        try (ResultSet resultSet = findTrack.executeQuery()) {
            return resultSet.next() ? resultSet.getLong("id") : -1;
        }
    }

    private long insertTrack(PreparedStatement insertTrack, Track track, int position)
            throws SQLException {
        insertTrack.setString(1, track.getTitle());
        insertTrack.setString(2, track.getArtist());
        insertTrack.setString(3, track.getAlbum());
        insertTrack.setLong(4, track.getDurationMs());
        insertTrack.setString(5, track.getLocation());
        insertTrack.setInt(6, position);
        insertTrack.executeUpdate();

        try (ResultSet keys = insertTrack.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private boolean playlistExists(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM playlists WHERE name = ?")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int nextPlaylistPosition(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(position), -1) + 1 FROM playlists")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void deleteUnusedTracks(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM tracks WHERE id NOT IN (SELECT track_id FROM playlist_tracks)");
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA foreign_keys = ON");
        }
        createTables(connection);
        ensureDefaultPlaylist(connection);
        return connection;
    }

    private void createTables(Connection connection) throws SQLException {
        String tracksSql = """
                CREATE TABLE IF NOT EXISTS tracks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT,
                    artist TEXT,
                    album TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    location TEXT NOT NULL,
                    position INTEGER NOT NULL
                )
                """;
        String playlistsSql = """
                CREATE TABLE IF NOT EXISTS playlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0
                )
                """;
        String playlistTracksSql = """
                CREATE TABLE IF NOT EXISTS playlist_tracks (
                    playlist_id INTEGER NOT NULL,
                    track_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY (playlist_id, track_id),
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
                )
                """;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(tracksSql);
            statement.executeUpdate(playlistsSql);
            statement.executeUpdate(playlistTracksSql);
        }
    }

    private void ensureDefaultPlaylist(Connection connection) throws SQLException {
        long defaultPlaylistId;

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id FROM playlists ORDER BY position, id LIMIT 1")) {
            if (resultSet.next()) {
                defaultPlaylistId = resultSet.getLong("id");
            } else {
                defaultPlaylistId = insertDefaultPlaylist(connection);
            }
        }

        String migrateSql = "INSERT OR IGNORE INTO playlist_tracks (playlist_id, track_id, position) "
                + "SELECT ?, id, position FROM tracks "
                + "WHERE id NOT IN (SELECT track_id FROM playlist_tracks)";
        try (PreparedStatement migrate = connection.prepareStatement(migrateSql)) {
            migrate.setLong(1, defaultPlaylistId);
            migrate.executeUpdate();
        }
    }

    private long insertDefaultPlaylist(Connection connection) throws SQLException {
        String sql = "INSERT INTO playlists (name, position) VALUES (?, 0)";
        try (PreparedStatement insert = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, DEFAULT_PLAYLIST_NAME);
            insert.executeUpdate();

            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
