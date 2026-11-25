package ua.kpi.musicplayer.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;

public class MusicPlayerApplication extends Application {

    private SimpleAudioPlayer player;

    @Override
    public void start(Stage stage) {
        player = new SimpleAudioPlayer();
        MainView view = new MainView(player);

        stage.setTitle("Music Player");
        stage.setScene(new Scene(view, 480, 200));
        stage.show();
    }

    @Override
    public void stop() {
        if (player != null) {
            player.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
