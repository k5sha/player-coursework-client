package ua.kpi.musicplayer.storage;

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

    private static final String DEFAULT_FILE_NAME = "musicplayer.db";

    private final Path databasePath;

    public TrackStorage() {
        this(Path.of(System.getProperty("user.home"), DEFAULT_FILE_NAME));
    }

    public TrackStorage(Path databasePath) {
        this.databasePath = databasePath;
    }

    public List<Track> loadTracks() {
        List<Track> tracks = new ArrayList<>();
        String sql = "SELECT title, artist, album, duration_ms, location FROM tracks ORDER BY position";

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                Track track = new Track();
                track.setTitle(resultSet.getString("title"));
                track.setArtist(resultSet.getString("artist"));
                track.setAlbum(resultSet.getString("album"));
                track.setDurationMs(resultSet.getLong("duration_ms"));
                track.setLocation(resultSet.getString("location"));
                tracks.add(track);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося завантажити треки з бази даних", e);
        }

        return tracks;
    }

    public void saveTracks(List<Track> tracks) {
        if (tracks == null) {
            return;
        }

        String sql = "INSERT INTO tracks (title, artist, album, duration_ms, location, position) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);

            try (Statement clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM tracks");
            }

            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (int i = 0; i < tracks.size(); i++) {
                    Track track = tracks.get(i);
                    insert.setString(1, track.getTitle());
                    insert.setString(2, track.getArtist());
                    insert.setString(3, track.getAlbum());
                    insert.setLong(4, track.getDurationMs());
                    insert.setString(5, track.getLocation());
                    insert.setInt(6, i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Не вдалося зберегти треки в базу даних", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        createTable(connection);
        return connection;
    }

    private void createTable(Connection connection) throws SQLException {
        String sql = """
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

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
