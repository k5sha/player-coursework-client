package ua.kpi.musicplayer.playback;

import ua.kpi.musicplayer.model.Track;

public interface TrackIterator {

    boolean hasNext();

    Track next();

    boolean hasPrevious();

    Track previous();
}
