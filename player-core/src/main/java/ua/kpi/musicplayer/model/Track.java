package ua.kpi.musicplayer.model;

import ua.kpi.musicplayer.visitor.MediaInfoVisitor;

public class Track {

    private String title;
    private String artist;
    private String album;
    private long durationMs;
    private String location;

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

    public String accept(MediaInfoVisitor visitor) {
        return visitor.visitTrack(this);
    }

    @Override
    public String toString() {
        boolean hasArtist = artist != null && !artist.isBlank();
        boolean hasTitle = title != null && !title.isBlank();

        if (hasArtist && hasTitle) {
            return artist.trim() + " - " + title.trim();
        }
        if (hasTitle) {
            return title.trim();
        }

        String fileName = fileNameFromLocation();
        return fileName.isEmpty() ? "Без назви" : fileName;
    }

    private String fileNameFromLocation() {
        if (location == null || location.isBlank()) {
            return "";
        }

        int separator = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return separator >= 0 ? location.substring(separator + 1) : location;
    }
}
