package ua.kpi.musicplayer.sync;

public class TrackUploadResult {

    private final int uploaded;
    private final int skipped;
    private final int cleared;
    private final int failed;
    private final boolean serverAvailable;

    public TrackUploadResult(int uploaded, int skipped, int cleared, int failed,
                             boolean serverAvailable) {
        this.uploaded = uploaded;
        this.skipped = skipped;
        this.cleared = cleared;
        this.failed = failed;
        this.serverAvailable = serverAvailable;
    }

    public int getUploaded() {
        return uploaded;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getCleared() {
        return cleared;
    }

    public int getFailed() {
        return failed;
    }

    public boolean isServerAvailable() {
        return serverAvailable;
    }
}
