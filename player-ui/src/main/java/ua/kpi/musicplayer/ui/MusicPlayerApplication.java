package ua.kpi.musicplayer.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;

public class MusicPlayerApplication extends Application {

    private MusicPlayerFacade facade;
    private MainView view;

    @Override
    public void start(Stage stage) {
        facade = new MusicPlayerFacade();
        view = new MainView(facade);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Music Player");
        stage.setResizable(false);

        Scene scene = new Scene(view, Color.TRANSPARENT);
        scene.getStylesheets().add(
                getClass().getResource("/ua/kpi/musicplayer/ui/styles.css").toExternalForm());
        stage.setScene(scene);

        view.attachStage(stage);
        stage.sizeToScene();
        stage.setMinWidth(stage.getWidth());
        stage.setMinHeight(stage.getHeight());
        stage.show();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.saveState();
        }
        if (facade != null) {
            facade.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
