package ua.kpi.musicplayer.model;

import ua.kpi.musicplayer.visitor.MediaInfoVisitor;

import java.util.Objects;
import java.util.regex.Pattern;

public class Track {

    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{10,}_");

    private String title;
    private String artist;
    private String album;
    private long durationMs;
    private String location;
    private String fileName;
    private long serverId;
    private boolean localOnly;

    public Track() {
    }

    public Track(String title, String artist, long durationMs, String location) {
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.location = location;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getServerId() {
        return serverId;
    }

    public void setServerId(long serverId) {
        this.serverId = serverId;
    }

    public boolean isLocalOnly() {
        return localOnly;
    }

    public void setLocalOnly(boolean localOnly) {
        this.localOnly = localOnly;
    }

    public String accept(MediaInfoVisitor visitor) {
        return visitor.visitTrack(this);
    }

    public static String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        String name = fileName.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = TIMESTAMP_PREFIX.matcher(name).replaceFirst("");
        return name.replace('_', ' ').trim();
    }

    public static boolean hasTimestampPrefix(String value) {
        return value != null && TIMESTAMP_PREFIX.matcher(value.trim()).lookingAt();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Track track)) {
            return false;
        }
        if (location != null || track.location != null) {
            return Objects.equals(location, track.location);
        }
        return Objects.equals(title, track.title) && Objects.equals(artist, track.artist);
    }

    @Override
    public int hashCode() {
        if (location != null) {
            return location.hashCode();
        }
        return Objects.hash(title, artist);
    }

    @Override
    public String toString() {
        boolean hasArtist = artist != null && !artist.isBlank();
        boolean hasTitle = title != null && !title.isBlank();

        String displayTitle = hasTitle
                ? (hasTimestampPrefix(title) ? normalizeFileName(title) : title.trim())
                : normalizeFileName(location);

        if (hasArtist && !displayTitle.isEmpty()) {
            return artist.trim() + " - " + displayTitle;
        }
        if (!displayTitle.isEmpty()) {
            return displayTitle;
        }

        return "Без назви";
    }
}
