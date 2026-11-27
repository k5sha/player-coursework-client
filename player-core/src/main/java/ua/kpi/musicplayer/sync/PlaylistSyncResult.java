package ua.kpi.musicplayer.sync;

public class PlaylistSyncResult {

    private final int uploaded;
    private final int downloaded;
    private final boolean serverAvailable;

    public PlaylistSyncResult(int uploaded, int downloaded, boolean serverAvailable) {
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.serverAvailable = serverAvailable;
    }

    public static PlaylistSyncResult unavailable() {
        return new PlaylistSyncResult(0, 0, false);
    }

    public int getUploaded() {
        return uploaded;
    }

    public int getDownloaded() {
        return downloaded;
    }

    public boolean isServerAvailable() {
        return serverAvailable;
    }
}
