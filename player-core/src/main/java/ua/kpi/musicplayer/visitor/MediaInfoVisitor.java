package ua.kpi.musicplayer.visitor;

import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.stream.RadioStation;

public interface MediaInfoVisitor {

    String visitTrack(Track track);

    String visitRadioStation(RadioStation station);
}
