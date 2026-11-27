package ua.kpi.musicplayer.sync;

public class TrackSyncResult {

    private final int uploaded;
    private final int downloaded;
    private final int cleared;
    private final int failed;

    public TrackSyncResult(int uploaded, int downloaded, int cleared, int failed) {
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.cleared = cleared;
        this.failed = failed;
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

    public int getFailed() {
        return failed;
    }
}
