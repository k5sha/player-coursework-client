package ua.kpi.musicplayer.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import ua.kpi.musicplayer.audio.AudioMetadataReader;
import ua.kpi.musicplayer.audio.Equalizer;
import ua.kpi.musicplayer.facade.MusicPlayerFacade;
import ua.kpi.musicplayer.memento.PlayerMemento;
import ua.kpi.musicplayer.model.Playlist;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class MainView extends StackPane {

    private static final Path STATE_FILE =
            Path.of(System.getProperty("user.home"), "musicplayer-state.properties");

    private final MusicPlayerFacade facade;
    private final MediaInfoVisitor mediaInfoVisitor = new SummaryMediaVisitor();

    private final ListView<Track> trackList = new ListView<>();
    private final ListView<RadioStation> stationList = new ListView<>();
    private final TextField streamUrlField = new TextField();
    private final TextField stationSearchField = new TextField();
    private final Label nowPlayingLabel = new Label("Нічого не відтворюється");
    private final Label currentDetailsLabel = new Label();
    private final Label playlistDetailsLabel = new Label();
    private final Label stationDetailsLabel = new Label();
    private final Label statusLabel = new Label("Готово");
    private final Label selectionLabel = new Label();
    private final Button pauseButton = new Button("Пауза");
    private final Button repeatButton = new Button();
    private final Button shuffleButton = new Button();
    private final Button streamPlayButton = new Button("Грати");
    private final Button stationSearchButton = new Button("Пошук");
    private final Button stationPlayButton = new Button("Грати");
    private final Button createPlaylistButton = new Button("Створити");
    private final Button deletePlaylistButton = new Button("Видалити");
    private final Button removeSelectedButton = new Button("Видалити");
    private final ComboBox<Equalizer.Preset> equalizerPresetBox = new ComboBox<>();
    private final ComboBox<Playlist> playlistBox = new ComboBox<>();
    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Label seekTimeLabel = new Label("00:00 / 00:00");
    private final Timeline seekTimeline =
            new Timeline(new KeyFrame(Duration.millis(500), event -> updateSeekControls()));
    private final StackPane pageStack = new StackPane();

    private Slider volumeSlider;
    private Slider lowEqSlider;
    private Slider midEqSlider;
    private Slider highEqSlider;
    private Stage stage;
    private boolean updatingSeekSlider;
    private boolean updatingPlaylistBox;
    private int contextMenuTrackIndex = -1;
    private long streamRequest;
    private double dragOffsetX;
    private double dragOffsetY;

    public MainView(MusicPlayerFacade facade) {
        this.facade = facade;

        setPrefSize(1080, 730);
        setPadding(new Insets(10));
        setAlignment(Pos.CENTER);
        getStyleClass().add("main-root");

        volumeSlider = new Slider(0, 100, facade.getVolume() * 100);
        volumeSlider.setPrefWidth(220);
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                facade.setVolume(newValue.floatValue() / 100.0f));

        seekSlider.setPrefWidth(480);
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

        playlistBox.setPrefWidth(220);
        playlistBox.setOnAction(event -> switchPlaylist());

        pauseButton.setOnAction(event -> togglePause());
        deletePlaylistButton.getStyleClass().add("danger-button");
        deletePlaylistButton.setOnAction(event -> deletePlaylist());
        deletePlaylistButton.setTooltip(new Tooltip("Видалити плейлист"));
        createPlaylistButton.setOnAction(event -> createPlaylist());
        createPlaylistButton.setTooltip(new Tooltip("Створити плейлист"));
        removeSelectedButton.getStyleClass().add("danger-button");
        removeSelectedButton.setOnAction(event -> removeSelectedTracks());
        removeSelectedButton.setTooltip(new Tooltip("Видалити вибрані треки"));

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

        trackList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        trackList.setPrefHeight(320);
        trackList.setPlaceholder(new Label("Порожньо"));
        trackList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Track track, boolean empty) {
                super.updateItem(track, empty);
                getStyleClass().remove("current-track");
                if (empty || track == null) {
                    setText(null);
                } else {
                    setText(track.toString());
                    if (getIndex() == facade.getCurrentIndex()) {
                        getStyleClass().add("current-track");
                    }
                }
            }
        });
        trackList.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<Track>) change -> updateSelectionInfo());
        trackList.setOnMouseClicked(event -> {
            int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
            if (event.getClickCount() == 2 && selectedIndex >= 0) {
                playTrack(selectedIndex);
            }
        });
        trackList.setContextMenu(createTrackContextMenu());
        trackList.setOnContextMenuRequested(event ->
                selectTrackForContextMenu(event.getPickResult().getIntersectedNode()));
        trackList.setOnMousePressed(event -> {
            if (event.isSecondaryButtonDown()) {
                selectTrackForContextMenu(event.getPickResult().getIntersectedNode());
            }
        });

        streamUrlField.setPromptText("Адреса потоку");
        streamUrlField.setPrefWidth(420);
        streamPlayButton.getStyleClass().add("accent-button");
        streamPlayButton.setOnAction(event -> playStream(streamUrlField.getText()));

        stationSearchField.setPromptText("Станція або тег");
        stationSearchField.setPrefWidth(320);
        stationSearchField.setOnAction(event -> searchStations());
        stationSearchButton.setOnAction(event -> searchStations());
        stationPlayButton.setDisable(true);
        stationPlayButton.setOnAction(event -> playSelectedStation());

        stationList.setPrefHeight(260);
        stationList.setPlaceholder(new Label("Нічого не знайдено"));
        stationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(RadioStation station, boolean empty) {
                super.updateItem(station, empty);
                setText(empty || station == null ? null : stationName(station));
            }
        });
        stationList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    stationPlayButton.setDisable(newValue == null);
                    showStationDetails(newValue);
                });
        stationList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                playSelectedStation();
            }
        });

        updateModeControls();

        VBox playerPage = createPlayerPage();
        VBox playlistsPage = createPlaylistsPage();
        VBox radioPage = createRadioPage();
        VBox equalizerPage = createEqualizerPage();

        ToggleGroup navigation = new ToggleGroup();
        ToggleButton playerNav = createNavButton("Плеєр", playerPage, navigation);
        ToggleButton playlistsNav = createNavButton("Плейлисти", playlistsPage, navigation);
        ToggleButton radioNav = createNavButton("Радіо", radioPage, navigation);
        ToggleButton equalizerNav = createNavButton("Еквалайзер", equalizerPage, navigation);
        playerNav.setSelected(true);
        pageStack.getChildren().setAll(playerPage);
        pageStack.setPadding(new Insets(20, 28, 10, 28));
        pageStack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        BorderPane frame = new BorderPane();
        frame.getStyleClass().add("app-frame");
        frame.setTop(createHeader(playerNav, playlistsNav, radioNav, equalizerNav));
        frame.setCenter(pageStack);
        frame.setBottom(createStatusBar());
        getChildren().add(frame);

        facade.setOnTrackFinished(streamFinished ->
                Platform.runLater(() -> onTrackFinished(streamFinished)));
        facade.addObserver(state ->
                Platform.runLater(() -> updatePlayerState(state)));

        loadSavedTracks();
        updateSeekControls();
        seekTimeline.setCycleCount(Timeline.INDEFINITE);
        seekTimeline.play();
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        stage.setOnCloseRequest(event -> saveState());
    }

    private VBox createPlayerPage() {
        VBox page = new VBox();
        page.getStyleClass().add("page");
        page.setAlignment(Pos.CENTER);

        VBox content = new VBox(16);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(820);

        Label art = new Label("♪");
        StackPane albumArt = new StackPane(art);
        albumArt.getStyleClass().add("album-art");
        albumArt.setPrefSize(200, 200);
        albumArt.setMinSize(200, 200);
        albumArt.setMaxSize(200, 200);

        nowPlayingLabel.getStyleClass().add("now-playing");
        nowPlayingLabel.setWrapText(true);
        nowPlayingLabel.setTextAlignment(TextAlignment.CENTER);
        nowPlayingLabel.setMaxWidth(780);

        configureDetailsLabel(currentDetailsLabel);
        currentDetailsLabel.setTextAlignment(TextAlignment.CENTER);
        currentDetailsLabel.setMaxWidth(780);

        seekTimeLabel.getStyleClass().add("time-label");
        HBox seekRow = new HBox(14, seekSlider, seekTimeLabel);
        seekRow.setAlignment(Pos.CENTER);
        seekRow.setMaxWidth(780);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        Button playButton = new Button("Грати");
        playButton.getStyleClass().addAll("accent-button", "play-button");
        playButton.setOnAction(event -> play());
        Button stopButton = new Button("Стоп");
        stopButton.setOnAction(event -> stop());
        Button previousButton = new Button("Назад");
        previousButton.setOnAction(event -> playPrevious());
        Button nextButton = new Button("Далі");
        nextButton.setOnAction(event -> playNext());

        HBox transport = new HBox(8, previousButton, playButton, pauseButton, stopButton, nextButton);
        transport.setAlignment(Pos.CENTER);

        HBox modesAndVolume = new HBox(12, repeatButton, shuffleButton,
                new Label("Гучність"), volumeSlider);
        modesAndVolume.setAlignment(Pos.CENTER);
        modesAndVolume.setMaxWidth(780);

        content.getChildren().addAll(albumArt, nowPlayingLabel, currentDetailsLabel,
                seekRow, transport, modesAndVolume);
        page.getChildren().add(content);
        return page;
    }

    private VBox createPlaylistsPage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("page");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox playlistRow = new HBox(10, pageTitle("Плейлисти"), titleSpacer, playlistBox,
                createPlaylistButton, deletePlaylistButton);
        playlistRow.setAlignment(Pos.CENTER_LEFT);

        selectionLabel.getStyleClass().add("muted-label");
        configureDetailsLabel(playlistDetailsLabel);
        playlistDetailsLabel.setMaxWidth(Double.MAX_VALUE);

        Region manageSpacer = new Region();
        HBox.setHgrow(manageSpacer, Priority.ALWAYS);
        Button playSelectedButton = new Button("Грати");
        playSelectedButton.getStyleClass().add("accent-button");
        playSelectedButton.setOnAction(event -> play());
        HBox manageRow = new HBox(8, createChooseFilesButton(), playSelectedButton,
                removeSelectedButton, manageSpacer, selectionLabel);
        manageRow.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(trackList, Priority.ALWAYS);
        page.getChildren().addAll(playlistRow, trackList, playlistDetailsLabel, manageRow);
        return page;
    }

    private VBox createRadioPage() {
        VBox page = new VBox(14);
        page.getStyleClass().add("page");
        page.getChildren().add(pageTitle("Радіо"));

        HBox streamRow = new HBox(10, streamUrlField, streamPlayButton);
        streamRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(streamUrlField, Priority.ALWAYS);

        HBox searchRow = new HBox(10, stationSearchField, stationSearchButton, stationPlayButton);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(stationSearchField, Priority.ALWAYS);

        configureDetailsLabel(stationDetailsLabel);
        stationDetailsLabel.setMaxWidth(Double.MAX_VALUE);

        VBox.setVgrow(stationList, Priority.ALWAYS);
        page.getChildren().addAll(streamRow, searchRow, stationDetailsLabel, stationList);
        return page;
    }

    private VBox createEqualizerPage() {
        VBox page = new VBox(18);
        page.getStyleClass().add("page");
        page.getChildren().add(pageTitle("Еквалайзер"));

        Button resetEqualizerButton = new Button("Скинути");
        resetEqualizerButton.setOnAction(event -> {
            facade.resetEqualizer();
            equalizerPresetBox.setValue(Equalizer.Preset.FLAT);
            updateEqualizerControls();
            statusLabel.setText("Еквалайзер скинуто");
        });

        HBox presetRow = new HBox(10, new Label("Пресет"), equalizerPresetBox,
                resetEqualizerButton);
        presetRow.setAlignment(Pos.CENTER_LEFT);
        equalizerPresetBox.setPrefWidth(200);

        VBox bands = new VBox(14,
                createEqRow("Низькі", lowEqSlider),
                createEqRow("Середні", midEqSlider),
                createEqRow("Високі", highEqSlider));
        bands.getStyleClass().add("eq-bands");

        page.getChildren().addAll(presetRow, bands);
        return page;
    }

    private HBox createEqRow(String title, Slider slider) {
        Label valueLabel = new Label(formatGain((float) slider.getValue()));
        valueLabel.setMinWidth(70);
        valueLabel.getStyleClass().add("muted-label");
        slider.valueProperty().addListener(
                (observable, oldValue, newValue) -> valueLabel.setText(formatGain(newValue.floatValue())));

        Label titleLabel = new Label(title);
        titleLabel.setMinWidth(140);
        HBox.setHgrow(slider, Priority.ALWAYS);
        HBox row = new HBox(12, titleLabel, slider, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private ToggleButton createNavButton(String text, Node page, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("tab-button");
        button.setToggleGroup(group);
        button.setOnAction(event -> {
            button.setSelected(true);
            pageStack.getChildren().setAll(page);
        });
        return button;
    }

    private HBox createHeader(ToggleButton... navButtons) {
        Label logo = new Label("♪");
        logo.getStyleClass().add("header-logo");
        Label title = new Label("Music Player");
        title.getStyleClass().add("header-title");
        HBox brand = new HBox(8, logo, title);
        brand.setAlignment(Pos.CENTER_LEFT);

        HBox tabs = new HBox(2, navButtons);
        tabs.getStyleClass().add("tab-bar");
        tabs.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = new Button("—");
        minimize.getStyleClass().add("window-button");
        minimize.setOnAction(event -> {
            if (stage != null) {
                stage.setIconified(true);
            }
        });

        Button close = new Button("✕");
        close.getStyleClass().addAll("window-button", "close-button");
        close.setOnAction(event -> requestClose());

        HBox header = new HBox(24, brand, tabs, spacer, minimize, close);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);

        header.setOnMousePressed(event -> {
            if (stage == null || isButtonTarget(event.getTarget())) {
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        header.setOnMouseDragged(event -> {
            if (stage == null || isButtonTarget(event.getTarget())) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        return header;
    }

    private static boolean isButtonTarget(Object target) {
        Object current = target;
        while (current instanceof Node node) {
            if (node instanceof ButtonBase) {
                return true;
            }
            current = node.getParent();
        }
        return false;
    }

    private HBox createStatusBar() {
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox bar = new HBox(statusLabel);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Button createChooseFilesButton() {
        Button button = new Button("Додати файли");
        button.setOnAction(event -> chooseFiles());
        return button;
    }

    private ContextMenu createTrackContextMenu() {
        MenuItem playItem = new MenuItem("Відтворити");
        playItem.setOnAction(event -> playContextTrack());

        Menu addMenu = new Menu("Додати в плейлист");
        Menu moveMenu = new Menu("Перенести в плейлист");

        MenuItem removeItem = new MenuItem("Видалити");
        removeItem.getStyleClass().add("danger-menu-item");
        removeItem.setOnAction(event -> removeSelectedTracks());

        ContextMenu menu = new ContextMenu(playItem, addMenu, moveMenu,
                new SeparatorMenuItem(), removeItem);
        menu.setOnShowing(event -> {
            boolean hasSelection = !trackList.getSelectionModel().getSelectedItems().isEmpty();
            playItem.setDisable(!hasSelection);
            removeItem.setDisable(!hasSelection);

            addMenu.setDisable(!hasSelection);
            moveMenu.setDisable(!hasSelection);
            if (hasSelection) {
                fillPlaylistMenu(addMenu, false);
                fillPlaylistMenu(moveMenu, true);
            }
        });
        return menu;
    }

    private void fillPlaylistMenu(Menu menu, boolean move) {
        menu.getItems().clear();
        long activeId = facade.getActivePlaylistId();
        for (Playlist playlist : playlistBox.getItems()) {
            MenuItem item = new MenuItem(playlist.getName());
            boolean unavailable = playlist.getId() == activeId
                    || (!move && facade.isDefaultPlaylist(playlist.getId()));
            item.setDisable(unavailable);
            item.setOnAction(event -> {
                if (move) {
                    moveSelectedTracksToPlaylist(playlist);
                } else {
                    addSelectedTracksToPlaylist(playlist);
                }
            });
            menu.getItems().add(item);
        }
    }

    private void selectTrackForContextMenu(Node node) {
        contextMenuTrackIndex = -1;

        ListCell<?> cell = findListCell(node);
        if (cell == null || cell.isEmpty() || cell.getIndex() < 0) {
            return;
        }

        int index = cell.getIndex();
        contextMenuTrackIndex = index;
        if (!trackList.getSelectionModel().isSelected(index)) {
            trackList.getSelectionModel().clearSelection();
            trackList.getSelectionModel().select(index);
        }
    }

    private static ListCell<?> findListCell(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof ListCell<?> cell) {
                return cell;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Label pageTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-title");
        return label;
    }

    private void loadSavedTracks() {
        try {
            List<Track> savedTracks = facade.loadSavedTracks();
            refreshPlaylistViews();
            restoreSavedPlayerState();
            refreshTrackList(true);
            updateNowPlayingFromQueue();

            Playlist active = playlistBox.getValue();
            String playlistName = active == null ? "Плейлист" : active.getName();
            statusLabel.setText(savedTracks.isEmpty()
                    ? playlistName + " · порожньо"
                    : playlistName + " · " + savedTracks.size() + " треків");
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка завантаження: " + e.getMessage());
        }
    }

    private void refreshPlaylistViews() {
        List<Playlist> playlists = facade.loadPlaylists();
        long activeId = facade.getActivePlaylistId();

        updatingPlaylistBox = true;
        try {
            playlistBox.getItems().setAll(playlists);
            playlistBox.setValue(findPlaylist(playlists, activeId));
        } finally {
            updatingPlaylistBox = false;
        }
        updatePlaylistControls();
    }

    private static Playlist findPlaylist(List<Playlist> playlists, long playlistId) {
        Playlist found = findPlaylistById(playlists, playlistId);
        return found != null ? found : (playlists.isEmpty() ? null : playlists.get(0));
    }

    private static Playlist findPlaylistById(List<Playlist> playlists, long playlistId) {
        for (Playlist playlist : playlists) {
            if (playlist.getId() == playlistId) {
                return playlist;
            }
        }
        return null;
    }

    private void switchPlaylist() {
        if (updatingPlaylistBox) {
            return;
        }

        Playlist selected = playlistBox.getValue();
        if (selected == null || selected.getId() == facade.getActivePlaylistId()) {
            return;
        }

        try {
            facade.switchPlaylist(selected.getId());
            refreshTrackList(true);
            refreshPlaylistViews();
            updateNowPlayingFromQueue();
            statusLabel.setText(selected.getName() + " · " + facade.getTracks().size() + " треків");
            updateSeekControls();
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void createPlaylist() {
        TextInputDialog dialog = new TextInputDialog("Новий плейлист");
        dialog.setTitle("Новий плейлист");
        dialog.setHeaderText(null);
        dialog.setContentText("Назва:");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }

        try {
            Playlist playlist = facade.createPlaylist(result.get().trim());
            refreshPlaylistViews();
            statusLabel.setText("Створено: " + playlist.getName());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void deletePlaylist() {
        Playlist selected = playlistBox.getValue();
        if (selected == null) {
            statusLabel.setText("Оберіть плейлист для видалення");
            return;
        }
        if (facade.isDefaultPlaylist(selected.getId())) {
            statusLabel.setText("Базовий плейлист видалити не можна");
            return;
        }

        try {
            boolean activeDeleted = selected.getId() == facade.getActivePlaylistId();
            facade.deletePlaylist(selected.getId());
            refreshPlaylistViews();
            refreshTrackList(true);
            if (activeDeleted) {
                updateNowPlayingFromQueue();
            }
            statusLabel.setText("Видалено: " + selected.getName());
            updateSeekControls();
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void addSelectedTracksToPlaylist(Playlist target) {
        if (target == null) {
            statusLabel.setText("Оберіть плейлист");
            return;
        }
        if (facade.isDefaultPlaylist(target.getId())) {
            statusLabel.setText("Усі треки вже в базовому плейлисті");
            return;
        }

        List<Track> selected = new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            statusLabel.setText("Виділіть трек");
            return;
        }

        try {
            facade.addTracksToPlaylist(target.getId(), selected);
            statusLabel.setText("Додано: " + selected.size());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void moveSelectedTracksToPlaylist(Playlist target) {
        if (target == null) {
            statusLabel.setText("Оберіть плейлист");
            return;
        }

        List<Track> selected = new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            statusLabel.setText("Виділіть трек");
            return;
        }

        long activeId = facade.getActivePlaylistId();
        if (target.getId() == activeId) {
            statusLabel.setText("Оберіть інший плейлист");
            return;
        }

        try {
            boolean fromDefault = facade.isDefaultPlaylist(activeId);
            boolean currentMoved = !fromDefault && selected.contains(facade.getCurrentTrack());
            facade.moveTracksToPlaylist(target.getId(), selected);
            if (!fromDefault) {
                refreshTrackList(false);
                if (currentMoved) {
                    resetNowPlaying();
                }
            }
            updateSeekControls();
            statusLabel.setText((fromDefault ? "Додано: " : "Перенесено: ") + selected.size());
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void removeSelectedTracks() {
        List<Integer> selectedIndices = new ArrayList<>(
                trackList.getSelectionModel().getSelectedIndices());
        if (selectedIndices.isEmpty()) {
            statusLabel.setText("Виділіть треки для видалення");
            return;
        }

        selectedIndices.sort(Comparator.reverseOrder());
        boolean currentRemoved = selectedIndices.contains(facade.getCurrentIndex());
        int removedCount = 0;
        try {
            for (int index : selectedIndices) {
                if (facade.removeTrackAt(index) != null) {
                    removedCount++;
                }
            }
            refreshTrackList(false);
            if (currentRemoved) {
                resetNowPlaying();
            }
            updateSeekControls();
            statusLabel.setText("Видалено: " + removedCount);
        } catch (RuntimeException e) {
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private void refreshTrackList(boolean selectCurrent) {
        trackList.getItems().setAll(facade.getTracks());
        if (selectCurrent && !trackList.getItems().isEmpty()) {
            int index = Math.min(Math.max(facade.getCurrentIndex(), 0),
                    trackList.getItems().size() - 1);
            trackList.getSelectionModel().clearSelection();
            trackList.getSelectionModel().select(index);
        }
        trackList.refresh();
        updateSelectionInfo();
    }

    private void updateSelectionInfo() {
        int count = trackList.getSelectionModel().getSelectedItems().size();
        selectionLabel.setText(count == 0 ? "" : "Вибрано: " + count);

        if (count == 1) {
            showTrackDetails(playlistDetailsLabel,
                    trackList.getSelectionModel().getSelectedItem());
        } else {
            hideDetails(playlistDetailsLabel);
        }
    }

    private void showTrackDetails(Label label, Track track) {
        if (track == null) {
            hideDetails(label);
            return;
        }
        setDetailsText(label, trackDetails(track));
    }

    private void showStationDetails(RadioStation station) {
        if (station == null) {
            hideDetails(stationDetailsLabel);
            return;
        }
        setDetailsText(stationDetailsLabel, stationDetails(station));
    }

    private String trackDetails(Track track) {
        String full = track.accept(mediaInfoVisitor);
        String name = track.toString();
        String details;

        if (full.startsWith(name)) {
            details = full.substring(name.length()).trim();
        } else {
            int separator = full.indexOf(" | ");
            details = separator >= 0 ? full.substring(separator + 3).trim() : full;
        }

        if (details.startsWith("|")) {
            details = details.substring(1).trim();
        }
        return details;
    }

    private String stationDetails(RadioStation station) {
        String full = station.accept(mediaInfoVisitor);
        int separator = full.indexOf(" | ");
        return separator >= 0 ? full.substring(separator + 3).trim() : "";
    }

    private static String stationName(RadioStation station) {
        return hasText(station.getName()) ? station.getName().trim() : "Без назви";
    }

    private static void configureDetailsLabel(Label label) {
        label.getStyleClass().add("media-details");
        label.setWrapText(true);
        hideDetails(label);
    }

    private static void setDetailsText(Label label, String text) {
        boolean visible = hasText(text);
        label.setText(visible ? text : "");
        label.setVisible(visible);
        label.setManaged(visible);
    }

    private static void hideDetails(Label label) {
        setDetailsText(label, null);
    }

    private void updatePlaylistControls() {
        Playlist active = playlistBox.getValue();
        deletePlaylistButton.setDisable(active == null
                || facade.isDefaultPlaylist(active.getId())
                || playlistBox.getItems().size() <= 1);
    }

    public void saveState() {
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
        slider.setPrefWidth(360);
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

    private static String formatGain(float gainDb) {
        return String.format("%+.1f дБ", gainDb);
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

        refreshTrackList(true);
        statusLabel.setText(saveError != null ? saveError : "Додано: " + addedTracks.size());
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
            statusLabel.setText("Спочатку додайте аудіофайли");
            return;
        }

        int selectedIndex = trackList.getSelectionModel().getSelectedIndex();
        playTrack(selectedIndex >= 0 ? selectedIndex : facade.getCurrentIndex());
    }

    private void playContextTrack() {
        if (contextMenuTrackIndex >= 0 && contextMenuTrackIndex < trackList.getItems().size()) {
            playTrack(contextMenuTrackIndex);
        } else {
            play();
        }
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
        nowPlayingLabel.setText(track.toString());
        showTrackDetails(currentDetailsLabel, track);
        statusLabel.setText("Грає");

        int index = facade.getCurrentIndex();
        if (index >= 0 && index < trackList.getItems().size()) {
            trackList.getSelectionModel().clearSelection();
            trackList.getSelectionModel().select(index);
        }
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
            statusLabel.setText("Нічого не грає");
            return;
        }

        facade.togglePause();
        if (facade.isPaused()) {
            pauseButton.setText("Продовжити");
            statusLabel.setText("Пауза");
        } else {
            pauseButton.setText("Пауза");
            statusLabel.setText("Грає");
        }
    }

    private void stop() {
        facade.stop();
        pauseButton.setText("Пауза");
        statusLabel.setText("Зупинено");
        updateSeekControls();
    }

    private void resetNowPlaying() {
        nowPlayingLabel.setText("Нічого не відтворюється");
        hideDetails(currentDetailsLabel);
        pauseButton.setText("Пауза");
    }

    private void updateNowPlayingFromQueue() {
        Track current = facade.getCurrentTrack();
        nowPlayingLabel.setText(current == null ? "Нічого не відтворюється" : current.toString());
        showTrackDetails(currentDetailsLabel, current);
        pauseButton.setText("Пауза");
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
        playStream(url, stationName(station));
    }

    private void searchStations() {
        String query = stationSearchField.getText();
        if (!hasText(query)) {
            statusLabel.setText("Введіть назву або тег");
            return;
        }

        stationSearchButton.setDisable(true);
        statusLabel.setText("Пошук...");
        CompletableFuture.supplyAsync(() -> facade.searchStations(query))
                .whenComplete((stations, error) -> Platform.runLater(() -> {
                    stationSearchButton.setDisable(false);
                    if (error != null) {
                        statusLabel.setText("Помилка: " + errorMessage(error));
                        return;
                    }

                    stationList.getItems().setAll(stations);
                    showStationDetails(null);
                    statusLabel.setText(stations.isEmpty()
                            ? "Нічого не знайдено"
                            : "Знайдено: " + stations.size());
                }));
    }

    private void playStream(String url) {
        playStream(url, null);
    }

    private void playStream(String url, String detail) {
        if (!hasText(url)) {
            statusLabel.setText("Введіть адресу потоку");
            return;
        }

        String streamUrl = url.trim();
        long request = ++streamRequest;
        streamPlayButton.setDisable(true);
        stationPlayButton.setDisable(true);
        seekSlider.setDisable(true);
        statusLabel.setText("Підключення...");

        CompletableFuture.supplyAsync(() -> facade.playStream(streamUrl))
                .whenComplete((started, error) -> Platform.runLater(() -> {
                    if (request != streamRequest) {
                        return;
                    }

                    streamPlayButton.setDisable(false);
                    stationPlayButton.setDisable(stationList.getSelectionModel().getSelectedItem() == null);
                    updateSeekControls();

                    if (error != null) {
                        statusLabel.setText("Помилка: " + errorMessage(error));
                        return;
                    }
                    if (!Boolean.TRUE.equals(started)) {
                        return;
                    }

                    pauseButton.setText("Пауза");
                    nowPlayingLabel.setText(detail == null ? "Інтернет-потік" : detail);
                    setDetailsText(currentDetailsLabel, "URL: " + streamUrl);
                    statusLabel.setText("Потік");
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
            statusLabel.setText("Завершено");
            trackList.refresh();
        }
        updateSeekControls();
    }

    private void requestClose() {
        if (stage == null) {
            return;
        }

        WindowEvent closeRequest = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
        stage.fireEvent(closeRequest);
        if (!closeRequest.isConsumed()) {
            stage.close();
        }
    }
}
