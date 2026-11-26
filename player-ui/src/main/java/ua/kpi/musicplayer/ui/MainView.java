package ua.kpi.musicplayer.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import ua.kpi.musicplayer.audio.AudioMetadataReader;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.playback.PlaybackQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainView extends VBox {

    private static final String CURRENT_TRACK_STYLE =
            "-fx-font-weight: bold; -fx-text-fill: #1565c0;";

    private final SimpleAudioPlayer player;
    private final PlaybackQueue queue = new PlaybackQueue();
    private final ListView<Track> trackList = new ListView<>();
    private final Label fileLabel = new Label("Файл не обрано");
    private final Label trackInfoLabel = new Label("Інформація про трек відсутня");
    private final Label statusLabel = new Label("Готово до роботи");
    private final Button pauseButton = new Button("Пауза");

    public MainView(SimpleAudioPlayer player) {
        this.player = player;

        setSpacing(12);
        setPadding(new Insets(16));
        setAlignment(Pos.TOP_LEFT);

        Button chooseButton = new Button("Обрати файли");
        chooseButton.setOnAction(event -> chooseFiles());

        Button playButton = new Button("Play");
        playButton.setOnAction(event -> play());

        Button stopButton = new Button("Stop");
        stopButton.setOnAction(event -> stop());

        Button previousButton = new Button("Попередній");
        previousButton.setOnAction(event -> playPrevious());

        Button nextButton = new Button("Наступний");
        nextButton.setOnAction(event -> playNext());

        pauseButton.setOnAction(event -> togglePause());

        Slider volumeSlider = new Slider(0, 100, player.getVolume() * 100);
        volumeSlider.setPrefWidth(180);
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                player.setVolume(newValue.floatValue() / 100.0f));

        trackList.setPrefHeight(220);
        trackList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Track track, boolean empty) {
                super.updateItem(track, empty);
                if (empty || track == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(track.toString());
                    setStyle(getIndex() == queue.getCurrentIndex() ? CURRENT_TRACK_STYLE : "");
                }
            }
        });
        trackList.setOnMouseClicked(event -> {
            int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
            if (event.getClickCount() == 2 && selectedIndex >= 0) {
                playTrack(selectedIndex);
            }
        });

        player.setOnTrackFinished(() -> Platform.runLater(this::onTrackFinished));

        getChildren().addAll(
                new HBox(8, chooseButton, playButton, pauseButton, stopButton, previousButton, nextButton),
                new HBox(8, new Label("Гучність:"), volumeSlider),
                trackList,
                trackInfoLabel,
                fileLabel,
                statusLabel);
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Обрати аудіофайли");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Аудіофайли", "*.mp3", "*.wav", "*.flac", "*.ogg"));

        List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }

        List<Track> addedTracks = new ArrayList<>();
        for (File file : files) {
            addedTracks.add(AudioMetadataReader.read(file));
        }

        queue.addTracks(addedTracks);
        trackList.getItems().setAll(queue.getTracks());
        trackList.getSelectionModel().select(queue.getCurrentIndex());
        trackList.refresh();

        updateTrackInfo(queue.getCurrentTrack());
        fileLabel.setText("Додано треків: " + addedTracks.size());
        statusLabel.setText("У списку треків: " + queue.getTracks().size());
    }

    private void updateTrackInfo(Track track) {
        if (track == null) {
            trackInfoLabel.setText("Інформація про трек відсутня");
            return;
        }

        List<String> parts = new ArrayList<>();
        if (hasText(track.getArtist())) {
            parts.add("Виконавець: " + track.getArtist().trim());
        }
        parts.add("Назва: " + displayTitle(track));
        if (hasText(track.getAlbum())) {
            parts.add("Альбом: " + track.getAlbum().trim());
        }
        if (track.getDurationMs() > 0) {
            parts.add("Тривалість: " + formatDuration(track.getDurationMs()));
        }

        trackInfoLabel.setText(String.join(" | ", parts));
    }

    private String displayTitle(Track track) {
        if (hasText(track.getTitle())) {
            return track.getTitle().trim();
        }
        if (hasText(track.getLocation())) {
            return new File(track.getLocation()).getName();
        }
        return "Без назви";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void play() {
        if (queue.getTracks().isEmpty()) {
            statusLabel.setText("Спочатку оберіть файли");
            return;
        }

        int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
        playTrack(selectedIndex >= 0 ? selectedIndex : queue.getCurrentIndex());
    }

    private void playTrack(int index) {
        queue.select(index);
        Track track = queue.getCurrentTrack();
        if (track == null) {
            statusLabel.setText("Спочатку оберіть файли");
            return;
        }

        try {
            player.play(track);
            pauseButton.setText("Пауза");
            updateTrackInfo(track);
            fileLabel.setText("Поточний трек: " + track);
            statusLabel.setText("Відтворення: " + track);
            trackList.getSelectionModel().select(index);
            trackList.refresh();
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void playNext() {
        if (!queue.hasNext()) {
            statusLabel.setText("Наступного треку немає");
            return;
        }

        queue.next();
        playTrack(queue.getCurrentIndex());
    }

    private void playPrevious() {
        if (!queue.hasPrevious()) {
            statusLabel.setText("Попереднього треку немає");
            return;
        }

        queue.previous();
        playTrack(queue.getCurrentIndex());
    }

    private void togglePause() {
        if (!player.isPlaying()) {
            statusLabel.setText("Зараз нічого не відтворюється");
            return;
        }

        player.togglePause();
        if (player.isPaused()) {
            pauseButton.setText("Продовжити");
            statusLabel.setText("Пауза");
        } else {
            pauseButton.setText("Пауза");
            Track track = queue.getCurrentTrack();
            statusLabel.setText(track == null ? "Відтворення" : "Відтворення: " + track);
        }
    }

    private void stop() {
        player.stop();
        pauseButton.setText("Пауза");
        statusLabel.setText("Зупинено");
    }

    private void onTrackFinished() {
        pauseButton.setText("Пауза");
        if (queue.hasNext()) {
            queue.next();
            playTrack(queue.getCurrentIndex());
        } else {
            statusLabel.setText("Відтворення завершено");
            trackList.refresh();
        }
    }
}
