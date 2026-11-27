package ua.kpi.musicplayer.sync;

import ua.kpi.musicplayer.model.Track;

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
 * Простий клієнт синхронізації треків із сервером.
 * Сервер очікується за адресою http://localhost:8080 з ресурсом /tracks.
 */
public class TrackSyncClient {

    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private final String apiBase;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    public TrackSyncClient() {
        this("http://localhost:8080");
    }

    public TrackSyncClient(String apiBase) {
        String base = apiBase == null || apiBase.isBlank() ? "http://localhost:8080" : apiBase.trim();
        this.apiBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public List<Track> fetchTracks() {
        HttpRequest request = requestBuilder("/tracks").GET().build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200);
        return parseTracks(response.body());
    }

    public void uploadTrack(Track track) {
        HttpRequest request = requestBuilder("/tracks")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(track), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200, 201);

        // Сервер повертає створений трек разом з id — запам'ятовуємо його для видалення
        long serverId = readLong(response.body(), "id");
        if (serverId > 0) {
            track.setServerId(serverId);
        }
    }

    public void deleteTrack(long id) {
        HttpRequest request = requestBuilder("/tracks/" + id).DELETE().build();
        HttpResponse<String> response = send(request);
        // 404 означає, що трек уже видалено на сервері — для клієнта це успіх
        checkStatus(response, 200, 204, 404);
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

    private static String toJson(Track track) {
        return "{\"title\":" + quote(track.getTitle())
                + ",\"artist\":" + quote(track.getArtist())
                + ",\"album\":" + quote(track.getAlbum())
                + ",\"durationMs\":" + track.getDurationMs()
                + ",\"location\":" + quote(track.getLocation()) + "}";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }

        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < ' ') {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static List<Track> parseTracks(String json) {
        List<Track> tracks = new ArrayList<>();
        for (String object : splitObjects(json)) {
            String location = readString(object, "location");
            if (location == null || location.isBlank()) {
                continue;
            }

            Track track = new Track();
            track.setServerId(readLong(object, "id"));
            track.setTitle(readString(object, "title"));
            track.setArtist(readString(object, "artist"));
            track.setAlbum(readString(object, "album"));
            track.setDurationMs(readLong(object, "durationMs"));
            track.setLocation(location);
            tracks.add(track);
        }
        return tracks;
    }

    private static List<String> splitObjects(String json) {
        List<String> objects = new ArrayList<>();
        if (json == null) {
            return objects;
        }

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String readString(String object, String key) {
        int start = valueStart(object, key);
        if (start < 0 || object.charAt(start) != '"') {
            return null;
        }

        StringBuilder raw = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < object.length(); i++) {
            char current = object.charAt(i);
            if (escaped) {
                raw.append('\\').append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return unescape(raw.toString());
            } else {
                raw.append(current);
            }
        }
        return raw.toString();
    }

    private static long readLong(String object, String key) {
        int start = valueStart(object, key);
        if (start < 0) {
            return 0;
        }

        int end = start;
        while (end < object.length() && (Character.isDigit(object.charAt(end)) || object.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return 0;
        }

        try {
            return Long.parseLong(object.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int valueStart(String object, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = object.indexOf(marker);
        if (keyIndex < 0) {
            return -1;
        }

        int colonIndex = object.indexOf(':', keyIndex + marker.length());
        if (colonIndex < 0) {
            return -1;
        }

        int start = colonIndex + 1;
        while (start < object.length() && Character.isWhitespace(object.charAt(start))) {
            start++;
        }
        return start < object.length() ? start : -1;
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                result.append(current);
                continue;
            }

            char next = value.charAt(++i);
            switch (next) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            result.append("\\u");
                        }
                    } else {
                        result.append("\\u");
                    }
                }
                default -> result.append(next);
            }
        }
        return result.toString();
    }
}
