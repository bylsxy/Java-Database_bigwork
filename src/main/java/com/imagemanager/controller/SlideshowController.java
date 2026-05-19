package com.imagemanager.controller;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.service.MusicService;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ImageUtil;
import com.imagemanager.util.ThemeUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 幻灯片播放控制器 — 管理大图展示、前后切换、缩放、自动播放和背景音乐。
 * <p>
 * v2.0 增强功能：
 * <ul>
 *   <li>多选播放 — 仅播放 Ctrl 选中的图片（由 MainController 传入子集）</li>
 *   <li>背景音乐 — 内置三首MP3 + 自定义本地文件，ComboBox选择，Slider调音量</li>
 *   <li>可配置播放间隔 — 从数据库 app_settings 读取</li>
 * </ul>
 */
public class SlideshowController {

    private static final Logger logger = LoggerFactory.getLogger(SlideshowController.class);
    private static final String NO_MUSIC_OPTION = "无音乐";
    private static final String CUSTOM_MUSIC_OPTION = "自定义播放";
    private static final String MUSIC_PLAYING_TEXT = "暂停音乐";
    private static final String MUSIC_PAUSED_TEXT = "继续播放";
    private static final String MUSIC_MUTED_TEXT = "播放音乐";
    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 5.0;

    /** 日期格式化器 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== FXML 注入 ====================

    @FXML private StackPane slideshowRoot;
    @FXML private ImageView themeBackgroundImageView;
    @FXML private BorderPane slideshowContentPane;
    @FXML private StackPane imageContainer;
    @FXML private ImageView mainImageView;
    @FXML private Label infoLabel;
    @FXML private HBox thumbnailStrip;
    @FXML private ScrollPane thumbnailStripScroll;
    @FXML private Button playButton;
    @FXML private Button prevButton;
    @FXML private Button nextButton;
    @FXML private Button fullScreenButton;
    @FXML private CheckBox loopCheckBox;

    // v2.0: 音乐控件
    @FXML private ComboBox<String> musicCombo;
    @FXML private Button musicToggleButton;
    @FXML private Slider volumeSlider;

    // ==================== 服务 ====================

    private final MusicService musicService = new MusicService();
    private final SettingsDao settingsDao = new SettingsDaoImpl();

    // ==================== 状态 ====================

    /** 参与播放的图片列表 */
    private List<ImageFile> images;

    /** 当前显示的图片索引（0-based） */
    private int currentIndex = 0;

    /** 当前缩放级别（1.0 = 100%） */
    private double zoomLevel = 1.0;

    /** 自动播放定时器 */
    private Timeline autoPlayTimeline;

    /** 是否正在自动播放 */
    private boolean isPlaying = false;

    /** 缩略图条中的 ImageView 数组（用于更新高亮状态） */
    private ImageView[] stripThumbnails;

    /** 播放间隔（秒），从设置读取 */
    private double playIntervalSeconds = 3.0;

    /** 是否按随机顺序播放，从设置页读取。 */
    private boolean randomOrder = false;

    /** 是否已经注册键盘事件，避免重复初始化时多次绑定。 */
    private boolean keyboardShortcutsRegistered = false;

    /** 自定义音乐文件路径。ComboBox 只显示固定文案，实际路径存在这里。 */
    private String customMusicPath;

    /** 避免程序主动修改 ComboBox 选中项时重复触发选择事件。 */
    private boolean updatingMusicSelection = false;

    // ==================== 初始化 ====================

    /**
     * 初始化幻灯片播放。
     * 由 MainController 在打开幻灯片窗口后调用。
     *
     * @param images     参与播放的图片列表（可能是全目录或 Ctrl 多选子集）
     * @param startIndex 起始图片的索引
     */
    public void initSlideshow(List<ImageFile> images, int startIndex) {
        if (images == null || images.isEmpty()) {
            this.images = List.of();
            return;
        }

        this.images = new ArrayList<>(images);
        this.currentIndex = Math.max(0, Math.min(startIndex, this.images.size() - 1));

        // 加载播放间隔设置
        try {
            playIntervalSeconds = Double.parseDouble(
                    settingsDao.getValueOrDefault("slideshow_interval", "3"));
        } catch (NumberFormatException e) {
            playIntervalSeconds = 3.0;
        }
        randomOrder = "RANDOM".equals(settingsDao.getValueOrDefault("slideshow_order", "SEQUENTIAL"));
        applyPlaybackOrder();

        // 构建底部缩略图条
        buildThumbnailStrip();

        // 构建自动播放定时器（使用可配置间隔）
        autoPlayTimeline = new Timeline(
                new KeyFrame(Duration.seconds(playIntervalSeconds), event -> {
                    if (!showNextImage(true)) {
                        stopAutoPlay();
                        AlertUtil.showInfo("播放完毕", "已经是最后一张了");
                    }
                })
        );
        autoPlayTimeline.setCycleCount(Timeline.INDEFINITE);

        // 绑定 ImageView 尺寸到容器
        mainImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        mainImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));
        ThemeUtil.applyThemeBackground(slideshowRoot, themeBackgroundImageView, settingsDao);
        ThemeUtil.markThemedSurface(slideshowContentPane);

        // v2.0: 初始化音乐控件
        initMusicControls();

        // 显示初始图片
        showImage(currentIndex);
        registerKeyboardShortcuts();
    }

    private void applyPlaybackOrder() {
        if (!randomOrder || images.size() <= 2) {
            return;
        }

        ImageFile startImage = images.get(currentIndex);
        List<ImageFile> shuffled = new ArrayList<>(images);
        shuffled.remove(startImage);
        Collections.shuffle(shuffled);
        shuffled.add(0, startImage);
        images = shuffled;
        currentIndex = 0;
        logger.info("幻灯片随机播放已启用，共 {} 张图片", images.size());
    }

    private void registerKeyboardShortcuts() {
        if (keyboardShortcutsRegistered) {
            return;
        }
        Platform.runLater(() -> {
            Scene scene = mainImageView.getScene();
            if (scene == null) {
                return;
            }
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
            keyboardShortcutsRegistered = true;
        });
    }

    private void handleKeyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.LEFT || code == KeyCode.UP) {
            onPrevious();
            event.consume();
        } else if (code == KeyCode.RIGHT || code == KeyCode.DOWN || code == KeyCode.SPACE) {
            onNext();
            event.consume();
        } else if (code == KeyCode.ENTER) {
            onPlayToggle();
            event.consume();
        } else if (code == KeyCode.ADD || code == KeyCode.EQUALS) {
            onZoomIn();
            event.consume();
        } else if (code == KeyCode.SUBTRACT || code == KeyCode.MINUS) {
            onZoomOut();
            event.consume();
        } else if (code == KeyCode.DIGIT0) {
            onFitToWindow();
            event.consume();
        } else if (code == KeyCode.DIGIT1) {
            onActualSize();
            event.consume();
        } else if (code == KeyCode.F11 || code == KeyCode.F) {
            onToggleFullScreen();
            event.consume();
        } else if (code == KeyCode.ESCAPE) {
            Stage stage = getStage();
            if (stage != null && stage.isFullScreen()) {
                stage.setFullScreen(false);
            } else {
                onExit();
            }
            event.consume();
        }
    }

    /**
     * 初始化音乐控件 — 内置三首音乐 + 自定义播放选项。
     */
    private void initMusicControls() {
        if (musicCombo == null) return;

        // 构建选项列表
        List<String> musicOptions = new ArrayList<>();
        musicOptions.add(NO_MUSIC_OPTION);
        musicOptions.addAll(musicService.getBuiltinMusicNames());
        musicOptions.add(CUSTOM_MUSIC_OPTION);
        musicCombo.setItems(FXCollections.observableArrayList(musicOptions));
        musicCombo.getSelectionModel().selectFirst();

        // 选择音乐时的处理
        musicCombo.setOnAction(event -> {
            if (updatingMusicSelection) {
                return;
            }
            String selected = musicCombo.getValue();
            if (selected == null || selected.equals(NO_MUSIC_OPTION)) {
                musicService.stop();
                updateMusicToggleButton();
            } else if (selected.equals(CUSTOM_MUSIC_OPTION)) {
                chooseAndPlayCustomMusic();
            } else {
                // 内置音乐
                playSelectedMusic(selected);
            }
        });

        // 音量滑块
        if (volumeSlider != null) {
            volumeSlider.setValue(musicService.getVolume());
            volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                musicService.setVolume(newVal.doubleValue());
            });
        }

        // 初始按钮状态：无音乐播放，图标为静音
        updateMusicToggleButton();
    }

    // ==================== 图片显示 ====================

    /**
     * 显示指定索引的图片。
     */
    private void showImage(int index) {
        if (images == null || images.isEmpty()) return;

        currentIndex = Math.max(0, Math.min(index, images.size() - 1));
        ImageFile image = images.get(currentIndex);

        // 加载原图
        Image fullImage = ImageUtil.loadImage(image.filePath());
        if (fullImage != null) {
            mainImageView.setImage(fullImage);
            // 应用缩放
            applyZoom();
        }

        // 更新信息条
        updateInfoBar(image);

        // 更新缩略图条高亮
        updateThumbnailStripHighlight(currentIndex);

        // 更新按钮状态
        boolean loopEnabled = loopCheckBox == null || loopCheckBox.isSelected();
        prevButton.setDisable(currentIndex == 0 && !loopEnabled);
        nextButton.setDisable(currentIndex == images.size() - 1 && !loopEnabled);
    }

    /**
     * 更新图片信息条。
     * 格式: "文件名 - 宽×高 - 缩放% - 日期 - 大小 - 序号/总数"
     */
    private void updateInfoBar(ImageFile image) {
        String dateStr = (image.modifiedAt() != null)
                ? image.modifiedAt().format(DATE_FORMAT)
                : "未知";

        int zoomPercent = (int) (zoomLevel * 100);

        String info = "%s - %s - %d%% - %s - %s - %d/%d".formatted(
                image.fileName(),
                image.resolution(),
                zoomPercent,
                dateStr,
                image.formattedSize(),
                currentIndex + 1,
                images.size()
        );

        infoLabel.setText(info);
    }

    // ==================== 导航控制 ====================

    /**
     * 上一张。
     */
    @FXML
    private void onPrevious() {
        if (!showPreviousImage()) {
            AlertUtil.showInfo("提示", "已经是第一张了");
        }
    }

    /**
     * 下一张。
     */
    @FXML
    private void onNext() {
        if (!showNextImage(true)) {
            AlertUtil.showInfo("提示", "已经是最后一张了");
        }
    }

    private boolean showPreviousImage() {
        if (images == null || images.size() <= 1) {
            return false;
        }

        int targetIndex;
        if (currentIndex > 0) {
            targetIndex = currentIndex - 1;
        } else if (isLoopEnabled()) {
            targetIndex = images.size() - 1;
        } else {
            return false;
        }

        zoomLevel = 1.0;
        showImage(targetIndex);
        return true;
    }

    private boolean showNextImage(boolean allowLoop) {
        if (images == null || images.size() <= 1) {
            return false;
        }

        int targetIndex;
        if (currentIndex < images.size() - 1) {
            targetIndex = currentIndex + 1;
        } else if (allowLoop && isLoopEnabled()) {
            targetIndex = 0;
        } else {
            return false;
        }

        zoomLevel = 1.0;
        showImage(targetIndex);
        return true;
    }

    private boolean isLoopEnabled() {
        return loopCheckBox == null || loopCheckBox.isSelected();
    }

    // ==================== 缩放控制 ====================

    /**
     * 放大 — 每次增加 20%。
     */
    @FXML
    private void onZoomIn() {
        if (zoomLevel < MAX_ZOOM) {
            zoomLevel += 0.2;
            applyZoom();
            updateInfoBar(images.get(currentIndex));
        }
    }

    /**
     * 缩小 — 每次减少 20%。
     */
    @FXML
    private void onZoomOut() {
        if (zoomLevel > MIN_ZOOM) {
            zoomLevel -= 0.2;
            applyZoom();
            updateInfoBar(images.get(currentIndex));
        }
    }

    @FXML
    private void onFitToWindow() {
        zoomLevel = 1.0;
        applyZoom();
        if (!images.isEmpty()) {
            updateInfoBar(images.get(currentIndex));
        }
    }

    @FXML
    private void onActualSize() {
        Image image = mainImageView.getImage();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }

        double fitWidth = Math.max(1, mainImageView.getFitWidth());
        double fitHeight = Math.max(1, mainImageView.getFitHeight());
        double fittedScale = Math.min(fitWidth / image.getWidth(), fitHeight / image.getHeight());
        if (fittedScale <= 0) {
            return;
        }
        zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1.0 / fittedScale));
        applyZoom();
        updateInfoBar(images.get(currentIndex));
    }

    /**
     * 应用当前缩放级别到 ImageView。
     */
    private void applyZoom() {
        mainImageView.setScaleX(zoomLevel);
        mainImageView.setScaleY(zoomLevel);
    }

    // ==================== 自动播放 ====================

    /**
     * 播放/停止切换。
     */
    @FXML
    private void onPlayToggle() {
        if (isPlaying) {
            stopAutoPlay();
        } else {
            startAutoPlay();
        }
    }

    private void startAutoPlay() {
        if (images == null || images.size() <= 1) {
            AlertUtil.showInfo("提示", "至少需要两张图片才能自动播放");
            return;
        }
        isPlaying = true;
        playButton.setText("停止");
        autoPlayTimeline.play();
        logger.info("开始自动播放（间隔: {}秒）", playIntervalSeconds);
    }

    private void stopAutoPlay() {
        isPlaying = false;
        playButton.setText("播放");
        if (autoPlayTimeline == null) {
            return;
        }
        autoPlayTimeline.stop();
        logger.info("停止自动播放");
    }

    @FXML
    private void onToggleFullScreen() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }
        stage.setFullScreen(!stage.isFullScreen());
        if (fullScreenButton != null) {
            fullScreenButton.setText(stage.isFullScreen() ? "退出全屏" : "全屏");
        }
    }

    // ==================== 音乐控制 ====================

    /**
     * 音乐播放/暂停切换按钮。
     * 行为：
     * - 如果有音乐正在播放 → 暂停
     * - 如果有音乐处于暂停状态 → 恢复播放
     * - 如果没有任何音乐（currentPlayer == null）→ 尝试根据 ComboBox 的选中项开始播放
     */
    @FXML
    private void onMusicToggle() {
        if (musicService.hasLoadedMusic()) {
            if (musicService.isPlaying()) {
                musicService.pause();
            } else {
                musicService.resume();
            }
            updateMusicToggleButton();
            return;
        }

        if (musicCombo == null) {
            return;
        }

        String selected = musicCombo.getValue();
        if (selected == null || selected.equals(NO_MUSIC_OPTION)) {
            AlertUtil.showWarning("无音乐", "请先从下拉列表中选择一首音乐");
            return;
        }

        if (selected.equals(CUSTOM_MUSIC_OPTION)) {
            if (customMusicPath != null && !customMusicPath.isBlank()) {
                playSelectedMusic(customMusicPath);
            } else {
                chooseAndPlayCustomMusic();
            }
        } else {
            // 没有任何音乐在播放，尝试从 ComboBox 选中的项开始播放
            playSelectedMusic(selected);
        }
    }

    private void chooseAndPlayCustomMusic() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择自定义音乐文件");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("音频文件", "*.mp3", "*.wav", "*.m4a", "*.aac"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(musicCombo.getScene().getWindow());
        if (selectedFile == null) {
            selectNoMusicOption();
            return;
        }

        customMusicPath = selectedFile.getAbsolutePath();
        boolean played = musicService.play(customMusicPath);
        if (played) {
            selectMusicOption(CUSTOM_MUSIC_OPTION);
        } else {
            AlertUtil.showError("播放失败", "无法播放音乐文件: " + selectedFile.getName());
            selectNoMusicOption();
        }
        updateMusicToggleButton();
    }

    private void playSelectedMusic(String musicIdentifier) {
        boolean played = musicService.play(musicIdentifier);
        if (!played) {
            AlertUtil.showError("播放失败", "无法播放选中的音乐，请检查文件是否存在或格式是否受支持。");
            selectNoMusicOption();
        }
        updateMusicToggleButton();
    }

    private void selectNoMusicOption() {
        musicService.stop();
        selectMusicOption(NO_MUSIC_OPTION);
        updateMusicToggleButton();
    }

    private void selectMusicOption(String option) {
        updatingMusicSelection = true;
        try {
            musicCombo.getSelectionModel().select(option);
        } finally {
            updatingMusicSelection = false;
        }
    }

    private void updateMusicToggleButton() {
        if (musicToggleButton == null) {
            return;
        }
        if (!musicService.hasLoadedMusic()) {
            musicToggleButton.setText(MUSIC_MUTED_TEXT);
        } else if (musicService.isPlaying()) {
            musicToggleButton.setText(MUSIC_PLAYING_TEXT);
        } else {
            musicToggleButton.setText(MUSIC_PAUSED_TEXT);
        }
    }

    // ==================== 退出 ====================

    /**
     * 退出幻灯片播放。
     */
    @FXML
    private void onExit() {
        dispose();
        Stage stage = getStage();
        if (stage != null) {
            stage.close();
        }
    }

    public void dispose() {
        stopAutoPlay();
        musicService.stop();
    }

    private Stage getStage() {
        if (mainImageView == null || mainImageView.getScene() == null) {
            return null;
        }
        return (Stage) mainImageView.getScene().getWindow();
    }

    // ==================== 底部缩略图条 ====================

    /**
     * 构建底部缩略图条。
     */
    private void buildThumbnailStrip() {
        thumbnailStrip.getChildren().clear();
        stripThumbnails = new ImageView[images.size()];

        for (int i = 0; i < images.size(); i++) {
            ImageFile image = images.get(i);
            final int idx = i;

            ImageView thumb = new ImageView();
            thumb.setFitWidth(60);
            thumb.setFitHeight(45);
            thumb.setPreserveRatio(true);
            thumb.setSmooth(true);
            thumb.getStyleClass().add("strip-thumbnail");

            // 加载缩略图
            if (image.thumbnail() != null && image.thumbnail().length > 0) {
                thumb.setImage(ImageUtil.fromBytes(image.thumbnail()));
            } else {
                thumb.setImage(ImageUtil.loadThumbnailImage(image.filePath(), 60, 45));
            }

            // 点击跳转
            thumb.setOnMouseClicked(event -> {
                zoomLevel = 1.0;
                showImage(idx);
            });

            stripThumbnails[i] = thumb;
            thumbnailStrip.getChildren().add(thumb);
        }
    }

    /**
     * 更新缩略图条的高亮状态。
     */
    private void updateThumbnailStripHighlight(int activeIndex) {
        if (stripThumbnails == null) return;

        for (int i = 0; i < stripThumbnails.length; i++) {
            stripThumbnails[i].getStyleClass().remove("active");
            if (i == activeIndex) {
                stripThumbnails[i].getStyleClass().add("active");
            }
        }
        scrollActiveThumbnailIntoView(activeIndex);
    }

    private void scrollActiveThumbnailIntoView(int activeIndex) {
        if (thumbnailStripScroll == null || stripThumbnails.length <= 1) {
            return;
        }
        Platform.runLater(() -> {
            double maxIndex = Math.max(1, stripThumbnails.length - 1);
            thumbnailStripScroll.setHvalue(Math.max(0, Math.min(1, activeIndex / maxIndex)));
        });
    }
}
