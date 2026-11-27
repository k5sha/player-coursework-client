package ua.kpi.musicplayer.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
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
import ua.kpi.musicplayer.sync.PlaylistSyncResult;
import ua.kpi.musicplayer.sync.TrackSyncResult;
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
import java.util.function.Function;

public class MainView extends BorderPane {

    private static final Path STATE_FILE =
            Path.of(System.getProperty("user.home"), "musicplayer-state.properties");
    private static final Path CACHE_DIR =
            Path.of(System.getProperty("user.home"), "musicplayer-cache");
    private static final int BAR_WIDTH = 32;

    private final MusicPlayerFacade facade;
    private final MediaInfoVisitor mediaInfoVisitor = new SummaryMediaVisitor();

    private final ToggleButton libraryTab = new ToggleButton("[1] Бібліотека");
    private final ToggleButton radioTab = new ToggleButton("[2] Радіо");
    private final ToggleButton equalizerTab = new ToggleButton("[3] Еквалайзер");
    private final Button serverIndicator = new Button("Сервер: офлайн");

    private final ListView<Playlist> playlistList = new ListView<>();
    private final TableView<IndexedTrack> trackTable = new TableView<>();
    private final TextField filterField = new TextField();
    private final Label selectionLabel = new Label();
    private final Label trackDetailsLabel = new Label();
    private final Button addPlaylistButton = new Button("+");
    private final Button deletePlaylistButton = new Button("-");

    private final ListView<RadioStation> stationList = new ListView<>();
    private final TextField stationSearchField = new TextField();
    private final TextField streamUrlField = new TextField();
    private final Label stationDetailsLabel = new Label();
    private final Label streamStatusLabel = new Label();
    private final Button searchButton = new Button("[Пошук]");
    private final Button connectButton = new Button("[Підключити]");

    private final ComboBox<Equalizer.Preset> presetBox = new ComboBox<>();
    private Slider lowEqSlider;
    private Slider midEqSlider;
    private Slider highEqSlider;

    private final Label nowPlayingLabel = new Label("Нічого не відтворюється");
    private final Label progressBarLabel = new Label();
    private final Label timeLabel = new Label("00:00 / 00:00");
    private final Label volumeValueLabel = new Label();
    private final Button prevButton = new Button("[|<]");
    private final Button playPauseButton = new Button("[>]");
    private final Button stopButton = new Button("[■]");
    private final Button nextButton = new Button("[>|]");
    private final Button repeatButton = new Button();
    private final Button shuffleButton = new Button();
    private final Label statusLine = new Label();
    private final StackPane pageStack = new StackPane();
    private final Timeline seekTimeline =
            new Timeline(new KeyFrame(Duration.millis(500), event -> onTimelineTick()));

    private Slider volumeSlider;
    private List<Node> pages;
    private String statusText = "Готово";
    private int blinkTick;
    private int activeTab;
    private boolean updatingPlaylistSelection;
    private int contextTrackIndex = -1;
    private long streamRequest;
    private boolean syncRunning;
    private double seekableDuration;

    public MainView(MusicPlayerFacade facade) {
        this.facade = facade;

        getStyleClass().add("main-root");

        volumeSlider = new Slider(0, 100, facade.getVolume() * 100);
        volumeSlider.setPrefWidth(140);
        volumeValueLabel.getStyleClass().add("muted-label");
        volumeValueLabel.setMinWidth(44);
        volumeValueLabel.setText(Math.round(facade.getVolume() * 100) + "%");
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            facade.setVolume(newValue.floatValue() / 100.0f);
            volumeValueLabel.setText(Math.round(newValue.doubleValue()) + "%");
        });

        lowEqSlider = createEqSlider(Equalizer.LOW_BAND);
        midEqSlider = createEqSlider(Equalizer.MID_BAND);
        highEqSlider = createEqSlider(Equalizer.HIGH_BAND);

        presetBox.getItems().setAll(Equalizer.Preset.values());
        presetBox.setValue(Equalizer.Preset.FLAT);
        presetBox.setOnAction(event -> {
            Equalizer.Preset preset = presetBox.getValue();
            if (preset == null) {
                return;
            }
            facade.applyEqualizerPreset(preset);
            updateEqualizerControls();
            setStatus("Еквалайзер: " + preset.getDisplayName());
        });

        ToggleGroup navigation = new ToggleGroup();
        libraryTab.setToggleGroup(navigation);
        radioTab.setToggleGroup(navigation);
        equalizerTab.setToggleGroup(navigation);
        libraryTab.getStyleClass().add("tab-button");
        radioTab.getStyleClass().add("tab-button");
        equalizerTab.getStyleClass().add("tab-button");
        libraryTab.setOnAction(event -> showPage(0));
        radioTab.setOnAction(event -> showPage(1));
        equalizerTab.setOnAction(event -> showPage(2));
        libraryTab.setSelected(true);

        serverIndicator.getStyleClass().addAll("srv-indicator", "srv-offline");
        serverIndicator.setOnAction(event -> runBackgroundSync(true));

        addPlaylistButton.setPrefWidth(44);
        deletePlaylistButton.setPrefWidth(44);
        addPlaylistButton.setTooltip(new Tooltip("Створити плейлист"));
        deletePlaylistButton.setTooltip(new Tooltip("Видалити плейлист"));
        addPlaylistButton.setOnAction(event -> createPlaylist());
        deletePlaylistButton.setOnAction(event -> deletePlaylist());

        playlistList.setPlaceholder(new Label("Немає плейлистів"));
        playlistList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> switchPlaylist(selected));
        playlistList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Playlist playlist, boolean empty) {
                super.updateItem(playlist, empty);
                getStyleClass().remove("default-playlist");
                if (empty || playlist == null) {
                    setText(null);
                } else {
                    setText(playlistDisplayName(playlist));
                    if (facade.isDefaultPlaylist(playlist.getId())) {
                        getStyleClass().add("default-playlist");
                    }
                }
            }
        });

        filterField.setPromptText("Фільтр / пошук...");
        filterField.textProperty().addListener(
                (observable, oldValue, newValue) -> refreshTrackRows(false));

        configureTrackTable();
        trackTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        trackTable.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<IndexedTrack>) change -> updateSelectionInfo());

        configureDetailsLabel(trackDetailsLabel);
        configureDetailsLabel(stationDetailsLabel);

        stationSearchField.setPromptText("Станція або тег");
        stationList.getItems().setAll(presetStations());
        stationList.setPlaceholder(new Label("Немає станцій"));
        stationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(RadioStation station, boolean empty) {
                super.updateItem(station, empty);
                setText(empty || station == null ? null : station.toString());
            }
        });
        stationList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, station) -> {
            showStationDetails(station);
            if (station != null && hasText(station.getPlaybackUrl())) {
                streamUrlField.setText(station.getPlaybackUrl());
            }
        });
        stationList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                playSelectedStation();
            }
        });

        streamStatusLabel.getStyleClass().add("media-details");
        streamStatusLabel.setWrapText(true);
        setStreamStatus("зупинено", null);

        prevButton.setOnAction(event -> playPrevious());
        playPauseButton.setOnAction(event -> togglePlayPause());
        stopButton.setOnAction(event -> stop());
        nextButton.setOnAction(event -> playNext());
        repeatButton.setOnAction(event -> {
            facade.cycleRepeatMode();
            updateModeControls();
            setStatus(facade.getRepeatMode() == PlaybackQueue.RepeatMode.OFF
                    ? "Повтор вимкнено"
                    : "Повтор: " + repeatModeText() + " — перемішування вимкнено");
        });
        shuffleButton.setOnAction(event -> {
            facade.toggleShuffle();
            updateModeControls();
            setStatus(facade.isShuffle()
                    ? "Перемішування увімкнено — повтор вимкнено"
                    : "Перемішування вимкнено");
        });

        progressBarLabel.getStyleClass().add("progress-bar-text");
        progressBarLabel.setCursor(Cursor.HAND);
        progressBarLabel.setOnMouseClicked(event -> seekByProgressBar(event.getX()));

        Node libraryPage = createLibraryPage();
        Node radioPage = createRadioPage();
        Node equalizerPage = createEqualizerPage();
        pages = List.of(libraryPage, radioPage, equalizerPage);
        pageStack.getStyleClass().add("page-stack");
        pageStack.getChildren().add(libraryPage);

        setTop(createHeader());
        setCenter(pageStack);
        setBottom(createBottomPane());

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (getScene() == null || getScene().getFocusOwner() instanceof TextInputControl) {
                return;
            }
            switch (event.getCode()) {
                case DIGIT1, NUMPAD1 -> showPage(0);
                case DIGIT2, NUMPAD2 -> showPage(1);
                case DIGIT3, NUMPAD3 -> showPage(2);
                default -> {
                    return;
                }
            }
            event.consume();
        });

        facade.setOnTrackFinished(streamFinished ->
                Platform.runLater(() -> onTrackFinished(streamFinished)));
        facade.addObserver(state ->
                Platform.runLater(() -> updatePlayerState(state)));

        loadSavedTracks();
        updateModeControls();
        updateSeekControls();
        seekTimeline.setCycleCount(Timeline.INDEFINITE);
        seekTimeline.play();
        runBackgroundSync(false);
    }

    private HBox createHeader() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, libraryTab, radioTab, equalizerTab, spacer, serverIndicator);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox createBottomPane() {
        StackPane playerPanel = createPanel("[Плеєр]", createPlayerContent());
        VBox bottom = new VBox(playerPanel, createStatusBar());
        bottom.setPadding(new Insets(0, 10, 0, 10));
        return bottom;
    }

    private StackPane createPanel(String title, Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("tui-panel-title");
        titleLabel.setTranslateY(-9);

        StackPane panel = new StackPane(content);
        panel.getStyleClass().add("tui-panel");
        StackPane.setAlignment(titleLabel, Pos.TOP_LEFT);
        StackPane.setMargin(titleLabel, new Insets(0, 0, 0, 8));
        panel.getChildren().add(titleLabel);
        return panel;
    }

    private Node createLibraryPage() {
        VBox.setVgrow(playlistList, Priority.ALWAYS);
        HBox playlistControls = new HBox(6, addPlaylistButton, deletePlaylistButton);

        VBox playlistsContent = new VBox(6, playlistList, playlistControls);
        playlistsContent.getStyleClass().add("tui-panel-content");
        StackPane playlistsPanel = createPanel("[1] Плейлисти", playlistsContent);
        playlistsPanel.setPrefWidth(230);

        selectionLabel.getStyleClass().add("muted-label");
        Button addFilesButton = new Button("+ Додати файли");
        addFilesButton.setOnAction(event -> chooseFiles());
        HBox toolbar = new HBox(8, filterField, addFilesButton, selectionLabel);
        HBox.setHgrow(filterField, Priority.ALWAYS);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(trackTable, Priority.ALWAYS);
        VBox tracksContent = new VBox(6, toolbar, trackTable, trackDetailsLabel);
        tracksContent.getStyleClass().add("tui-panel-content");
        StackPane tracksPanel = createPanel("[2] Треки", tracksContent);
        HBox.setHgrow(tracksPanel, Priority.ALWAYS);

        HBox split = new HBox(10, playlistsPanel, tracksPanel);
        VBox page = new VBox(split);
        page.getStyleClass().add("page");
        VBox.setVgrow(split, Priority.ALWAYS);
        return page;
    }

    private Node createRadioPage() {
        searchButton.setOnAction(event -> searchStations());
        stationSearchField.setOnAction(event -> searchStations());
        Button presetsButton = new Button("[Пресети]");
        presetsButton.setOnAction(event -> showPresetStations());
        HBox searchRow = new HBox(8, stationSearchField, searchButton, presetsButton);
        HBox.setHgrow(stationSearchField, Priority.ALWAYS);

        VBox.setVgrow(stationList, Priority.ALWAYS);
        VBox stationsContent = new VBox(6, searchRow, stationList, stationDetailsLabel);
        stationsContent.getStyleClass().add("tui-panel-content");
        StackPane stationsPanel = createPanel("[Радіо]", stationsContent);
        VBox.setVgrow(stationsPanel, Priority.ALWAYS);

        streamUrlField.setPromptText("http://.../stream");
        connectButton.getStyleClass().add("accent-button");
        connectButton.setOnAction(event -> playStream(streamUrlField.getText(), null));
        Button stopStreamButton = new Button("[Стоп]");
        stopStreamButton.setOnAction(event -> stop());
        HBox streamRow = new HBox(8, streamUrlField, connectButton, stopStreamButton);
        HBox.setHgrow(streamUrlField, Priority.ALWAYS);

        VBox streamContent = new VBox(6, streamRow, streamStatusLabel);
        streamContent.getStyleClass().add("tui-panel-content");
        StackPane streamPanel = createPanel("[Потік]", streamContent);

        VBox page = new VBox(10, stationsPanel, streamPanel);
        page.getStyleClass().add("page");
        return page;
    }

    private Node createEqualizerPage() {
        Label presetTitle = new Label("Пресет");
        presetTitle.getStyleClass().add("muted-label");
        Button resetButton = new Button("[Скинути]");
        resetButton.setOnAction(event -> {
            facade.resetEqualizer();
            presetBox.setValue(Equalizer.Preset.FLAT);
            updateEqualizerControls();
            setStatus("Еквалайзер скинуто");
        });
        presetBox.setPrefWidth(200);
        HBox presetRow = new HBox(8, presetTitle, presetBox, resetButton);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        HBox bands = new HBox(48,
                createBandColumn("Низькі", lowEqSlider),
                createBandColumn("Середні", midEqSlider),
                createBandColumn("Високі", highEqSlider));
        bands.getStyleClass().add("eq-bands");
        bands.setAlignment(Pos.CENTER);

        VBox content = new VBox(18, presetRow, bands);
        content.getStyleClass().add("tui-panel-content");
        StackPane panel = createPanel("[Еквалайзер]", content);

        VBox page = new VBox(panel);
        page.getStyleClass().add("page");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    private VBox createBandColumn(String title, Slider slider) {
        Label valueLabel = new Label(formatGain((float) slider.getValue()));
        valueLabel.getStyleClass().add("muted-label");
        slider.valueProperty().addListener((observable, oldValue, newValue) ->
                valueLabel.setText(formatGain(newValue.floatValue())));

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("muted-label");
        VBox column = new VBox(6, titleLabel, slider, valueLabel);
        column.setAlignment(Pos.CENTER);
        return column;
    }

    private VBox createPlayerContent() {
        nowPlayingLabel.getStyleClass().add("now-playing");
        HBox.setHgrow(nowPlayingLabel, Priority.ALWAYS);

        Label volumeTitle = new Label("[VOL]");
        volumeTitle.getStyleClass().add("muted-label");
        volumeValueLabel.setAlignment(Pos.CENTER_RIGHT);
        HBox volumeBox = new HBox(8, volumeTitle, volumeSlider, volumeValueLabel);
        volumeBox.setAlignment(Pos.CENTER_RIGHT);

        HBox infoRow = new HBox(12, nowPlayingLabel, volumeBox);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        timeLabel.getStyleClass().add("time-label");
        Region controlSpacer = new Region();
        HBox.setHgrow(controlSpacer, Priority.ALWAYS);
        HBox controlsRow = new HBox(6, prevButton, playPauseButton, stopButton, nextButton,
                repeatButton, shuffleButton, controlSpacer, progressBarLabel, timeLabel);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(6, infoRow, controlsRow);
        content.getStyleClass().add("tui-panel-content");
        return content;
    }

    private HBox createStatusBar() {
        Label prompt = new Label(">");
        prompt.getStyleClass().add("status-prompt");
        statusLine.getStyleClass().add("status-text");
        statusLine.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLine, Priority.ALWAYS);
        HBox bar = new HBox(6, prompt, statusLine);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void configureTrackTable() {
        trackTable.setPlaceholder(new Label("Немає треків"));

        TableColumn<IndexedTrack, String> numberColumn =
                textColumn("#", 40, row -> String.valueOf(row.index() + 1));
        TableColumn<IndexedTrack, String> titleColumn =
                textColumn("Назва", 220, row -> trackTitle(row.track()));
        TableColumn<IndexedTrack, String> artistColumn =
                textColumn("Виконавець", 160, row -> orDash(row.track().getArtist()));
        TableColumn<IndexedTrack, String> albumColumn =
                textColumn("Альбом", 160, row -> orDash(row.track().getAlbum()));
        TableColumn<IndexedTrack, String> durationColumn =
                textColumn("Час", 64, row -> row.track().getDurationMs() > 0
                        ? formatDuration(row.track().getDurationMs()) : "--:--");
        TableColumn<IndexedTrack, String> stateColumn = createTrackStateColumn();

        titleColumn.prefWidthProperty().bind(trackTable.widthProperty().subtract(520));
        trackTable.getColumns().setAll(List.of(
                numberColumn, titleColumn, artistColumn, albumColumn, durationColumn, stateColumn));

        trackTable.setRowFactory(view -> {
            TableRow<IndexedTrack> row = new TableRow<>() {
                @Override
                protected void updateItem(IndexedTrack item, boolean empty) {
                    super.updateItem(item, empty);
                    boolean current = !empty && item != null && item.index() == facade.getCurrentIndex();
                    if (current && !getStyleClass().contains("current-row")) {
                        getStyleClass().add("current-row");
                    } else if (!current) {
                        getStyleClass().remove("current-row");
                    }
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && row.getItem() != null) {
                    playTrack(row.getItem().index());
                }
            });
            return row;
        });

        trackTable.setContextMenu(createTrackContextMenu());
        trackTable.setOnContextMenuRequested(event -> {
            contextTrackIndex = -1;
            Node node = event.getPickResult() == null
                    ? null : event.getPickResult().getIntersectedNode();
            TableRow<?> row = findTableRow(node);
            if (row == null || row.isEmpty()) {
                return;
            }
            if (row.getItem() instanceof IndexedTrack indexed) {
                contextTrackIndex = indexed.index();
            }
            if (!trackTable.getSelectionModel().isSelected(row.getIndex())) {
                trackTable.getSelectionModel().clearSelection();
                trackTable.getSelectionModel().select(row.getIndex());
            }
        });
    }

    private TableColumn<IndexedTrack, String> createTrackStateColumn() {
        TableColumn<IndexedTrack, String> column = new TableColumn<>("Стан");
        column.setPrefWidth(90);
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue() == null ? "" : trackState(data.getValue().track())));
        column.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("state-srv", "state-cache", "state-loc");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                if ("Кеш".equals(value)) {
                    getStyleClass().add("state-cache");
                } else if ("Сервер".equals(value)) {
                    getStyleClass().add("state-srv");
                } else {
                    getStyleClass().add("state-loc");
                }
            }
        });
        return column;
    }

    private static TableColumn<IndexedTrack, String> textColumn(
            String title, double prefWidth, Function<IndexedTrack, String> extractor) {
        TableColumn<IndexedTrack, String> column = new TableColumn<>(title);
        column.setPrefWidth(prefWidth);
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue() == null ? "" : extractor.apply(data.getValue())));
        column.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? "" : value);
            }
        });
        return column;
    }

    private static TableRow<?> findTableRow(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof TableRow<?> row) {
                return row;
            }
            current = current.getParent();
        }
        return null;
    }

    private ContextMenu createTrackContextMenu() {
        MenuItem playItem = new MenuItem("Відтворити");
        playItem.setOnAction(event -> playContextTrack());

        Menu addMenu = new Menu("Додати в плейлист");
        Menu moveMenu = new Menu("Перенести в плейлист");

        MenuItem uploadItem = new MenuItem("Завантажити на сервер");
        uploadItem.setOnAction(event -> uploadTrack(contextTrack()));

        MenuItem deleteFromServerItem = new MenuItem("Видалити з сервера");
        deleteFromServerItem.getStyleClass().add("danger-menu-item");
        deleteFromServerItem.setOnAction(event -> deleteTrackFromServer(contextTrack()));

        MenuItem removeItem = new MenuItem("Видалити");
        removeItem.getStyleClass().add("danger-menu-item");
        removeItem.setOnAction(event -> removeSelectedTracks());

        ContextMenu menu = new ContextMenu(playItem, addMenu, moveMenu,
                new SeparatorMenuItem(), uploadItem, deleteFromServerItem,
                new SeparatorMenuItem(), removeItem);
        menu.setOnShowing(event -> {
            boolean hasSelection = !selectedTracks().isEmpty();
            playItem.setDisable(!hasSelection);
            removeItem.setDisable(!hasSelection);
            addMenu.setDisable(!hasSelection);
            moveMenu.setDisable(!hasSelection);
            if (hasSelection) {
                fillPlaylistMenu(addMenu, false);
                fillPlaylistMenu(moveMenu, true);
            }

            Track track = contextTrack();
            boolean onServer = track != null && track.getServerId() > 0;
            uploadItem.setVisible(!onServer);
            deleteFromServerItem.setVisible(onServer);
        });
        return menu;
    }

    private void fillPlaylistMenu(Menu menu, boolean move) {
        menu.getItems().clear();
        long activeId = facade.getActivePlaylistId();
        for (Playlist playlist : playlistList.getItems()) {
            MenuItem item = new MenuItem(playlistDisplayName(playlist));
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

    private void showPage(int index) {
        if (index == activeTab) {
            return;
        }
        activeTab = index;
        libraryTab.setSelected(index == 0);
        radioTab.setSelected(index == 1);
        equalizerTab.setSelected(index == 2);
        pageStack.getChildren().setAll(pages.get(index));
    }

    private void onTimelineTick() {
        updateSeekControls();
        blinkTick++;
        renderStatusLine();
    }

    private void setStatus(String text) {
        statusText = text == null ? "" : text;
        renderStatusLine();
    }

    private void renderStatusLine() {
        statusLine.setText(statusText + (blinkTick % 2 == 0 ? "█" : " "));
    }

    private void loadSavedTracks() {
        try {
            List<Track> savedTracks = facade.loadSavedTracks();
            refreshPlaylistViews();
            restoreSavedPlayerState();
            refreshTrackRows(true);
            updateNowPlayingFromQueue();
            setStatus("Бібліотека: " + savedTracks.size() + " треків");
        } catch (RuntimeException e) {
            setStatus("Помилка завантаження: " + e.getMessage());
        }
    }

    private void refreshPlaylistViews() {
        List<Playlist> playlists = facade.loadPlaylists();
        long activeId = facade.getActivePlaylistId();
        int activeIndex = 0;

        updatingPlaylistSelection = true;
        try {
            playlistList.getItems().setAll(playlists);
            for (int index = 0; index < playlists.size(); index++) {
                if (playlists.get(index).getId() == activeId) {
                    activeIndex = index;
                    break;
                }
            }
            playlistList.getSelectionModel().select(activeIndex);
        } finally {
            updatingPlaylistSelection = false;
        }
        updatePlaylistControls();
    }

    private void updatePlaylistControls() {
        Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        deletePlaylistButton.setDisable(selected == null
                || facade.isDefaultPlaylist(selected.getId())
                || playlistList.getItems().size() <= 1);
    }

    private String playlistDisplayName(Playlist playlist) {
        return facade.isDefaultPlaylist(playlist.getId()) ? "Усі треки" : playlist.getName();
    }

    private void switchPlaylist(Playlist selected) {
        if (updatingPlaylistSelection || selected == null
                || selected.getId() == facade.getActivePlaylistId()) {
            return;
        }

        try {
            facade.switchPlaylist(selected.getId());
            refreshTrackRows(true);
            updateNowPlayingFromQueue();
            setStatus(playlistDisplayName(selected) + " · "
                    + facade.getTracks().size() + " треків");
            updateSeekControls();
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void createPlaylist() {
        TextInputDialog dialog = new TextInputDialog("Новий плейлист");
        dialog.setTitle("Новий плейлист");
        dialog.setHeaderText(null);
        dialog.setContentText("Назва:");
        dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().getStylesheets().add(stylesheetUrl());

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }

        try {
            Playlist playlist = facade.createPlaylist(result.get().trim());
            refreshPlaylistViews();
            setStatus("Створено: " + playlist.getName());
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void deletePlaylist() {
        Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Оберіть плейлист для видалення");
            return;
        }
        if (facade.isDefaultPlaylist(selected.getId())) {
            setStatus("Базовий плейлист видалити не можна");
            return;
        }

        try {
            boolean activeDeleted = selected.getId() == facade.getActivePlaylistId();
            facade.deletePlaylist(selected.getId());
            refreshPlaylistViews();
            refreshTrackRows(true);
            if (activeDeleted) {
                updateNowPlayingFromQueue();
            }
            setStatus("Видалено: " + selected.getName());
            updateSeekControls();
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void refreshTrackRows(boolean selectCurrent) {
        String query = filterField.getText() == null
                ? "" : filterField.getText().trim().toLowerCase();
        List<Track> tracks = facade.getTracks();

        List<IndexedTrack> rows = new ArrayList<>();
        for (int index = 0; index < tracks.size(); index++) {
            Track track = tracks.get(index);
            if (query.isEmpty() || trackMatches(track, query)) {
                rows.add(new IndexedTrack(index, track));
            }
        }
        trackTable.getItems().setAll(rows);

        if (selectCurrent && !rows.isEmpty()) {
            selectRowByTrackIndex(facade.getCurrentIndex());
        }
        trackTable.refresh();
        updateSelectionInfo();
    }

    private static boolean trackMatches(Track track, String query) {
        return containsIgnoreCase(track.getTitle(), query)
                || containsIgnoreCase(track.getArtist(), query)
                || containsIgnoreCase(track.getAlbum(), query)
                || containsIgnoreCase(track.getFileName(), query)
                || containsIgnoreCase(track.getLocation(), query);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private void selectRowByTrackIndex(int trackIndex) {
        if (trackIndex < 0) {
            return;
        }
        List<IndexedTrack> rows = trackTable.getItems();
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).index() == trackIndex) {
                trackTable.getSelectionModel().clearSelection();
                trackTable.getSelectionModel().select(row);
                return;
            }
        }
    }

    private List<Track> selectedTracks() {
        List<Track> tracks = new ArrayList<>();
        for (IndexedTrack row : trackTable.getSelectionModel().getSelectedItems()) {
            if (row != null) {
                tracks.add(row.track());
            }
        }
        return tracks;
    }

    private void updateSelectionInfo() {
        List<Track> selected = selectedTracks();
        selectionLabel.setText(selected.isEmpty() ? "" : "Вибрано: " + selected.size());
        if (selected.size() == 1) {
            setDetailsText(trackDetailsLabel, trackDetails(selected.get(0)));
        } else {
            hideDetails(trackDetailsLabel);
        }
    }

    private void addSelectedTracksToPlaylist(Playlist target) {
        if (target == null) {
            setStatus("Оберіть плейлист");
            return;
        }
        if (facade.isDefaultPlaylist(target.getId())) {
            setStatus("Усі треки вже в базовому плейлисті");
            return;
        }

        List<Track> selected = selectedTracks();
        if (selected.isEmpty()) {
            setStatus("Виділіть трек");
            return;
        }

        try {
            facade.addTracksToPlaylist(target.getId(), selected);
            setStatus("Додано: " + selected.size());
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void moveSelectedTracksToPlaylist(Playlist target) {
        if (target == null) {
            setStatus("Оберіть плейлист");
            return;
        }

        List<Track> selected = selectedTracks();
        if (selected.isEmpty()) {
            setStatus("Виділіть трек");
            return;
        }

        long activeId = facade.getActivePlaylistId();
        if (target.getId() == activeId) {
            setStatus("Оберіть інший плейлист");
            return;
        }

        try {
            boolean fromDefault = facade.isDefaultPlaylist(activeId);
            boolean currentMoved = !fromDefault && selected.contains(facade.getCurrentTrack());
            facade.moveTracksToPlaylist(target.getId(), selected);
            if (!fromDefault) {
                refreshTrackRows(false);
                if (currentMoved) {
                    resetNowPlaying();
                }
            }
            updateSeekControls();
            setStatus((fromDefault ? "Додано: " : "Перенесено: ") + selected.size());
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void removeSelectedTracks() {
        List<Integer> indices = new ArrayList<>();
        for (IndexedTrack row : trackTable.getSelectionModel().getSelectedItems()) {
            if (row != null) {
                indices.add(row.index());
            }
        }
        if (indices.isEmpty()) {
            setStatus("Виділіть треки для видалення");
            return;
        }

        indices.sort(Comparator.reverseOrder());
        boolean currentRemoved = indices.contains(facade.getCurrentIndex());
        int removedCount = 0;
        try {
            for (int index : indices) {
                if (facade.removeTrackAt(index) != null) {
                    removedCount++;
                }
            }
            refreshTrackRows(false);
            if (currentRemoved) {
                resetNowPlaying();
            }
            updateSeekControls();
            setStatus("Видалено: " + removedCount);
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private Track contextTrack() {
        if (contextTrackIndex >= 0 && contextTrackIndex < facade.getTracks().size()) {
            return facade.getTracks().get(contextTrackIndex);
        }
        IndexedTrack selected = trackTable.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.track();
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

        try {
            facade.addTracksAndSave(addedTracks);
        } catch (RuntimeException e) {
            setStatus("Не вдалося зберегти треки: " + e.getMessage());
            return;
        }

        refreshTrackRows(true);
        setStatus("Додано: " + addedTracks.size() + " — синхронізація з сервером...");
        runBackgroundSync(false);
    }

    private void uploadTrack(Track track) {
        if (track == null) {
            setStatus("Виділіть трек для завантаження");
            return;
        }
        if (track.getServerId() > 0) {
            setStatus("Трек уже на сервері #" + track.getServerId());
            return;
        }

        setStatus("Завантаження на сервер: " + track);
        CompletableFuture.supplyAsync(() -> facade.uploadTrackFile(track))
                .whenComplete((serverId, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        setStatus("Сервер: " + errorMessage(error));
                    } else {
                        setStatus("Надіслано на сервер #" + serverId + ": " + track);
                    }
                    trackTable.refresh();
                }));
    }

    private void deleteTrackFromServer(Track track) {
        if (track == null) {
            setStatus("Виділіть трек для видалення з сервера");
            return;
        }
        long serverId = track.getServerId();
        if (serverId <= 0) {
            setStatus("Трек не завантажено на сервер");
            return;
        }

        setStatus("Видалення з сервера #" + serverId + "...");
        CompletableFuture.runAsync(() -> facade.deleteTrackFromServer(track))
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        setStatus("Сервер: " + errorMessage(error));
                        return;
                    }
                    trackTable.refresh();
                    setStatus("Видалено з сервера #" + serverId + ": " + track);
                }));
    }

    private void runBackgroundSync(boolean manual) {
        if (syncRunning) {
            if (manual) {
                setStatus("Оновлення з сервера вже триває");
            }
            return;
        }

        syncRunning = true;
        serverIndicator.setDisable(true);
        setServerIndicator("Синхронізація...", "srv-busy");
        setStatus(manual ? "Оновлення з сервера..." : "Синхронізація з сервером...");

        CompletableFuture.supplyAsync(() -> {
            TrackSyncResult tracks = null;
            try {
                tracks = facade.synchronizeTracks();
            } catch (RuntimeException ignored) {
            }
            PlaylistSyncResult playlists = facade.synchronizePlaylists();
            return new SyncOutcome(playlists, tracks);
        }).whenComplete((outcome, error) -> Platform.runLater(() -> {
            syncRunning = false;
            serverIndicator.setDisable(false);
            if (error != null) {
                setServerIndicator("Сервер: офлайн", "srv-offline");
                setStatus("Сервер недоступний — працюємо локально");
                return;
            }

            boolean online = outcome.tracks() != null || outcome.playlists().isServerAvailable();
            setServerIndicator(online ? "Сервер: онлайн" : "Сервер: офлайн",
                    online ? "srv-online" : "srv-offline");

            refreshPlaylistViews();
            refreshTrackRows(true);
            setStatus(syncSummary(outcome));
        }));
    }

    private void setServerIndicator(String text, String styleClass) {
        serverIndicator.getStyleClass().removeAll("srv-online", "srv-offline", "srv-busy");
        serverIndicator.getStyleClass().add(styleClass);
        serverIndicator.setText(text);
    }

    private record SyncOutcome(PlaylistSyncResult playlists, TrackSyncResult tracks) {
    }

    private static String syncSummary(SyncOutcome outcome) {
        TrackSyncResult tracks = outcome.tracks();
        PlaylistSyncResult playlists = outcome.playlists();

        if (tracks == null && !playlists.isServerAvailable()) {
            return "Сервер недоступний — працюємо локально";
        }

        List<String> parts = new ArrayList<>();
        if (tracks == null) {
            parts.add("треки не синхронізовано");
        } else {
            addCount(parts, "надіслано треків", tracks.getUploaded());
            addCount(parts, "отримано треків", tracks.getDownloaded());
            addCount(parts, "немає на сервері", tracks.getCleared());
            addCount(parts, "з помилкою", tracks.getFailed());
        }

        if (playlists.isServerAvailable()) {
            addCount(parts, "надіслано плейлистів", playlists.getUploaded());
            addCount(parts, "створено плейлистів", playlists.getDownloaded());
        } else {
            parts.add("плейлисти не синхронізовано");
        }

        return parts.isEmpty()
                ? "Синхронізовано — без змін"
                : "Синхронізовано: " + String.join("; ", parts);
    }

    private static void addCount(List<String> parts, String label, int count) {
        if (count > 0) {
            parts.add(label + ": " + count);
        }
    }

    private void play() {
        if (facade.getTracks().isEmpty()) {
            setStatus("Спочатку додайте аудіофайли");
            return;
        }

        IndexedTrack selected = trackTable.getSelectionModel().getSelectedItem();
        playTrack(selected == null ? facade.getCurrentIndex() : selected.index());
    }

    private void playContextTrack() {
        if (contextTrackIndex >= 0 && contextTrackIndex < facade.getTracks().size()) {
            playTrack(contextTrackIndex);
        } else {
            play();
        }
    }

    private void playTrack(int index) {
        try {
            Track track = facade.playTrackAt(index);
            if (track == null) {
                setStatus("Спочатку оберіть треки");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void showPlayingTrack(Track track) {
        nowPlayingLabel.setText("♪ " + track);
        setStatus("Грає: " + track);
        selectRowByTrackIndex(facade.getCurrentIndex());
        trackTable.refresh();
        updateSeekControls();
    }

    private void playNext() {
        try {
            Track track = facade.playNext();
            if (track == null) {
                setStatus("Наступного треку немає");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void playPrevious() {
        try {
            Track track = facade.playPrevious();
            if (track == null) {
                setStatus("Попереднього треку немає");
                return;
            }
            showPlayingTrack(track);
        } catch (RuntimeException e) {
            setStatus("Помилка: " + e.getMessage());
        }
    }

    private void togglePlayPause() {
        if (facade.isPaused()) {
            facade.togglePause();
            setStatus("Продовжено");
        } else if (facade.isPlaying()) {
            facade.togglePause();
            setStatus("Пауза");
        } else {
            play();
        }
    }

    private void stop() {
        facade.stop();
        resetNowPlaying();
        setStreamStatus("зупинено", null);
        setStatus("Зупинено");
        updateSeekControls();
    }

    private void resetNowPlaying() {
        nowPlayingLabel.setText("Нічого не відтворюється");
        playPauseButton.setText("[>]");
    }

    private void updateNowPlayingFromQueue() {
        Track current = facade.getCurrentTrack();
        nowPlayingLabel.setText(current == null ? "Нічого не відтворюється" : "♪ " + current);
        playPauseButton.setText("[>]");
    }

    private void updateSeekControls() {
        if (facade.getCurrentStreamUrl() != null) {
            seekableDuration = 0;
            progressBarLabel.setText("[" + "·".repeat(BAR_WIDTH) + "]");
            timeLabel.setText("ПОТІК / LIVE");
            return;
        }

        double duration = facade.getDurationSeconds();
        double position = facade.getCurrentPositionSeconds();
        seekableDuration = facade.canSeek() && duration > 0 ? duration : 0;

        progressBarLabel.setText(progressBarText(position, duration));
        timeLabel.setText(formatDuration(Math.round(position * 1000)) + " / "
                + formatDuration(Math.round(duration * 1000)));
    }

    private void seekByProgressBar(double clickX) {
        if (seekableDuration <= 0 || !facade.canSeek() || progressBarLabel.getWidth() <= 0) {
            return;
        }

        double fraction = clickX / progressBarLabel.getWidth();
        fraction = Math.max(0, Math.min(1, fraction));
        try {
            facade.seekToSeconds(fraction * seekableDuration);
        } catch (RuntimeException e) {
            setStatus("Помилка перемотування: " + e.getMessage());
        }
        updateSeekControls();
    }

    private static String progressBarText(double position, double duration) {
        int filled = duration > 0 ? (int) Math.round(position / duration * BAR_WIDTH) : 0;
        filled = Math.max(0, Math.min(BAR_WIDTH, filled));
        StringBuilder bar = new StringBuilder("[");
        bar.append("█".repeat(filled));
        bar.append("░".repeat(BAR_WIDTH - filled));
        return bar.append("]").toString();
    }

    private void playSelectedStation() {
        RadioStation station = stationList.getSelectionModel().getSelectedItem();
        if (station == null) {
            setStatus("Оберіть станцію зі списку");
            return;
        }

        String url = station.getPlaybackUrl();
        if (!hasText(url)) {
            setStatus("У станції немає URL потоку");
            return;
        }

        streamUrlField.setText(url);
        playStream(url, stationName(station));
    }

    private void showPresetStations() {
        stationList.getItems().setAll(presetStations());
        showStationDetails(null);
        setStatus("Показано пресети станцій");
    }

    private void searchStations() {
        String query = stationSearchField.getText();
        if (!hasText(query)) {
            setStatus("Введіть назву або тег");
            return;
        }

        searchButton.setDisable(true);
        setStatus("Пошук станцій...");
        CompletableFuture.supplyAsync(() -> facade.searchStations(query))
                .whenComplete((stations, error) -> Platform.runLater(() -> {
                    searchButton.setDisable(false);
                    if (error != null) {
                        setStatus("Помилка: " + errorMessage(error));
                        return;
                    }

                    stationList.getItems().setAll(stations);
                    showStationDetails(null);
                    setStatus(stations.isEmpty()
                            ? "Станцій не знайдено"
                            : "Знайдено станцій: " + stations.size());
                }));
    }

    private void playStream(String url, String detail) {
        if (!hasText(url)) {
            setStatus("Введіть адресу потоку");
            return;
        }

        String streamUrl = url.trim();
        long request = ++streamRequest;
        connectButton.setDisable(true);
        setStatus("Підключення до потоку...");

        CompletableFuture.supplyAsync(() -> facade.playStream(streamUrl))
                .whenComplete((started, error) -> Platform.runLater(() -> {
                    if (request != streamRequest) {
                        return;
                    }

                    connectButton.setDisable(false);
                    updateSeekControls();

                    if (error != null) {
                        setStreamStatus("помилка", streamUrl);
                        setStatus("Помилка потоку: " + errorMessage(error));
                        return;
                    }
                    if (!Boolean.TRUE.equals(started)) {
                        setStreamStatus("не підключено", streamUrl);
                        setStatus("Не вдалося підключити потік");
                        return;
                    }

                    nowPlayingLabel.setText("♪ " + (detail == null ? "Інтернет-потік" : detail));
                    setStreamStatus("підключено", streamUrl);
                    setStatus("Потік підключено");
                }));
    }

    private void setStreamStatus(String state, String detail) {
        streamStatusLabel.setText("СТАТУС: " + state
                + "\nURL: " + (detail == null || detail.isBlank() ? "-" : detail));
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

    private void updatePlayerState(PlayerState state) {
        playPauseButton.setText(state.isPlaying() && !state.isPaused() ? "[||]" : "[>]");
        trackTable.refresh();
        updateSeekControls();
    }

    private void onTrackFinished(boolean streamFinished) {
        playPauseButton.setText("[>]");
        if (streamFinished) {
            setStreamStatus("завершено", null);
            setStatus("Потік завершено");
            updateSeekControls();
            return;
        }

        Track track = facade.getCurrentTrack();
        if (facade.isPlaying() && track != null) {
            showPlayingTrack(track);
        } else {
            setStatus("Відтворення завершено");
            trackTable.refresh();
        }
        updateSeekControls();
    }

    private void updateModeControls() {
        repeatButton.setText("[Повтор: " + repeatModeText() + "]");
        shuffleButton.setText("[Зміш: " + (facade.isShuffle() ? "увімк" : "вимк") + "]");
        markModeButton(repeatButton, facade.getRepeatMode() != PlaybackQueue.RepeatMode.OFF);
        markModeButton(shuffleButton, facade.isShuffle());
    }

    private static void markModeButton(Button button, boolean active) {
        button.getStyleClass().remove("mode-button-active");
        if (active) {
            button.getStyleClass().add("mode-button-active");
        }
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

    private Slider createEqSlider(int band) {
        Slider slider = new Slider(Equalizer.MIN_GAIN_DB, Equalizer.MAX_GAIN_DB,
                facade.getBandGainDb(band));
        slider.setOrientation(Orientation.VERTICAL);
        slider.setPrefHeight(170);
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
        }
    }

    private String stylesheetUrl() {
        return getClass().getResource("/ua/kpi/musicplayer/ui/styles.css").toExternalForm();
    }

    private static List<RadioStation> presetStations() {
        return List.of(
                presetStation("SomaFM Groove Salad", "https://ice1.somafm.com/groovesalad-128-mp3"),
                presetStation("SomaFM Drone Zone", "https://ice1.somafm.com/dronezone-128-mp3"),
                presetStation("SomaFM DEF CON", "https://ice1.somafm.com/defcon-128-mp3"),
                presetStation("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3"),
                presetStation("KEXP 90.3", "https://kexp.streamguys1.com/kexp160.aac"));
    }

    private static RadioStation presetStation(String name, String url) {
        RadioStation station = new RadioStation();
        station.setName(name);
        station.setUrl(url);
        station.setUrlResolved(url);
        return station;
    }

    private static String trackTitle(Track track) {
        String title = track.getTitle();
        if (hasText(title)) {
            return Track.hasTimestampPrefix(title) ? Track.normalizeFileName(title) : title.trim();
        }
        String name = hasText(track.getFileName()) ? track.getFileName() : track.getLocation();
        String normalized = Track.normalizeFileName(name);
        return normalized.isEmpty() ? "Без назви" : normalized;
    }

    private static String orDash(String value) {
        return hasText(value) ? value.trim() : "-";
    }

    private static String trackState(Track track) {
        if (track.getServerId() <= 0) {
            return "Локально";
        }
        String location = track.getLocation();
        if (hasText(location) && location.startsWith(CACHE_DIR.toString())) {
            return "Кеш";
        }
        return "Сервер";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return hasText(cause.getMessage()) ? cause.getMessage() : cause.toString();
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private record IndexedTrack(int index, Track track) {
    }
}
