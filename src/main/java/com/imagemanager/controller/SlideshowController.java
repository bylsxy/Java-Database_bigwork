package com.imagemanager.controller;

import com.imagemanager.model.ImageFile;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ImageUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 幻灯片播放控制器 — 管理大图展示、前后切换、缩放和自动播放。
 * <p>
 * 功能实现：
 * <ul>
 *   <li>大图展示（ImageView, 保持比例, 居中显示在深色背景上）</li>
 *   <li>手动切换（上一张/下一张, 边界提示）</li>
 *   <li>缩放控制（放大/缩小, 每次 20%, 范围 10%~500%）</li>
 *   <li>自动播放（Timeline 定时器, 默认 1 秒间隔）</li>
 *   <li>底部缩略图条（当前图片高亮）</li>
 *   <li>图片信息条（文件名, 分辨率, 缩放比, 日期, 大小, 序号）</li>
 * </ul>
 */
public class SlideshowController {

    private static final Logger logger = LoggerFactory.getLogger(SlideshowController.class);

    /** 自动播放间隔（秒） */
    private static final double PLAY_INTERVAL_SECONDS = 1.0;

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

    // ==================== 初始化 ====================

    /**
     * 初始化幻灯片播放。
     * 由 MainController 在打开幻灯片窗口后调用。
     *
     * @param images     参与播放的图片列表
     * @param startIndex 起始图片的索引
     */
    public void initSlideshow(List<ImageFile> images, int startIndex) {
        this.images = images;
        this.currentIndex = Math.max(0, Math.min(startIndex, images.size() - 1));

        // 构建底部缩略图条
        buildThumbnailStrip();

        // 构建自动播放定时器
        autoPlayTimeline = new Timeline(
                new KeyFrame(Duration.seconds(PLAY_INTERVAL_SECONDS), event -> {
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

        // 显示初始图片
        showImage(currentIndex);
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
        logger.info("开始自动播放");
    }

    private void stopAutoPlay() {
        isPlaying = false;
        playButton.setText("▶ 播放");
        autoPlayTimeline.stop();
        logger.info("停止自动播放");
    }

    // ==================== 退出 ====================

    /**
     * 退出幻灯片播放。
     */
    @FXML
    private void onExit() {
        stopAutoPlay();
        // 关闭窗口
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
