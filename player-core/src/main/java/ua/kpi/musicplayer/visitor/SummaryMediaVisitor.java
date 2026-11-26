package ua.kpi.musicplayer.visitor;

import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.stream.RadioStation;

public class SummaryMediaVisitor implements MediaInfoVisitor {

    @Override
    public String visitTrack(Track track) {
        StringBuilder info = new StringBuilder();
        info.append(orDefault(track.getArtist(), "Невідомий виконавець"))
                .append(" - ")
                .append(orDefault(track.getTitle(), "Без назви"));

        if (hasText(track.getAlbum())) {
            info.append(" (").append(track.getAlbum().trim()).append(")");
        }

        info.append(" | Тривалість: ").append(formatDuration(track.getDurationMs()))
                .append(" | Формат: ").append(formatFile(track));

        return info.toString();
    }

    @Override
    public String visitRadioStation(RadioStation station) {
        StringBuilder info = new StringBuilder(orDefault(station.getName(), "Без назви"));
        info.append(" | Жанр: ").append(orDefault(station.getTags(), "невідомий"))
                .append(" | Країна: ").append(orDefault(station.getCountry(), "невідома"))
                .append(" | URL: ").append(orDefault(station.getPlaybackUrl(), "немає"));

        return info.toString();
    }

    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "невідома";
        }
        long totalSeconds = durationMs / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String formatFile(Track track) {
        String location = track.getLocation();
        if (!hasText(location)) {
            return "невідомий";
        }

        int dot = location.lastIndexOf('.');
        if (dot < 0 || dot == location.length() - 1) {
            return "невідомий";
        }

        String extension = location.substring(dot + 1).trim().toUpperCase();
        return extension.isEmpty() ? "невідомий" : extension;
    }

    private static String orDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
