package ua.kpi.musicplayer.sync;

public class TrackUploadResult {

    private final int uploaded;
    private final int skipped;
    private final int failed;

    public TrackUploadResult(int uploaded, int skipped, int failed) {
        this.uploaded = uploaded;
        this.skipped = skipped;
        this.failed = failed;
    }

    public int getUploaded() {
        return uploaded;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getFailed() {
        return failed;
    }
}
