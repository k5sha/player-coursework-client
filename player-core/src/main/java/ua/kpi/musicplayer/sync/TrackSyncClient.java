package ua.kpi.musicplayer.sync;

import ua.kpi.musicplayer.model.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class TrackSyncClient {

    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;
    private static final int FILE_TIMEOUT_SECONDS = 60;
    private static final Path CACHE_DIR =
            Path.of(System.getProperty("user.home"), "musicplayer-cache");

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

    public static boolean isCacheFile(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        try {
            return Path.of(location).startsWith(CACHE_DIR);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<Track> fetchTracks() {
        HttpRequest request = requestBuilder("/tracks").GET().build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200);
        return parseTracks(response.body());
    }

    public void deleteTrack(long id) {
        HttpRequest request = requestBuilder("/tracks/" + id).DELETE().build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200, 204, 404);
    }

    public void deletePlaylist(String name) {
        if (name == null || name.isBlank()) {
            return;
        }

        HttpRequest listRequest = requestBuilder("/playlists").GET().build();
        HttpResponse<String> listResponse = send(listRequest);
        checkStatus(listResponse, 200);

        long playlistId = findPlaylistIdByName(listResponse.body(), name.trim());
        if (playlistId <= 0) {
            return;
        }

        HttpRequest deleteRequest = requestBuilder("/playlists/" + playlistId).DELETE().build();
        HttpResponse<String> deleteResponse = send(deleteRequest);
        checkStatus(deleteResponse, 200, 204, 404);
    }

    private static long findPlaylistIdByName(String json, String name) {
        for (String object : splitObjects(json)) {
            if (name.equals(readString(object, "name"))) {
                return readLong(object, "id");
            }
        }
        return 0;
    }


    public Path downloadTrackFile(long id, String fileNameHint) {
        if (id <= 0) {
            throw new IllegalArgumentException("Некоректний id треку");
        }

        Path target = CACHE_DIR.resolve("track-" + id + fileExtension(fileNameHint));
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return target;
            }

            Files.createDirectories(CACHE_DIR);
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(apiBase + "/tracks/" + id + "/file"))
                    .header("Accept", "*/*")
                    .timeout(Duration.ofSeconds(FILE_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<Path> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            } catch (IOException e) {
                throw new IllegalStateException("Сервер недоступний за адресою " + apiBase, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Запит до сервера перервано", e);
            }

            if (response.statusCode() != 200) {
                Files.deleteIfExists(temp);
                throw new IllegalStateException("Сервер повернув код " + response.statusCode());
            }

            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw new IllegalStateException("Не вдалося зберегти файл треку", e);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося завантажити файл треку", e);
        }
    }

    public Track uploadTrackFile(Path file, String title, String artist, String album,
                                 int durationSeconds) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Локальний файл треку не знайдено");
        }

        String boundary = "----MusicPlayer" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            writeTextPart(body, boundary, "title", title);
            writeTextPart(body, boundary, "artist", artist);
            writeTextPart(body, boundary, "album", album);
            writeTextPart(body, boundary, "durationSeconds",
                    String.valueOf(Math.max(0, durationSeconds)));
            writeFilePart(body, boundary, file);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося підготувати файл до завантаження", e);
        }

        HttpRequest request = requestBuilder("/tracks/upload")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(FILE_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = send(request);
        checkStatus(response, 200, 201);

        Track uploaded = parseTrack(response.body());
        if (uploaded == null || uploaded.getServerId() <= 0) {
            throw new IllegalStateException("Сервер не повернув id створеного треку");
        }
        return uploaded;
    }

    private static void writeTextPart(ByteArrayOutputStream body, String boundary,
                                      String name, String value) throws IOException {
        String text = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n";
        body.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream body, String boundary, Path file)
            throws IOException {
        String fileName = file.getFileName() == null ? "track" : file.getFileName().toString();
        fileName = fileName.replace("\"", "_").replace("\r", " ").replace("\n", " ");
        String headers = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        body.write(headers.getBytes(StandardCharsets.UTF_8));
        Files.copy(file, body);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String fileExtension(String fileName) {
        if (fileName != null) {
            int separator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
            String name = separator >= 0 ? fileName.substring(separator + 1) : fileName;
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                String extension = name.substring(dot);
                if (extension.matches("\\.[A-Za-z0-9]{1,8}")) {
                    return extension.toLowerCase(Locale.ROOT);
                }
            }
        }
        return ".mp3";
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

    static String quote(String value) {
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
            Track track = parseTrack(object);
            if (track != null) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    private static Track parseTrack(String object) {
        long serverId = readLong(object, "id");
        String location = readString(object, "location");
        String fileName = readString(object, "fileName");
        if (serverId <= 0
                && (location == null || location.isBlank())
                && (fileName == null || fileName.isBlank())) {
            return null;
        }

        Track track = new Track();
        track.setServerId(serverId);
        track.setTitle(readString(object, "title"));
        track.setArtist(readString(object, "artist"));
        track.setAlbum(readString(object, "album"));
        track.setDurationMs(readLong(object, "durationMs"));
        track.setLocation(location == null ? "" : location);
        track.setFileName(fileName);
        return track;
    }

    static List<String> splitObjects(String json) {
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

    static String readString(String object, String key) {
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

    static int valueStart(String object, String key) {
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

    static String unescape(String value) {
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
