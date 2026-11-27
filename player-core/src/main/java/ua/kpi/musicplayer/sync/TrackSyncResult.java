package ua.kpi.musicplayer.sync;

public class TrackSyncResult {

    private final int uploaded;
    private final int downloaded;

    public TrackSyncResult(int uploaded, int downloaded) {
        this.uploaded = uploaded;
        this.downloaded = downloaded;
    }

    public int getUploaded() {
        return uploaded;
    }

    public int getDownloaded() {
        return downloaded;
    }
}
