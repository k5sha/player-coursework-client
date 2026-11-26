package ua.kpi.musicplayer.model;

public class Playlist {

    private final long id;
    private final String name;

    public Playlist(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
