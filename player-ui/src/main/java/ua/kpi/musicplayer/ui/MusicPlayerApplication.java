package ua.kpi.musicplayer.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;

public class MusicPlayerApplication extends Application {

    private MusicPlayerFacade facade;

    @Override
    public void start(Stage stage) {
        facade = new MusicPlayerFacade();
        MainView view = new MainView(facade);

        stage.setTitle("Music Player");
        stage.setScene(new Scene(view));
        stage.sizeToScene();
        stage.setMinWidth(stage.getWidth());
        stage.setMinHeight(stage.getHeight());
        stage.show();
    }

    @Override
    public void stop() {
        if (facade != null) {
            facade.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
