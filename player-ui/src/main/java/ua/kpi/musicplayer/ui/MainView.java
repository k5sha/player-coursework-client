package ua.kpi.musicplayer.ui;

import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
import javafx.util.Duration;
import ua.kpi.musicplayer.audio.AudioMetadataReader;
import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;
import ua.kpi.musicplayer.memento.PlayerMemento;
import ua.kpi.musicplayer.model.Track;
import ua.kpi.musicplayer.observer.PlayerState;
import ua.kpi.musicplayer.playback.PlaybackQueue;
import ua.kpi.musicplayer.stream.RadioStation;
import ua.kpi.musicplayer.visitor.MediaInfoVisitor;
import ua.kpi.musicplayer.visitor.SummaryMediaVisitor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class MainView extends VBox {

    private static final String CURRENT_TRACK_STYLE =
            "-fx-font-weight: bold; -fx-text-fill: #1565c0;";
    private static final Path STATE_FILE =
            Path.of(System.getProperty("user.home"), "musicplayer-state.properties");

    private final MusicPlayerFacade facade;
    private final MediaInfoVisitor mediaInfoVisitor = new SummaryMediaVisitor();
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
    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Label seekTimeLabel = new Label("00:00 / 00:00");
    private final Timeline seekTimeline =
            new Timeline(new KeyFrame(Duration.millis(500), event -> updateSeekControls()));
    private Slider volumeSlider;
    private Slider lowEqSlider;
    private Slider midEqSlider;
    private Slider highEqSlider;
    private boolean updatingSeekSlider;
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

        Button removeButton = new Button("Видалити трек");
        removeButton.setOnAction(event -> removeSelectedTrack());

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

        volumeSlider = new Slider(0, 100, facade.getVolume() * 100);
        volumeSlider.setPrefWidth(180);
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                facade.setVolume(newValue.floatValue() / 100.0f));

        seekSlider.setPrefWidth(320);
        seekSlider.setDisable(true);
        seekSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingSeekSlider) {
                seekTimeLabel.setText(formatDuration(Math.round(newValue.doubleValue() * 1000))
                        + " / " + formatDuration(Math.round(seekSlider.getMax() * 1000)));
            }
        });
        seekSlider.setOnMouseReleased(event -> seekToSliderValue());

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
        trackList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateTrackInfo(newValue));
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
                (observable, oldValue, newValue) -> {
                    stationPlayButton.setDisable(newValue == null);
                    updateStationInfo(newValue);
                });
        stationList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                playSelectedStation();
            }
        });

        facade.setOnTrackFinished(streamFinished ->
                Platform.runLater(() -> onTrackFinished(streamFinished)));
        facade.addObserver(state ->
                Platform.runLater(() -> updatePlayerState(state)));

        updateModeControls();

        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.windowProperty().addListener((windowObservable, oldWindow, newWindow) -> {
                if (newWindow != null) {
                    newWindow.setOnCloseRequest(event -> savePlayerState());
                }
            });
        });

        getChildren().addAll(
                new HBox(8, chooseButton, playButton, pauseButton, stopButton, previousButton, nextButton,
                        removeButton),
                new HBox(8, new Label("Гучність:"), volumeSlider),
                new HBox(8, new Label("Позиція:"), seekSlider, seekTimeLabel),
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
        updateSeekControls();
        seekTimeline.setCycleCount(Timeline.INDEFINITE);
        seekTimeline.play();
    }

    private void loadSavedTracks() {
        try {
            List<Track> savedTracks = facade.loadSavedTracks();
            restoreSavedPlayerState();
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

    private void savePlayerState() {
        PlayerMemento memento = facade.createMemento();
        Properties properties = new Properties();
        properties.setProperty("currentIndex", String.valueOf(memento.getCurrentIndex()));
        properties.setProperty("repeatMode", memento.getRepeatMode().name());
        properties.setProperty("shuffle", String.valueOf(memento.isShuffle()));
        properties.setProperty("volume", String.valueOf(memento.getVolume()));
        properties.setProperty("positionSeconds", String.valueOf(memento.getPositionSeconds()));

        float[] bands = memento.getEqualizerBands();
        for (int band = 0; band < bands.length; band++) {
            properties.setProperty("equalizer." + band, String.valueOf(bands[band]));
        }

        try (OutputStream output = Files.newOutputStream(STATE_FILE)) {
            properties.store(output, "Music player state");
        } catch (IOException ignored) {
            // Стан не збережено, роботі програми це не заважає
        }
    }

    private void restoreSavedPlayerState() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(STATE_FILE)) {
            properties.load(input);

            float[] bands = new float[Equalizer.BAND_COUNT];
            for (int band = 0; band < bands.length; band++) {
                bands[band] = Float.parseFloat(properties.getProperty("equalizer." + band, "0"));
            }

            PlayerMemento memento = new PlayerMemento(
                    Integer.parseInt(properties.getProperty("currentIndex", "-1")),
                    PlaybackQueue.RepeatMode.valueOf(properties.getProperty("repeatMode", "OFF")),
                    Boolean.parseBoolean(properties.getProperty("shuffle", "false")),
                    Float.parseFloat(properties.getProperty("volume", "1")),
                    bands,
                    Double.parseDouble(properties.getProperty("positionSeconds", "0")));
            facade.restore(memento);

            volumeSlider.setValue(facade.getVolume() * 100);
            updateModeControls();
            updateEqualizerControls();
            updateSeekControls();
        } catch (IOException | RuntimeException ignored) {
            // Пошкоджений файл стану не заважає запуску програми
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

    private void seekToSliderValue() {
        if (!facade.canSeek()) {
            return;
        }

        try {
            facade.seekToSeconds(seekSlider.getValue());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка перемотування: " + e.getMessage());
        }
        updateSeekControls();
    }

    private void updateSeekControls() {
        double duration = facade.getDurationSeconds();
        double position = facade.getCurrentPositionSeconds();
        boolean seekable = facade.canSeek() && duration > 0;

        seekSlider.setDisable(!seekable);
        seekSlider.setMax(duration > 0 ? duration : 1);
        if (!seekSlider.isValueChanging()) {
            updatingSeekSlider = true;
            try {
                seekSlider.setValue(Math.min(position, seekSlider.getMax()));
            } finally {
                updatingSeekSlider = false;
            }
        }

        double shownPosition = seekSlider.isValueChanging() ? seekSlider.getValue() : position;
        seekTimeLabel.setText(formatDuration(Math.round(shownPosition * 1000)) + " / "
                + formatDuration(Math.round(duration * 1000)));
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

    private void removeSelectedTrack() {
        int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) {
            statusLabel.setText("Оберіть трек для видалення");
            return;
        }

        try {
            Track removed = facade.removeTrackAt(selectedIndex);
            if (removed == null) {
                statusLabel.setText("Не вдалося видалити трек");
                return;
            }

            trackList.getItems().setAll(facade.getTracks());
            if (!trackList.getItems().isEmpty()) {
                trackList.getSelectionModel().select(
                        Math.min(selectedIndex, trackList.getItems().size() - 1));
            }
            trackList.refresh();
            updateTrackInfo(trackList.getSelectionModel().getSelectedItem());
            fileLabel.setText("Видалено трек: " + removed);
            statusLabel.setText("У списку треків: " + facade.getTracks().size());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка видалення: " + e.getMessage());
        }
    }

    private void updateTrackInfo(Track track) {
        trackInfoLabel.setText(track == null
                ? "Інформація про трек відсутня"
                : track.accept(mediaInfoVisitor));
    }

    private void updateStationInfo(RadioStation station) {
        if (station != null) {
            trackInfoLabel.setText(station.accept(mediaInfoVisitor));
        }
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
        updateSeekControls();
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
        updateSeekControls();
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
        playStream(url, station.accept(mediaInfoVisitor));
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
        playStream(url, null);
    }

    private void playStream(String url, String detail) {
        if (!hasText(url)) {
            statusLabel.setText("Введіть URL аудіопотоку");
            return;
        }

        String streamUrl = url.trim();
        long request = ++streamRequest;
        streamPlayButton.setDisable(true);
        stationPlayButton.setDisable(true);
        seekSlider.setDisable(true);
        statusLabel.setText("Підключення до потоку...");

        CompletableFuture.supplyAsync(() -> facade.playStream(streamUrl))
                .whenComplete((started, error) -> Platform.runLater(() -> {
                    if (request != streamRequest) {
                        return;
                    }

                    streamPlayButton.setDisable(false);
                    stationPlayButton.setDisable(stationList.getSelectionModel().getSelectedItem() == null);
                    updateSeekControls();

                    if (error != null) {
                        statusLabel.setText("Помилка потоку: " + errorMessage(error));
                        return;
                    }
                    if (!Boolean.TRUE.equals(started)) {
                        return;
                    }

                    pauseButton.setText("Пауза");
                    fileLabel.setText("Потік: " + streamUrl);
                    trackInfoLabel.setText(detail == null
                            ? "Інтернет-потік: " + streamUrl
                            : detail);
                    statusLabel.setText("Відтворення потоку: " + streamUrl);
                }));
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return hasText(cause.getMessage()) ? cause.getMessage() : cause.toString();
    }

    private void updatePlayerState(PlayerState state) {
        pauseButton.setText(state.isPaused() ? "Продовжити" : "Пауза");
        trackList.refresh();
        updateSeekControls();
    }

    private void onTrackFinished(boolean streamFinished) {
        pauseButton.setText("Пауза");
        if (streamFinished) {
            statusLabel.setText("Потік завершено");
            updateSeekControls();
            return;
        }

        Track track = facade.getCurrentTrack();
        if (facade.isPlaying() && track != null) {
            showPlayingTrack(track);
        } else {
            statusLabel.setText("Відтворення завершено");
            trackList.refresh();
        }
        updateSeekControls();
    }
}
