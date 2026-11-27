package ua.kpi.musicplayer.sync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Простий клієнт синхронізації плейлистів із сервером.
 * Сервер очікується за адресою http://localhost:8080 з ресурсом /playlists.
 */
public class PlaylistSyncClient {

    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private final String apiBase;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    public PlaylistSyncClient() {
        this("http://localhost:8080");
    }

    public PlaylistSyncClient(String apiBase) {
        String base = apiBase == null || apiBase.isBlank() ? "http://localhost:8080" : apiBase.trim();
        this.apiBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public List<RemotePlaylist> fetchPlaylists() {
        HttpRequest request = requestBuilder("/playlists").GET().build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200);
        return parsePlaylists(response.body());
    }

    public void uploadPlaylist(String name, List<String> locations) {
        String json = "{\"name\":" + TrackSyncClient.quote(name)
                + ",\"locations\":" + toJsonArray(locations) + "}";
        HttpRequest request = requestBuilder("/playlists")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200, 201, 204);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Сервер недоступний за адресою " + apiBase, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запит до сервера перервано", e);
        }
    }

    private static void checkStatus(HttpResponse<String> response, int... expectedCodes) {
        for (int code : expectedCodes) {
            if (response.statusCode() == code) {
                return;
            }
        }
        throw new IllegalStateException("Сервер повернув код " + response.statusCode());
    }

    private static String toJsonArray(List<String> locations) {
        StringBuilder json = new StringBuilder("[");
        if (locations != null) {
            for (int i = 0; i < locations.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(TrackSyncClient.quote(locations.get(i)));
            }
        }
        return json.append(']').toString();
    }

    private static List<RemotePlaylist> parsePlaylists(String json) {
        List<RemotePlaylist> playlists = new ArrayList<>();
        for (String object : TrackSyncClient.splitObjects(json)) {
            String name = TrackSyncClient.readString(object, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            playlists.add(new RemotePlaylist(name.trim(), readStringArray(object, "locations")));
        }
        return playlists;
    }

    private static List<String> readStringArray(String object, String key) {
        List<String> values = new ArrayList<>();
        int start = TrackSyncClient.valueStart(object, key);
        if (start < 0 || object.charAt(start) != '[') {
            return values;
        }

        StringBuilder raw = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start + 1; i < object.length(); i++) {
            char current = object.charAt(i);
            if (inString) {
                if (escaped) {
                    raw.append('\\').append(current);
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    values.add(TrackSyncClient.unescape(raw.toString()));
                    raw.setLength(0);
                    inString = false;
                } else {
                    raw.append(current);
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == ']') {
                break;
            }
        }
        return values;
    }

    public static class RemotePlaylist {

        private final String name;
        private final List<String> locations;

        public RemotePlaylist(String name, List<String> locations) {
            this.name = name;
            this.locations = locations;
        }

        public String getName() {
            return name;
        }

        public List<String> getLocations() {
            return locations;
        }
    }
}
