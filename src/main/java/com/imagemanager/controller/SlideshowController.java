package com.imagemanager.controller;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.service.MusicService;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ImageUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
    private static final String NO_MUSIC_OPTION = "🔇 无音乐";
    private static final String CUSTOM_MUSIC_OPTION = "🎵 自定义播放";
    private static final String MUSIC_PLAYING_TEXT = "暂停音乐";
    private static final String MUSIC_PAUSED_TEXT = "继续播放";
    private static final String MUSIC_MUTED_TEXT = "播放音乐";

    /** 日期格式化器 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== FXML 注入 ====================

    @FXML private StackPane imageContainer;
    @FXML private ImageView mainImageView;
    @FXML private Label infoLabel;
    @FXML private HBox thumbnailStrip;
    @FXML private ScrollPane thumbnailStripScroll;
    @FXML private Button playButton;
    @FXML private Button prevButton;
    @FXML private Button nextButton;

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
        this.images = images;
        this.currentIndex = Math.max(0, Math.min(startIndex, images.size() - 1));

        // 加载播放间隔设置
        try {
            playIntervalSeconds = Double.parseDouble(
                    settingsDao.getValueOrDefault("slideshow_interval", "3"));
        } catch (NumberFormatException e) {
            playIntervalSeconds = 3.0;
        }

        // 构建底部缩略图条
        buildThumbnailStrip();

        // 构建自动播放定时器（使用可配置间隔）
        autoPlayTimeline = new Timeline(
                new KeyFrame(Duration.seconds(playIntervalSeconds), event -> {
                    if (currentIndex < images.size() - 1) {
                        showImage(++currentIndex);
                    } else {
                        stopAutoPlay();
                        AlertUtil.showInfo("播放完毕", "已经是最后一张了");
                    }
                })
        );
        autoPlayTimeline.setCycleCount(Timeline.INDEFINITE);

        // 绑定 ImageView 尺寸到容器
        mainImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        mainImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));

        // v2.0: 初始化音乐控件
        initMusicControls();

        // 显示初始图片
        showImage(currentIndex);
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

        currentIndex = index;
        ImageFile image = images.get(index);

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
        updateThumbnailStripHighlight(index);

        // 更新按钮状态
        prevButton.setDisable(index == 0);
        nextButton.setDisable(index == images.size() - 1);
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
        if (currentIndex > 0) {
            zoomLevel = 1.0; // 切换图片时重置缩放
            showImage(--currentIndex);
        } else {
            AlertUtil.showInfo("提示", "已经是第一张了");
        }
    }

    /**
     * 下一张。
     */
    @FXML
    private void onNext() {
        if (currentIndex < images.size() - 1) {
            zoomLevel = 1.0;
            showImage(++currentIndex);
        } else {
            AlertUtil.showInfo("提示", "已经是最后一张了");
        }
    }

    // ==================== 缩放控制 ====================

    /**
     * 放大 — 每次增加 20%。
     */
    @FXML
    private void onZoomIn() {
        if (zoomLevel < 5.0) {
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
        if (zoomLevel > 0.2) {
            zoomLevel -= 0.2;
            applyZoom();
            updateInfoBar(images.get(currentIndex));
        }
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
        isPlaying = true;
        playButton.setText("⏹ 停止");
        autoPlayTimeline.play();
        logger.info("开始自动播放（间隔: {}秒）", playIntervalSeconds);
    }

    private void stopAutoPlay() {
        isPlaying = false;
        playButton.setText("▶ 播放");
        autoPlayTimeline.stop();
        logger.info("停止自动播放");
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
        stopAutoPlay();
        musicService.stop();
        Stage stage = (Stage) mainImageView.getScene().getWindow();
        stage.close();
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
    }
}
