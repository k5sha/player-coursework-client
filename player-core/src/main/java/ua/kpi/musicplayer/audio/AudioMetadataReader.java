package ua.kpi.musicplayer.audio;

import ua.kpi.musicplayer.model.Track;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.Map;

public final class AudioMetadataReader {

    private AudioMetadataReader() {
    }

    public static Track read(File file) {
        Track track = new Track();
        track.setLocation(file.getAbsolutePath());

        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            Map<String, Object> properties = fileFormat.properties();

            track.setTitle(text(properties, "title"));
            track.setArtist(firstNonBlank(
                    text(properties, "author"),
                    text(properties, "artist")));
            track.setAlbum(text(properties, "album"));
            track.setDurationMs(durationMs(properties, fileFormat));
        } catch (Exception ignored) {
            // Формат не підтримується
        }

        return track;
    }

    private static String text(Map<String, Object> properties, String key) {
        if (properties == null) {
            return null;
        }

        Object value = properties.get(key);
        if (value == null) {
            return null;
        }

        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null) {
            return first;
        }
        return second;
    }

    private static long durationMs(Map<String, Object> properties, AudioFileFormat fileFormat) {
        Object duration = properties == null ? null : properties.get("duration");
        if (duration instanceof Number) {
            long microseconds = ((Number) duration).longValue();
            if (microseconds > 0) {
                return microseconds / 1000;
            }
        }

        if (duration != null) {
            try {
                long microseconds = Long.parseLong(duration.toString().trim());
                if (microseconds > 0) {
                    return microseconds / 1000;
                }
            } catch (NumberFormatException ignored) {
                // Порахувати тривалість за кадрами
            }
        }

        float frameRate = fileFormat.getFormat().getFrameRate();
        long frameLength = fileFormat.getFrameLength();
        if (frameRate > 0 && frameLength > 0 && frameLength != AudioSystem.NOT_SPECIFIED) {
            return (long) (frameLength / frameRate * 1000);
        }

        return 0;
    }
}
