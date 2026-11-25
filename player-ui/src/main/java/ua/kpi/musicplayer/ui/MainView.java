package ua.kpi.musicplayer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.model.Track;

import java.io.File;

public class MainView extends VBox {

    private final SimpleAudioPlayer player;
    private final Label fileLabel = new Label("Файл не обрано");
    private final Label statusLabel = new Label("Готово до роботи");
    private final Button pauseButton = new Button("Пауза");
    private Track currentTrack;

    public MainView(SimpleAudioPlayer player) {
        this.player = player;

        setSpacing(12);
        setPadding(new Insets(16));
        setAlignment(Pos.TOP_LEFT);

        Button chooseButton = new Button("Обрати файл");
        chooseButton.setOnAction(event -> chooseFile());

        Button playButton = new Button("Play");
        playButton.setOnAction(event -> play());

        Button stopButton = new Button("Stop");
        stopButton.setOnAction(event -> stop());

        pauseButton.setOnAction(event -> togglePause());

        Slider volumeSlider = new Slider(0, 100, player.getVolume() * 100);
        volumeSlider.setPrefWidth(180);
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                player.setVolume(newValue.floatValue() / 100.0f));

        getChildren().addAll(
                new HBox(8, chooseButton, playButton, pauseButton, stopButton),
                new HBox(8, new Label("Гучність:"), volumeSlider),
                fileLabel,
                statusLabel);
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Обрати аудіофайл");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Аудіофайли", "*.mp3", "*.wav", "*.flac", "*.ogg"));

        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }

        currentTrack = new Track(file.getName(), null, 0, file.getAbsolutePath());
        fileLabel.setText("Обраний файл: " + currentTrack.getTitle());
        statusLabel.setText("Файл готовий до відтворення");
    }

    private void play() {
        if (currentTrack == null) {
            statusLabel.setText("Спочатку оберіть файл");
            return;
        }

        try {
            player.play(currentTrack);
            pauseButton.setText("Пауза");
            statusLabel.setText("Відтворення: " + currentTrack.getTitle());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
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
            statusLabel.setText("Відтворення: " + currentTrack.getTitle());
        }
    }

    private void stop() {
        player.stop();
        pauseButton.setText("Пауза");
        statusLabel.setText("Зупинено");
    }
}
