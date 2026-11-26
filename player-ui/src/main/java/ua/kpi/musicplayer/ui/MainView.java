package ua.kpi.musicplayer.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import ua.kpi.musicplayer.audio.AudioMetadataReader;
import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.stream.RadioStation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainView extends VBox {

    private static final String CURRENT_TRACK_STYLE =
            "-fx-font-weight: bold; -fx-text-fill: #1565c0;";

    private final MusicPlayerFacade facade;
    private final ListView<Track> trackList = new ListView<>();
    private final ListView<RadioStation> stationList = new ListView<>();
    private final TextField streamUrlField = new TextField();
    private final TextField stationSearchField = new TextField();
    private final Label fileLabel = new Label("Файл не обрано");
    private final Label trackInfoLabel = new Label("Інформація про трек відсутня");
    private final Label statusLabel = new Label("Готово до роботи");
    private final Button pauseButton = new Button("Пауза");
    private final Button repeatButton = new Button();
    private final Button shuffleButton = new Button();
    private final Button streamPlayButton = new Button("Відтворити потік");
    private final Button stationSearchButton = new Button("Знайти");
    private final Button stationPlayButton = new Button("Обрану станцію");
    private final ComboBox<Equalizer.Preset> equalizerPresetBox = new ComboBox<>();
    private Slider lowEqSlider;
    private Slider midEqSlider;
    private Slider highEqSlider;
    private long streamRequest;

    public MainView(MusicPlayerFacade facade) {
        this.facade = facade;

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

        repeatButton.setOnAction(event -> {
            facade.cycleRepeatMode();
            updateModeControls();
            statusLabel.setText("Режим повтору: " + repeatModeText());
        });

        shuffleButton.setOnAction(event -> {
            facade.toggleShuffle();
            updateModeControls();
            statusLabel.setText("Перемішування: " + (facade.isShuffle() ? "увімкнено" : "вимкнено"));
        });

        Slider volumeSlider = new Slider(0, 100, facade.getVolume() * 100);
        volumeSlider.setPrefWidth(180);
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                facade.setVolume(newValue.floatValue() / 100.0f));

        lowEqSlider = createEqSlider(Equalizer.LOW_BAND);
        midEqSlider = createEqSlider(Equalizer.MID_BAND);
        highEqSlider = createEqSlider(Equalizer.HIGH_BAND);

        equalizerPresetBox.getItems().setAll(Equalizer.Preset.values());
        equalizerPresetBox.setValue(Equalizer.Preset.FLAT);
        equalizerPresetBox.setOnAction(event -> {
            Equalizer.Preset preset = equalizerPresetBox.getValue();
            if (preset == null) {
                return;
            }
            facade.applyEqualizerPreset(preset);
            updateEqualizerControls();
            statusLabel.setText("Еквалайзер: " + preset.getDisplayName());
        });

        Button resetEqualizerButton = new Button("Скинути");
        resetEqualizerButton.setOnAction(event -> {
            facade.resetEqualizer();
            equalizerPresetBox.setValue(Equalizer.Preset.FLAT);
            updateEqualizerControls();
            statusLabel.setText("Еквалайзер скинуто");
        });

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
                    setStyle(getIndex() == facade.getCurrentIndex() ? CURRENT_TRACK_STYLE : "");
                }
            }
        });
        trackList.setOnMouseClicked(event -> {
            int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
            if (event.getClickCount() == 2 && selectedIndex >= 0) {
                playTrack(selectedIndex);
            }
        });

        streamUrlField.setPromptText("URL аудіопотоку");
        streamUrlField.setPrefWidth(320);
        streamPlayButton.setOnAction(event -> playStream(streamUrlField.getText()));

        stationSearchField.setPromptText("Назва або тег станції");
        stationSearchField.setPrefWidth(220);
        stationSearchField.setOnAction(event -> searchStations());
        stationSearchButton.setOnAction(event -> searchStations());
        stationPlayButton.setDisable(true);
        stationPlayButton.setOnAction(event -> playSelectedStation());

        stationList.setPrefHeight(160);
        stationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(RadioStation station, boolean empty) {
                super.updateItem(station, empty);
                setText(empty || station == null ? null : station.toString());
            }
        });
        stationList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> stationPlayButton.setDisable(newValue == null));
        stationList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                playSelectedStation();
            }
        });

        facade.setOnTrackFinished(streamFinished ->
                Platform.runLater(() -> onTrackFinished(streamFinished)));

        updateModeControls();

        getChildren().addAll(
                new HBox(8, chooseButton, playButton, pauseButton, stopButton, previousButton, nextButton),
                new HBox(8, new Label("Гучність:"), volumeSlider),
                new HBox(8, new Label("Еквалайзер:"), equalizerPresetBox, resetEqualizerButton),
                new HBox(8, new Label("Низькі:"), lowEqSlider),
                new HBox(8, new Label("Середні:"), midEqSlider),
                new HBox(8, new Label("Високі:"), highEqSlider),
                new HBox(8, new Label("Режими:"), repeatButton, shuffleButton),
                new HBox(8, new Label("URL потоку:"), streamUrlField, streamPlayButton),
                new HBox(8, new Label("Radio Browser:"), stationSearchField, stationSearchButton, stationPlayButton),
                stationList,
                trackList,
                trackInfoLabel,
                fileLabel,
                statusLabel);

        loadSavedTracks();
    }

    private void loadSavedTracks() {
        try {
            List<Track> savedTracks = facade.loadSavedTracks();
            if (savedTracks.isEmpty()) {
                return;
            }

            trackList.getItems().setAll(facade.getTracks());
            trackList.getSelectionModel().select(facade.getCurrentIndex());
            updateTrackInfo(facade.getCurrentTrack());
            statusLabel.setText("Завантажено збережених треків: " + savedTracks.size());
        } catch (RuntimeException e) {
            statusLabel.setText("Не вдалося завантажити треки: " + e.getMessage());
        }
    }

    private Slider createEqSlider(int band) {
        Slider slider = new Slider(Equalizer.MIN_GAIN_DB, Equalizer.MAX_GAIN_DB,
                facade.getBandGainDb(band));
        slider.setPrefWidth(160);
        slider.setBlockIncrement(1);
        slider.valueProperty().addListener((observable, oldValue, newValue) ->
                facade.setBandGainDb(band, newValue.floatValue()));
        return slider;
    }

    private void updateEqualizerControls() {
        lowEqSlider.setValue(facade.getBandGainDb(Equalizer.LOW_BAND));
        midEqSlider.setValue(facade.getBandGainDb(Equalizer.MID_BAND));
        highEqSlider.setValue(facade.getBandGainDb(Equalizer.HIGH_BAND));
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

        String saveError = null;
        try {
            facade.addTracksAndSave(addedTracks);
        } catch (RuntimeException e) {
            saveError = "Не вдалося зберегти треки: " + e.getMessage();
        }

        trackList.getItems().setAll(facade.getTracks());
        trackList.getSelectionModel().select(facade.getCurrentIndex());
        trackList.refresh();

        updateTrackInfo(facade.getCurrentTrack());
        fileLabel.setText("Додано треків: " + addedTracks.size());
        statusLabel.setText("У списку треків: " + facade.getTracks().size());
        if (saveError != null) {
            statusLabel.setText(saveError);
        }
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

    private void updateModeControls() {
        repeatButton.setText("Повтор: " + repeatModeText());
        shuffleButton.setText("Перемішування: " + (facade.isShuffle() ? "увімк." : "вимк."));
    }

    private String repeatModeText() {
        switch (facade.getRepeatMode()) {
            case ALL:
                return "усі";
            case ONE:
                return "один";
            default:
                return "вимк.";
        }
    }

    private void play() {
        if (facade.getTracks().isEmpty()) {
            statusLabel.setText("Спочатку оберіть файли");
            return;
        }

        int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
        playTrack(selectedIndex >= 0 ? selectedIndex : facade.getCurrentIndex());
    }

    private void playTrack(int index) {
        try {
            Track track = facade.playTrackAt(index);
            if (track == null) {
                statusLabel.setText("Спочатку оберіть файли");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void showPlayingTrack(Track track) {
        pauseButton.setText("Пауза");
        updateTrackInfo(track);
        fileLabel.setText("Поточний трек: " + track);
        statusLabel.setText("Відтворення: " + track);
        trackList.getSelectionModel().select(facade.getCurrentIndex());
        trackList.refresh();
    }

    private void playNext() {
        try {
            Track track = facade.playNext();
            if (track == null) {
                statusLabel.setText("Наступного треку немає");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void playPrevious() {
        try {
            Track track = facade.playPrevious();
            if (track == null) {
                statusLabel.setText("Попереднього треку немає");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void togglePause() {
        if (!facade.isPlaying()) {
            statusLabel.setText("Зараз нічого не відтворюється");
            return;
        }

        facade.togglePause();
        if (facade.isPaused()) {
            pauseButton.setText("Продовжити");
            statusLabel.setText("Пауза");
        } else {
            pauseButton.setText("Пауза");
            String streamUrl = facade.getCurrentStreamUrl();
            if (streamUrl != null) {
                statusLabel.setText("Відтворення потоку: " + streamUrl);
            } else {
                Track track = facade.getCurrentTrack();
                statusLabel.setText(track == null ? "Відтворення" : "Відтворення: " + track);
            }
        }
    }

    private void stop() {
        facade.stop();
        pauseButton.setText("Пауза");
        statusLabel.setText("Зупинено");
    }

    private void playSelectedStation() {
        RadioStation station = stationList.getSelectionModel().getSelectedItem();
        if (station == null) {
            statusLabel.setText("Оберіть станцію зі списку");
            return;
        }

        String url = station.getPlaybackUrl();
        if (!hasText(url)) {
            statusLabel.setText("У станції немає URL потоку");
            return;
        }

        streamUrlField.setText(url);
        playStream(url);
    }

    private void searchStations() {
        String query = stationSearchField.getText();
        if (!hasText(query)) {
            statusLabel.setText("Введіть назву або тег станції");
            return;
        }

        stationSearchButton.setDisable(true);
        statusLabel.setText("Пошук станцій Radio Browser...");
        CompletableFuture.supplyAsync(() -> facade.searchStations(query))
                .whenComplete((stations, error) -> Platform.runLater(() -> {
                    stationSearchButton.setDisable(false);
                    if (error != null) {
                        statusLabel.setText("Помилка пошуку: " + errorMessage(error));
                        return;
                    }

                    stationList.getItems().setAll(stations);
                    statusLabel.setText(stations.isEmpty()
                            ? "Станцій не знайдено"
                            : "Знайдено станцій: " + stations.size());
                }));
    }

    private void playStream(String url) {
        if (!hasText(url)) {
            statusLabel.setText("Введіть URL аудіопотоку");
            return;
        }

        String streamUrl = url.trim();
        long request = ++streamRequest;
        streamPlayButton.setDisable(true);
        stationPlayButton.setDisable(true);
        statusLabel.setText("Підключення до потоку...");

        CompletableFuture.supplyAsync(() -> facade.playStream(streamUrl))
                .whenComplete((started, error) -> Platform.runLater(() -> {
                    if (request != streamRequest) {
                        return;
                    }

                    streamPlayButton.setDisable(false);
                    stationPlayButton.setDisable(stationList.getSelectionModel().getSelectedItem() == null);

                    if (error != null) {
                        statusLabel.setText("Помилка потоку: " + errorMessage(error));
                        return;
                    }
                    if (!Boolean.TRUE.equals(started)) {
                        return;
                    }

                    pauseButton.setText("Пауза");
                    fileLabel.setText("Потік: " + streamUrl);
                    trackInfoLabel.setText("Інтернет-потік: " + streamUrl);
                    statusLabel.setText("Відтворення потоку: " + streamUrl);
                }));
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return hasText(cause.getMessage()) ? cause.getMessage() : cause.toString();
    }

    private void onTrackFinished(boolean streamFinished) {
        pauseButton.setText("Пауза");
        if (streamFinished) {
            statusLabel.setText("Потік завершено");
            return;
        }

        Track track = facade.getCurrentTrack();
        if (facade.isPlaying() && track != null) {
            showPlayingTrack(track);
        } else {
            statusLabel.setText("Відтворення завершено");
            trackList.refresh();
        }
    }
}
