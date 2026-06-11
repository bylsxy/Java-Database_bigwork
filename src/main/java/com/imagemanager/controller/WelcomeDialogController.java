package com.imagemanager.controller;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.scanner.DirectoryScanner;
import com.imagemanager.util.FileUtil;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;

/**
 * 首次启动向导控制器 — 管理目录选择、预估展示和"下次不展示"功能。
 */
public class WelcomeDialogController {

    private static final Logger logger = LoggerFactory.getLogger(WelcomeDialogController.class);
    private static final SettingsDao settingsDao = new SettingsDaoImpl();

    @FXML private TextField directoryField;
    @FXML private Button browseButton;
    @FXML private ScrollPane contentScrollPane;
    @FXML private VBox estimateBox;
    @FXML private Label estimateLabel;
    @FXML private Label warningLabel;
    @FXML private CheckBox dontShowAgainCheckBox;
    @FXML private Hyperlink settingsLink;

    private String selectedDirectory = "";
    private Consumer<Window> openSettingsHandler;
    private long estimateRequestId = 0;

    @FXML
    public void initialize() {
        prefillInitialDirectory();

        // 监听文本框变化
        directoryField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                File dir = new File(newVal);
                if (FileUtil.isUsableScanDirectory(dir)) {
                    selectedDirectory = newVal;
                    updateEstimate(dir);
                }
            }
        });
    }

    /**
     * 优先使用用户已保存的扫描目录；没有可用目录时再回退到系统 Pictures。
     */
    private void prefillInitialDirectory() {
        String savedDirectory = settingsDao.getValueOrDefault("scan_directory", "").trim();
        File initialDir = resolveExistingDirectory(savedDirectory);

        if (initialDir == null) {
            String defaultPictures = System.getProperty("user.home") + File.separator + "Pictures";
            initialDir = resolveExistingDirectory(defaultPictures);
        }

        if (initialDir != null) {
            selectedDirectory = initialDir.getAbsolutePath();
            directoryField.setText(selectedDirectory);
            updateEstimate(initialDir);
        }
    }

    private File resolveExistingDirectory(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        File dir = new File(path);
        return FileUtil.isUsableScanDirectory(dir) ? dir : null;
    }

    @FXML
    private void onBrowseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择图片扫描目录");

        // 初始目录
        String currentText = directoryField.getText();
        if (currentText != null && !currentText.isBlank()) {
            File currentDir = new File(currentText);
            if (currentDir.exists()) {
                chooser.setInitialDirectory(currentDir);
            }
        }

        Stage stage = (Stage) browseButton.getScene().getWindow();
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            if (!FileUtil.isUsableScanDirectory(chosen)) {
                warningLabel.setVisible(true);
                warningLabel.setManaged(true);
                warningLabel.setText("请选择具体图片文件夹，不要直接选择磁盘根目录。");
                return;
            }
            directoryField.setText(chosen.getAbsolutePath());
            selectedDirectory = chosen.getAbsolutePath();
            updateEstimate(chosen);
        }
    }

    @FXML
    private void onOpenSettings() {
        logger.info("用户请求打开设置页面");
        if (openSettingsHandler != null) {
            openSettingsHandler.accept(settingsLink.getScene().getWindow());
        } else {
            logger.warn("未设置打开设置页面的处理器");
        }
    }

    public void setOpenSettingsHandler(Consumer<Window> openSettingsHandler) {
        this.openSettingsHandler = openSettingsHandler;
    }

    /**
     * 预估目录中的图片数量并更新UI。
     */
    private void updateEstimate(File dir) {
        long requestId = ++estimateRequestId;
        estimateBox.setVisible(true);
        estimateBox.setManaged(true);
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        // 在后台线程快速预估
        Thread estimateThread = new Thread(() -> {
            DirectoryScanner scanner = new DirectoryScanner();
            int count = scanner.estimateImageCount(dir);

            Platform.runLater(() -> {
                if (requestId != estimateRequestId) {
                    return;
                }
                estimateLabel.setText(String.format(
                        "预估结果：在 \"%s\" 中发现约 %d 张图片",
                        dir.getName(), count));

                int batchLimit = AIConfig.getBatchLimit();
                if (count > batchLimit) {
                    warningLabel.setVisible(true);
                    warningLabel.setManaged(true);
                    warningLabel.setText(String.format(
                            "警告：该目录包含约 %d 张图片，AI识别每批最多处理 %d(max) 张。建议先用较小目录测试，或在设置页调低上限，也可随时用主界面的停止按钮中断。",
                            count, batchLimit));
                } else if (count > 50) {
                    warningLabel.setVisible(true);
                    warningLabel.setManaged(true);
                    warningLabel.setText("提示：图片数量较多，AI识别可能需要较长时间。");
                } else {
                    warningLabel.setVisible(false);
                    warningLabel.setManaged(false);
                }

                fitDialogWithinScreen();
            });
        }, "welcome-directory-estimate");
        estimateThread.setDaemon(true);
        estimateThread.start();
        Platform.runLater(this::fitDialogWithinScreen);
    }

    /**
     * 目录预估结果会动态展开内容，展开后把窗口限制在当前屏幕内。
     */
    private void fitDialogWithinScreen() {
        if (directoryField == null || directoryField.getScene() == null) {
            return;
        }

        Window window = directoryField.getScene().getWindow();
        if (!(window instanceof Stage stage)) {
            return;
        }

        Rectangle2D bounds = Screen.getScreensForRectangle(
                        stage.getX(), stage.getY(),
                        Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();

        double margin = 40;
        double maxHeight = Math.max(360, bounds.getHeight() - margin);
        double maxWidth = Math.max(560, bounds.getWidth() - margin);

        if (contentScrollPane != null) {
            double availableContentHeight = Math.max(180, maxHeight - 170);
            contentScrollPane.setMaxHeight(availableContentHeight);
        }

        stage.sizeToScene();
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }
        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }

        double x = Math.min(Math.max(stage.getX(), bounds.getMinX() + 20),
                bounds.getMaxX() - stage.getWidth() - 20);
        double y = Math.min(Math.max(stage.getY(), bounds.getMinY() + 20),
                bounds.getMaxY() - stage.getHeight() - 20);
        stage.setX(Math.max(bounds.getMinX(), x));
        stage.setY(Math.max(bounds.getMinY(), y));
    }

    /**
     * 获取用户选择的目录路径。
     */
    public String getSelectedDirectory() {
        return selectedDirectory;
    }

    /**
     * 获取是否勾选了"下次不展示"。
     */
    public boolean isDontShowAgain() {
        return dontShowAgainCheckBox.isSelected();
    }

    /**
     * 保存用户的选择到数据库。
     */
    public void saveSettings() {
        if (!selectedDirectory.isBlank()) {
            File dir = new File(selectedDirectory);
            if (!FileUtil.isUsableScanDirectory(dir)) {
                logger.warn("向导目录不是安全的扫描目录，已忽略: {}", selectedDirectory);
                selectedDirectory = "";
            } else {
                settingsDao.upsert("scan_directory", selectedDirectory);
            }
        }
        if (dontShowAgainCheckBox.isSelected()) {
            settingsDao.upsert("show_welcome", "false");
        }
        logger.info("向导设置已保存: directory={}, dontShowAgain={}",
                selectedDirectory, dontShowAgainCheckBox.isSelected());
    }

    /**
     * 检查是否需要显示欢迎向导。
     */
    public static boolean shouldShowWelcome() {
        String showWelcome = settingsDao.getValueOrDefault("show_welcome", "true");
        return "true".equalsIgnoreCase(showWelcome);
    }
}
