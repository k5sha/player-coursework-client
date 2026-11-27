package ua.kpi.musicplayer.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;

public class MusicPlayerApplication extends Application {

    private MusicPlayerFacade facade;
    private MainView view;

    @Override
    public void start(Stage stage) {
        facade = new MusicPlayerFacade();
        view = new MainView(facade);

        stage.setTitle("Music Player");

        Scene scene = new Scene(view, 1020, 700);
        scene.setFill(Color.web("#0d1117"));
        scene.getStylesheets().add(
                getClass().getResource("/ua/kpi/musicplayer/ui/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
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
