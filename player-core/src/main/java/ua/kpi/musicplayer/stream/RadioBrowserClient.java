package ua.kpi.musicplayer.stream;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RadioBrowserClient {

    private static final String API_BASE = "https://de1.api.radio-browser.info";
    private static final int LIMIT = 30;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<RadioStation> search(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return List.of();
        }

        List<RadioStation> stations = request("/json/stations/search?name=" + encode(text)
                + "&limit=" + LIMIT
                + "&hidebroken=true&order=clickcount&reverse=true");
        if (stations.isEmpty()) {
            stations = request("/json/stations/search?tag=" + encode(text)
                    + "&limit=" + LIMIT
                    + "&hidebroken=true&order=clickcount&reverse=true");
        }
        return stations;
    }

    private List<RadioStation> request(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + path))
                .header("User-Agent", "MusicPlayerCoursework/1.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Radio Browser повернув код " + response.statusCode());
            }
            return parseStations(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося звернутися до Radio Browser", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Пошук станцій перервано", e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static List<RadioStation> parseStations(String json) {
        List<RadioStation> stations = new ArrayList<>();
        for (String object : splitObjects(json)) {
            String urlResolved = readString(object, "url_resolved");
            String url = readString(object, "url");
            if (isBlank(urlResolved) && isBlank(url)) {
                continue;
            }

            RadioStation station = new RadioStation();
            station.setName(readString(object, "name"));
            station.setCountry(readString(object, "country"));
            station.setTags(readString(object, "tags"));
            station.setUrl(url);
            station.setUrlResolved(urlResolved);
            stations.add(station);
        }
        return stations;
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
        String marker = "\"" + key + "\"";
        int keyIndex = object.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = object.indexOf(':', keyIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }

        int start = object.indexOf('"', colonIndex + 1);
        if (start < 0) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
