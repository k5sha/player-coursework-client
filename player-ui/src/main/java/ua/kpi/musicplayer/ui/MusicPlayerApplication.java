package ua.kpi.musicplayer.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ua.kpi.musicplayer.audio.SimpleAudioPlayer;

public class MusicPlayerApplication extends Application {

    private SimpleAudioPlayer player;
    private MainView view;

    @Override
    public void start(Stage stage) {
        player = new SimpleAudioPlayer();
        view = new MainView(player);

        stage.setTitle("Music Player");
        stage.setScene(new Scene(view));
        stage.sizeToScene();
        stage.setMinWidth(stage.getWidth());
        stage.setMinHeight(stage.getHeight());
        stage.show();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.saveTracks();
        }
        if (player != null) {
            player.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
