package com.imagemanager.controller;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.scanner.DirectoryScanner;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 首次启动向导控制器 — 管理目录选择、预估展示和"下次不展示"功能。
 */
public class WelcomeDialogController {

    private static final Logger logger = LoggerFactory.getLogger(WelcomeDialogController.class);
    private static final SettingsDao settingsDao = new SettingsDaoImpl();

    @FXML private TextField directoryField;
    @FXML private Button browseButton;
    @FXML private VBox estimateBox;
    @FXML private Label estimateLabel;
    @FXML private Label warningLabel;
    @FXML private CheckBox dontShowAgainCheckBox;
    @FXML private Hyperlink settingsLink;

    private String selectedDirectory = "";

    @FXML
    public void initialize() {
        // 设置默认目录为 Windows %USERPROFILE%\Pictures
        String defaultPictures = System.getProperty("user.home") + File.separator + "Pictures";
        File defaultDir = new File(defaultPictures);
        if (defaultDir.exists()) {
            directoryField.setText(defaultPictures);
            selectedDirectory = defaultPictures;
            updateEstimate(defaultDir);
        }

        // 监听文本框变化
        directoryField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                File dir = new File(newVal);
                if (dir.exists() && dir.isDirectory()) {
                    selectedDirectory = newVal;
                    updateEstimate(dir);
                }
            }
        });
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
            directoryField.setText(chosen.getAbsolutePath());
            selectedDirectory = chosen.getAbsolutePath();
            updateEstimate(chosen);
        }
    }

    @FXML
    private void onOpenSettings() {
        // 会在 App.java 中处理，这里仅记录
        logger.info("用户请求打开设置页面");
    }

    /**
     * 预估目录中的图片数量并更新UI。
     */
    private void updateEstimate(File dir) {
        estimateBox.setVisible(true);
        estimateBox.setManaged(true);

        // 在后台线程快速预估
        new Thread(() -> {
            DirectoryScanner scanner = new DirectoryScanner();
            int count = scanner.estimateImageCount(dir);

            javafx.application.Platform.runLater(() -> {
                estimateLabel.setText(String.format(
                        "📊 预估结果：在 \"%s\" 中发现约 %d 张图片",
                        dir.getName(), count));

                if (count > 500) {
                    warningLabel.setVisible(true);
                    warningLabel.setManaged(true);
                    warningLabel.setText(String.format(
                            "⚠️ 警告：该目录包含 %d 张图片，AI处理可能消耗大量tokens！建议选择一个更小的目录进行测试。", count));
                } else if (count > 200) {
                    warningLabel.setVisible(true);
                    warningLabel.setManaged(true);
                    warningLabel.setText("⚠️ 提示：图片较多，AI处理可能需要较长时间。");
                } else {
                    warningLabel.setVisible(false);
                    warningLabel.setManaged(false);
                }
            });
        }).start();
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
            settingsDao.upsert("scan_directory", selectedDirectory);
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
