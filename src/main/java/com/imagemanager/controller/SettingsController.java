package com.imagemanager.controller;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIModelClient;
import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ThemeUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
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

    @FXML private StackPane settingsRoot;
    @FXML private ImageView themeBackgroundImageView;
    @FXML private VBox settingsContentPane;

    // AI配置
    @FXML private TextField baseUrlField;
    @FXML private PasswordField apiKeyField;
    @FXML private ComboBox<String> modelComboBox;
    @FXML private Button refreshModelsButton;
    @FXML private Label modelStatusLabel;
    @FXML private TextField delayField;
    @FXML private Spinner<Integer> batchLimitSpinner;

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

    // 底部按钮
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private String originalBaseUrl;
    private String originalApiKey;
    private String originalModel;
    private String originalDelay;
    private String originalBatchLimit;
    private String originalScanDirectory;
    private String storedApiKey = "";
    private String environmentApiKey = "";
    private boolean saved = false;
    private boolean scanRequested = false;
    private String savedScanDirectory = "";

    @FXML
    public void initialize() {
        // 加载当前配置
        originalBaseUrl = settingsDao.getValueOrDefault("ai_base_url", AIConfig.DEFAULT_BASE_URL);
        storedApiKey = settingsDao.getValueOrDefault("ai_api_key", "");
        environmentApiKey = AIConfig.getEnvironmentApiKey();
        originalApiKey = storedApiKey.isBlank() ? environmentApiKey : storedApiKey;
        originalModel = settingsDao.getValueOrDefault("ai_model", AIConfig.DEFAULT_MODEL);
        originalDelay = settingsDao.getValueOrDefault("ai_request_delay", AIConfig.DEFAULT_DELAY);
        originalBatchLimit = String.valueOf(AIConfig.getBatchLimit());
        originalScanDirectory = settingsDao.getValueOrDefault("scan_directory", "");
        String themeBackground = settingsDao.getValueOrDefault(ThemeUtil.THEME_BACKGROUND_PATH, "");

        baseUrlField.setText(originalBaseUrl);
        apiKeyField.setText(originalApiKey);
        modelComboBox.setItems(FXCollections.observableArrayList());
        modelComboBox.setMaxWidth(Double.MAX_VALUE);
        if (!originalModel.isBlank()) {
            modelComboBox.getItems().add(originalModel);
            modelComboBox.getSelectionModel().select(originalModel);
        }
        delayField.setText(originalDelay);
        batchLimitSpinner.setEditable(true);
        var batchLimitFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                AIConfig.MIN_BATCH_LIMIT, AIConfig.MAX_BATCH_LIMIT, Integer.parseInt(originalBatchLimit));
        batchLimitFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value + "(max)";
            }

            @Override
            public Integer fromString(String value) {
                try {
                    return parseBatchLimitText(value);
                } catch (NumberFormatException e) {
                    return batchLimitSpinner.getValue() == null
                            ? Integer.parseInt(AIConfig.DEFAULT_BATCH_LIMIT)
                            : batchLimitSpinner.getValue();
                }
            }
        });
        batchLimitSpinner.setValueFactory(batchLimitFactory);
        scanDirField.setText(originalScanDirectory);
        themeBackgroundField.setText(themeBackground);
        ThemeUtil.markThemedSurface(settingsContentPane);
        updateThemePreview();
        themeBackgroundField.textProperty().addListener((obs, oldVal, newVal) -> updateThemePreview());

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

        baseUrlField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                loadModelsAsync(false);
            }
        });
        apiKeyField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                loadModelsAsync(false);
            }
        });
        loadModelsAsync(false);

        logger.info("设置页面初始化完成");
    }

    /**
     * 实时测试 API 连接。
     */
    @FXML
    private void onRefreshModels() {
        loadModelsAsync(true);
    }

    private void loadModelsAsync(boolean showWarningOnMissingKey) {
        String baseUrl = baseUrlField.getText() == null ? "" : baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        String selectedBefore = getSelectedModel();

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            modelStatusLabel.setText("填写 Base URL，并从环境变量或输入框提供 API Key 后自动获取模型列表");
            if (showWarningOnMissingKey) {
                AlertUtil.showWarning("无法获取模型列表", "请先确认 Base URL 和 API Key 已配置");
            }
            return;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            modelStatusLabel.setText("Base URL 格式不正确，无法获取模型列表");
            return;
        }

        refreshModelsButton.setDisable(true);
        modelComboBox.setDisable(true);
        modelStatusLabel.setText("正在从 CPA 代理节点获取模型列表...");

        Thread modelThread = new Thread(() -> {
            try {
                var models = AIModelClient.fetchModelIds(baseUrl, apiKey);
                Platform.runLater(() -> applyModelList(models, selectedBefore));
            } catch (Exception e) {
                logger.warn("获取模型列表失败: {}", e.getMessage());
                Platform.runLater(() -> {
                    refreshModelsButton.setDisable(false);
                    modelComboBox.setDisable(false);
                    if (modelComboBox.getItems().isEmpty() && !selectedBefore.isBlank()) {
                        modelComboBox.setItems(FXCollections.observableArrayList(selectedBefore));
                        modelComboBox.getSelectionModel().select(selectedBefore);
                    }
                    modelStatusLabel.setText("获取模型列表失败：" + e.getMessage());
                });
            }
        });
        modelThread.setDaemon(true);
        modelThread.setName("AI-Model-List");
        modelThread.start();
    }

    private void applyModelList(java.util.List<String> models, String selectedBefore) {
        modelComboBox.setItems(FXCollections.observableArrayList(models));
        String target = !selectedBefore.isBlank() ? selectedBefore : originalModel;
        if (!target.isBlank() && models.contains(target)) {
            modelComboBox.getSelectionModel().select(target);
        } else if (!models.isEmpty()) {
            modelComboBox.getSelectionModel().selectFirst();
        }
        refreshModelsButton.setDisable(false);
        modelComboBox.setDisable(false);
        modelStatusLabel.setText("已获取 " + models.size() + " 个模型，请从下拉列表选择");
    }

    private String getSelectedModel() {
        String model = modelComboBox.getSelectionModel().getSelectedItem();
        return model == null ? "" : model.trim();
    }

    @FXML
    private void onTestAPI() {
        // 使用当前输入测试连接，但不写入正式配置。
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText().trim();
        String model = getSelectedModel();
        String delay = delayField.getText().trim();
        int batchLimit = getBatchLimitOrWarn();
        if (batchLimit < 0) {
            return;
        }

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

    private void updateThemePreview() {
        String path = themeBackgroundField == null || themeBackgroundField.getText() == null
                ? ""
                : themeBackgroundField.getText().trim();
        ThemeUtil.applyThemeBackground(settingsRoot, themeBackgroundImageView, path);
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
        saveSettingsAndClose(false);
    }

    @FXML
    private void onScanCurrentDirectory() {
        saveSettingsAndClose(true);
    }

    private void saveSettingsAndClose(boolean forceScan) {
        String baseUrl = baseUrlField.getText().trim();
        String apiKey = apiKeyField.getText().trim();
        String model = getSelectedModel();
        String delay = delayField.getText().trim();
        int batchLimit = getBatchLimitOrWarn();
        String scanDirectory = scanDirField.getText().trim();
        String themeBackground = themeBackgroundField.getText().trim();

        if (batchLimit < 0) {
            return;
        }
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
        settingsDao.upsert("ai_api_key", apiKeyToStore(apiKey));
        settingsDao.upsert("ai_model", model);
        settingsDao.upsert("ai_request_delay", delay);
        settingsDao.upsert("ai_batch_limit", String.valueOf(batchLimit));

        // 扫描目录
        settingsDao.upsert("scan_directory", scanDirectory);

        // 界面主题
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_PATH, themeBackground);
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_OPACITY, ThemeUtil.DEFAULT_OPACITY);

        // 幻灯片
        settingsDao.upsert("slideshow_interval", String.valueOf(intervalSpinner.getValue()));
        int selectedOrder = orderComboBox.getSelectionModel().getSelectedIndex();
        settingsDao.upsert("slideshow_order", selectedOrder == 1 ? "RANDOM" : "SEQUENTIAL");

        boolean aiChanged = !baseUrl.equals(originalBaseUrl)
                || !apiKey.equals(originalApiKey)
                || !model.equals(originalModel)
                || !delay.equals(originalDelay)
                || !String.valueOf(batchLimit).equals(originalBatchLimit);
        boolean scanDirChanged = !scanDirectory.equals(originalScanDirectory);
        saved = true;
        savedScanDirectory = scanDirectory;
        scanRequested = !scanDirectory.isBlank() && (forceScan || scanDirChanged || aiChanged);

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

    private String apiKeyToStore(String apiKey) {
        if (storedApiKey.isBlank()
                && !environmentApiKey.isBlank()
                && environmentApiKey.equals(apiKey)) {
            return "";
        }
        return apiKey;
    }

    private int getBatchLimitOrWarn() {
        String text = batchLimitSpinner.isEditable()
                ? batchLimitSpinner.getEditor().getText().trim()
                : String.valueOf(batchLimitSpinner.getValue());
        try {
            int limit = parseBatchLimitText(text);
            if (limit < AIConfig.MIN_BATCH_LIMIT || limit > AIConfig.MAX_BATCH_LIMIT) {
                AlertUtil.showWarning("配置无效",
                        "单批上限(max)需要在 " + AIConfig.MIN_BATCH_LIMIT
                                + " 到 " + AIConfig.MAX_BATCH_LIMIT + " 张之间");
                return -1;
            }
            batchLimitSpinner.getValueFactory().setValue(limit);
            return limit;
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("配置无效", "单批上限(max)必须是数字");
            return -1;
        }
    }

    private int parseBatchLimitText(String text) {
        String normalized = (text == null ? "" : text)
                .replace("(max)", "")
                .replace("（max）", "")
                .trim();
        return Integer.parseInt(normalized);
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
            AlertUtil.showWarning("配置不完整", "请先填写 API Key，或在系统环境变量中配置密钥");
            return false;
        }
        if (!apiKey.isBlank() && model.isBlank()) {
            AlertUtil.showWarning("配置不完整", "请先刷新模型列表，并在下拉菜单中选择模型");
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
