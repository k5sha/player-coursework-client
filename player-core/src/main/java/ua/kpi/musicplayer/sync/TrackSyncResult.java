package ua.kpi.musicplayer.sync;

public class TrackSyncResult {

    private final int uploaded;
    private final int downloaded;
    private final int cleared;

    public TrackSyncResult(int uploaded, int downloaded, int cleared) {
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.cleared = cleared;
    }

    public int getUploaded() {
        return uploaded;
    }

    public int getDownloaded() {
        return downloaded;
    }

    public int getCleared() {
        return cleared;
    }
}
