package com.imagemanager.controller;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ImageUtil;
import com.imagemanager.util.ThemeUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单张图片查看器控制器。
 */
public class ImageViewerController {

    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 5.0;

    @FXML private StackPane viewerRoot;
    @FXML private ImageView themeBackgroundImageView;
    @FXML private BorderPane viewerContentPane;
    @FXML private StackPane imageContainer;
    @FXML private ImageView mainImageView;
    @FXML private Label infoLabel;
    @FXML private Button prevButton;
    @FXML private Button nextButton;

    private final SettingsDao settingsDao = new SettingsDaoImpl();
    private List<ImageFile> images = List.of();
    private int currentIndex;
    private double zoomLevel = 1.0;
    private boolean keyboardShortcutsRegistered;
    private Consumer<ImageFile> editAction;
    private Consumer<ImageFile> playAction;

    public void initViewer(List<ImageFile> images, int startIndex) {
        if (images == null || images.isEmpty()) {
            this.images = List.of();
            updateInfo("没有可查看的图片");
            return;
        }

        this.images = new ArrayList<>(images);
        this.currentIndex = Math.max(0, Math.min(startIndex, this.images.size() - 1));

        mainImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        mainImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));
        ThemeUtil.applyThemeBackground(viewerRoot, themeBackgroundImageView, settingsDao);
        ThemeUtil.markThemedSurface(viewerContentPane);

        showImage(currentIndex);
        registerKeyboardShortcuts();
    }

    public void setEditAction(Consumer<ImageFile> editAction) {
        this.editAction = editAction;
    }

    public void setPlayAction(Consumer<ImageFile> playAction) {
        this.playAction = playAction;
    }

    @FXML
    private void onPrevious() {
        if (currentIndex <= 0) {
            AlertUtil.showInfo("提示", "已经是第一张了");
            return;
        }
        showImage(currentIndex - 1);
    }

    @FXML
    private void onNext() {
        if (currentIndex >= images.size() - 1) {
            AlertUtil.showInfo("提示", "已经是最后一张了");
            return;
        }
        showImage(currentIndex + 1);
    }

    @FXML
    private void onZoomIn() {
        if (zoomLevel < MAX_ZOOM) {
            zoomLevel = Math.min(MAX_ZOOM, zoomLevel + 0.2);
            applyZoom();
        }
    }

    @FXML
    private void onZoomOut() {
        if (zoomLevel > MIN_ZOOM) {
            zoomLevel = Math.max(MIN_ZOOM, zoomLevel - 0.2);
            applyZoom();
        }
    }

    @FXML
    private void onFitToWindow() {
        zoomLevel = 1.0;
        mainImageView.setPreserveRatio(true);
        mainImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(20));
        mainImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(20));
        mainImageView.setScaleX(1.0);
        mainImageView.setScaleY(1.0);
        updateInfoForCurrentImage();
    }

    @FXML
    private void onActualSize() {
        Image image = mainImageView.getImage();
        if (image == null) {
            return;
        }
        mainImageView.fitWidthProperty().unbind();
        mainImageView.fitHeightProperty().unbind();
        mainImageView.setFitWidth(image.getWidth());
        mainImageView.setFitHeight(image.getHeight());
        mainImageView.setScaleX(1.0);
        mainImageView.setScaleY(1.0);
        zoomLevel = 1.0;
        updateInfoForCurrentImage();
    }

    @FXML
    private void onEdit() {
        ImageFile image = currentImage();
        if (image != null && editAction != null) {
            editAction.accept(image);
        }
    }

    @FXML
    private void onPlay() {
        ImageFile image = currentImage();
        if (image != null && playAction != null) {
            playAction.accept(image);
        }
    }

    @FXML
    private void onClose() {
        Stage stage = getStage();
        if (stage != null) {
            stage.close();
        }
    }

    private void showImage(int index) {
        if (images.isEmpty()) {
            return;
        }
        currentIndex = Math.max(0, Math.min(index, images.size() - 1));
        ImageFile imageFile = images.get(currentIndex);
        Image image = ImageUtil.loadImage(imageFile.filePath());
        mainImageView.setImage(image);
        onFitToWindow();
        updateNavigationButtons();
    }

    private void applyZoom() {
        mainImageView.setScaleX(zoomLevel);
        mainImageView.setScaleY(zoomLevel);
        updateInfoForCurrentImage();
    }

    private void updateNavigationButtons() {
        if (prevButton != null) {
            prevButton.setDisable(currentIndex <= 0);
        }
        if (nextButton != null) {
            nextButton.setDisable(currentIndex >= images.size() - 1);
        }
    }

    private void updateInfoForCurrentImage() {
        ImageFile image = currentImage();
        if (image == null) {
            updateInfo("没有可查看的图片");
            return;
        }
        updateInfo("%d/%d  %s  %s  %s  缩放 %.0f%%".formatted(
                currentIndex + 1,
                images.size(),
                image.fileName(),
                image.resolution(),
                image.formattedSize(),
                zoomLevel * 100
        ));
    }

    private void updateInfo(String text) {
        if (infoLabel != null) {
            infoLabel.setText(text);
        }
    }

    private ImageFile currentImage() {
        if (images.isEmpty() || currentIndex < 0 || currentIndex >= images.size()) {
            return null;
        }
        return images.get(currentIndex);
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
        } else if (code == KeyCode.ENTER) {
            onPlay();
            event.consume();
        } else if (code == KeyCode.ESCAPE) {
            onClose();
            event.consume();
        }
    }

    private Stage getStage() {
        return mainImageView == null || mainImageView.getScene() == null
                ? null
                : (Stage) mainImageView.getScene().getWindow();
    }
}
