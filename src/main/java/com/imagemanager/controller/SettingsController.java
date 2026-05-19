package com.imagemanager.controller;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ThemeUtil;
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

    // 界面主题
    @FXML private TextField themeBackgroundField;
    @FXML private Slider themeOpacitySlider;
    @FXML private Label themeOpacityLabel;

    // 底部按钮
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private String originalBaseUrl;
    private String originalApiKey;
    private String originalModel;
    private String originalDelay;
    private String originalScanDirectory;
    private boolean saved = false;
    private boolean scanRequested = false;
    private String savedScanDirectory = "";

    @FXML
    public void initialize() {
        // 加载当前配置
        originalBaseUrl = settingsDao.getValueOrDefault("ai_base_url",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        originalApiKey = settingsDao.getValueOrDefault("ai_api_key", "");
        originalModel = settingsDao.getValueOrDefault("ai_model", "qwen-vl-plus");
        originalDelay = settingsDao.getValueOrDefault("ai_request_delay", "1500");
        originalScanDirectory = settingsDao.getValueOrDefault("scan_directory", "");
        String themeBackground = settingsDao.getValueOrDefault(ThemeUtil.THEME_BACKGROUND_PATH, "");
        double themeOpacity = ThemeUtil.parseOpacity(
                settingsDao.getValueOrDefault(ThemeUtil.THEME_BACKGROUND_OPACITY, ThemeUtil.DEFAULT_OPACITY));

        baseUrlField.setText(originalBaseUrl);
        apiKeyField.setText(originalApiKey);
        modelField.setText(originalModel);
        delayField.setText(originalDelay);
        scanDirField.setText(originalScanDirectory);
        themeBackgroundField.setText(themeBackground);
        themeOpacitySlider.setValue(themeOpacity);
        updateThemeOpacityLabel(themeOpacity);
        themeOpacitySlider.valueProperty().addListener((obs, oldVal, newVal) ->
                updateThemeOpacityLabel(newVal.doubleValue()));

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
        // 使用当前输入测试连接，但不写入正式配置。
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText().trim();
        String model = modelField.getText().trim();
        String delay = delayField.getText().trim();

        if (!validateAiConfig(baseUrl, apiKey, model, delay, true)) {
            return;
        }

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

        Thread testThread = new Thread(() -> {
            var runtimeConfig = new AIConfig.RuntimeConfig(baseUrl, apiKey, model, delay, null);
            String result = AIConfig.withTemporaryConfig(runtimeConfig,
                    () -> aiService.testConnection(testImage));
            Platform.runLater(() -> {
                testResultArea.setText(result);
                testButton.setDisable(false);
                testProgress.setVisible(false);
                testStatusLabel.setText(result.startsWith("成功") ? "测试通过" : "测试失败");
            });
        });
        testThread.setDaemon(true);
        testThread.setName("AI-Settings-Test");
        testThread.start();
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

    @FXML
    private void onBrowseThemeBackground() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择主题背景图片");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp")
        );

        File chosen = chooser.showOpenDialog(themeBackgroundField.getScene().getWindow());
        if (chosen != null) {
            themeBackgroundField.setText(chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onClearThemeBackground() {
        themeBackgroundField.clear();
    }

    private void updateThemeOpacityLabel(double value) {
        themeOpacityLabel.setText("%d%%".formatted((int) Math.round(value * 100)));
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
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText().trim();
        String model = modelField.getText().trim();
        String delay = delayField.getText().trim();
        String scanDirectory = scanDirField.getText().trim();
        String themeBackground = themeBackgroundField.getText().trim();

        if (!validateAiConfig(baseUrl, apiKey, model, delay, false)) {
            return;
        }
        if (!scanDirectory.isBlank()) {
            File scanDir = new File(scanDirectory);
            if (!scanDir.exists() || !scanDir.isDirectory()) {
                AlertUtil.showWarning("保存失败", "扫描目录不存在或不是文件夹");
                return;
            }
        }
        if (!themeBackground.isBlank()) {
            File themeFile = new File(themeBackground);
            if (!themeFile.exists() || !themeFile.isFile()) {
                AlertUtil.showWarning("保存失败", "主题背景图片不存在");
                return;
            }
        }

        // AI配置
        settingsDao.upsert("ai_base_url", baseUrl);
        settingsDao.upsert("ai_api_key", apiKey);
        settingsDao.upsert("ai_model", model);
        settingsDao.upsert("ai_request_delay", delay);

        // 扫描目录
        settingsDao.upsert("scan_directory", scanDirectory);

        // 界面主题
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_PATH, themeBackground);
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_OPACITY,
                String.valueOf(themeOpacitySlider.getValue()));

        // 幻灯片
        settingsDao.upsert("slideshow_interval", String.valueOf(intervalSpinner.getValue()));
        int selectedOrder = orderComboBox.getSelectionModel().getSelectedIndex();
        settingsDao.upsert("slideshow_order", selectedOrder == 1 ? "RANDOM" : "SEQUENTIAL");

        boolean aiChanged = !baseUrl.equals(originalBaseUrl)
                || !apiKey.equals(originalApiKey)
                || !model.equals(originalModel)
                || !delay.equals(originalDelay);
        boolean scanDirChanged = !scanDirectory.equals(originalScanDirectory);
        saved = true;
        savedScanDirectory = scanDirectory;
        scanRequested = !scanDirectory.isBlank() && (scanDirChanged || aiChanged);

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

    public boolean isSaved() {
        return saved;
    }

    public boolean isScanRequested() {
        return scanRequested;
    }

    public String getSavedScanDirectory() {
        return savedScanDirectory;
    }

    private boolean validateAiConfig(String baseUrl, String apiKey, String model,
                                     String delay, boolean requireApiKey) {
        if (baseUrl.isBlank()) {
            AlertUtil.showWarning("配置不完整", "请填写 Base URL");
            return false;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            AlertUtil.showWarning("配置无效", "Base URL 必须以 http:// 或 https:// 开头");
            return false;
        }
        if (requireApiKey && apiKey.isBlank()) {
            AlertUtil.showWarning("配置不完整", "请先填写 API Key");
            return false;
        }
        if (!apiKey.isBlank() && model.isBlank()) {
            AlertUtil.showWarning("配置不完整", "填写 API Key 后也需要填写模型名称");
            return false;
        }
        try {
            long delayMs = Long.parseLong(delay);
            if (delayMs < 0 || delayMs > 60000) {
                AlertUtil.showWarning("配置无效", "请求间隔需要在 0 到 60000 毫秒之间");
                return false;
            }
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("配置无效", "请求间隔必须是数字");
            return false;
        }
        return true;
    }
}
