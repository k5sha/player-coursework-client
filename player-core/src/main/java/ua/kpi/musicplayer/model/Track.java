package ua.kpi.musicplayer.model;

public class Track {

    private String title;
    private String artist;
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
}
