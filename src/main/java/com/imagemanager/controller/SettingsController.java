package com.imagemanager.controller;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 设置页面控制器 — 管理 AI API 配置、扫描目录、幻灯片偏好。
 * <p>
 * 包含 API 实时测试功能：用户上传示例图片，验证模型返回效果。
 */
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsDao settingsDao = new SettingsDaoImpl();
    private final AIService aiService = new OpenAICompatibleService();

    // AI配置
    @FXML private TextField baseUrlField;
    @FXML private PasswordField apiKeyField;
    @FXML private TextField modelField;
    @FXML private TextField delayField;

    // 测试区域
    @FXML private Button testButton;
    @FXML private ProgressIndicator testProgress;
    @FXML private Label testStatusLabel;
    @FXML private TextArea testResultArea;

    // 扫描目录
    @FXML private TextField scanDirField;

    // 幻灯片
    @FXML private Spinner<Integer> intervalSpinner;
    @FXML private ComboBox<String> orderComboBox;

    // 底部按钮
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    @FXML
    public void initialize() {
        // 加载当前配置
        baseUrlField.setText(settingsDao.getValueOrDefault("ai_base_url",
                "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        apiKeyField.setText(settingsDao.getValueOrDefault("ai_api_key", ""));
        modelField.setText(settingsDao.getValueOrDefault("ai_model", "qwen-vl-plus"));
        delayField.setText(settingsDao.getValueOrDefault("ai_request_delay", "1500"));

        scanDirField.setText(settingsDao.getValueOrDefault("scan_directory", ""));

        // 幻灯片间隔 Spinner
        int currentInterval = 3;
        try {
            currentInterval = Integer.parseInt(settingsDao.getValueOrDefault("slideshow_interval", "3"));
        } catch (NumberFormatException ignored) {}
        intervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, currentInterval));

        // 播放顺序
        orderComboBox.setItems(FXCollections.observableArrayList("顺序播放", "随机播放"));
        String order = settingsDao.getValueOrDefault("slideshow_order", "SEQUENTIAL");
        orderComboBox.getSelectionModel().select("RANDOM".equals(order) ? 1 : 0);

        logger.info("设置页面初始化完成");
    }

    /**
     * 实时测试 API 连接。
     */
    @FXML
    private void onTestAPI() {
        // 先临时保存配置（不持久化），让测试使用最新输入的值
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText().trim();
        String model = modelField.getText().trim();

        if (apiKey.isBlank()) {
            testResultArea.setText("❌ 请先填写 API Key");
            return;
        }

        // 临时写入数据库以便 AIConfig 能读取
        settingsDao.upsert("ai_base_url", baseUrl);
        settingsDao.upsert("ai_api_key", apiKey);
        settingsDao.upsert("ai_model", model);

        // 选择测试图片
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择测试图片");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp")
        );

        File testImage = fileChooser.showOpenDialog(testButton.getScene().getWindow());
        if (testImage == null) {
            return;
        }

        // 在后台线程执行测试
        testButton.setDisable(true);
        testProgress.setVisible(true);
        testStatusLabel.setText("正在连接AI模型...");
        testResultArea.setText("正在分析图片: " + testImage.getName() + "\n请稍候...");

        new Thread(() -> {
            String result = aiService.testConnection(testImage);
            Platform.runLater(() -> {
                testResultArea.setText(result);
                testButton.setDisable(false);
                testProgress.setVisible(false);
                testStatusLabel.setText(result.startsWith("✅") ? "测试通过" : "测试失败");
            });
        }).start();
    }

    @FXML
    private void onBrowseScanDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择图片扫描目录");

        String currentDir = scanDirField.getText();
        if (currentDir != null && !currentDir.isBlank()) {
            File dir = new File(currentDir);
            if (dir.exists()) {
                chooser.setInitialDirectory(dir);
            }
        }

        File chosen = chooser.showDialog(scanDirField.getScene().getWindow());
        if (chosen != null) {
            scanDirField.setText(chosen.getAbsolutePath());
        }
    }

    /**
     * 重新打开首次向导。
     */
    @FXML
    private void onReopenWelcome() {
        settingsDao.upsert("show_welcome", "true");
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText("下次启动应用时将显示首次启动向导。");
        alert.showAndWait();
    }

    /**
     * 保存所有设置。
     */
    @FXML
    private void onSave() {
        // AI配置
        settingsDao.upsert("ai_base_url", baseUrlField.getText().trim());
        settingsDao.upsert("ai_api_key", apiKeyField.getText().trim());
        settingsDao.upsert("ai_model", modelField.getText().trim());
        settingsDao.upsert("ai_request_delay", delayField.getText().trim());

        // 扫描目录
        settingsDao.upsert("scan_directory", scanDirField.getText().trim());

        // 幻灯片
        settingsDao.upsert("slideshow_interval", String.valueOf(intervalSpinner.getValue()));
        int selectedOrder = orderComboBox.getSelectionModel().getSelectedIndex();
        settingsDao.upsert("slideshow_order", selectedOrder == 1 ? "RANDOM" : "SEQUENTIAL");

        logger.info("设置已保存");

        // 关闭窗口
        Stage stage = (Stage) saveButton.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void onCancel() {
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }
}
