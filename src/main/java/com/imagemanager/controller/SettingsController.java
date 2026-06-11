package com.imagemanager.controller;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIEndpointConfig;
import com.imagemanager.ai.AIFallbackManager;
import com.imagemanager.ai.AIModelClient;
import com.imagemanager.ai.AISettings;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ThemeUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面控制器 — 管理本机私有 AI fallback 配置、扫描目录、幻灯片偏好。
 */
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsDao settingsDao = new SettingsDaoImpl();
    private final ObservableList<AIEndpointConfig> endpoints = FXCollections.observableArrayList();

    @FXML private StackPane settingsRoot;
    @FXML private ImageView themeBackgroundImageView;
    @FXML private VBox settingsContentPane;

    @FXML private Label configPathLabel;
    @FXML private ListView<AIEndpointConfig> endpointListView;
    @FXML private TextField endpointNameField;
    @FXML private TextField endpointBaseUrlField;
    @FXML private PasswordField endpointApiKeyField;
    @FXML private ComboBox<String> modelComboBox;
    @FXML private CheckBox endpointEnabledCheckBox;
    @FXML private Button refreshModelsButton;
    @FXML private Label modelStatusLabel;
    @FXML private TextField delayField;
    @FXML private TextField circuitBreakerField;
    @FXML private Spinner<Integer> batchLimitSpinner;
    @FXML private TextArea testResultArea;

    @FXML private TextField scanDirField;
    @FXML private Spinner<Integer> intervalSpinner;
    @FXML private ComboBox<String> orderComboBox;
    @FXML private TextField themeBackgroundField;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private AIEndpointConfig selectedEndpoint;
    private String originalScanDirectory;
    private String originalEndpointSignature;
    private String originalDelay;
    private String originalBatchLimit;
    private boolean saved = false;
    private boolean scanRequested = false;
    private String savedScanDirectory = "";

    @FXML
    public void initialize() {
        AISettings aiSettings = AIConfig.loadSettings();
        endpoints.setAll(aiSettings.getEndpoints());
        originalEndpointSignature = endpointSignature(endpoints);
        originalDelay = aiSettings.getRequestDelay();
        originalBatchLimit = String.valueOf(AIConfig.getBatchLimit());

        configPathLabel.setText(AIConfig.getConfigPath().toString());
        configureEndpointList();
        configureGlobalAiControls(aiSettings);
        configureOtherSettings();

        if (!endpoints.isEmpty()) {
            endpointListView.getSelectionModel().selectFirst();
        } else {
            clearEndpointForm();
        }
        logger.info("设置页面初始化完成");
    }

    private void configureEndpointList() {
        endpointListView.setItems(endpoints);
        endpointListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(AIEndpointConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName());
            }
        });
        endpointListView.getSelectionModel().selectedItemProperty().addListener((obs, oldEndpoint, newEndpoint) -> {
            if (oldEndpoint != null) {
                saveEndpointForm(oldEndpoint);
            }
            selectedEndpoint = newEndpoint;
            loadEndpointForm(newEndpoint);
        });
        modelComboBox.setEditable(true);
    }

    private void configureGlobalAiControls(AISettings aiSettings) {
        delayField.setText(aiSettings.getRequestDelay());
        circuitBreakerField.setText(String.valueOf(aiSettings.getCircuitBreakerThreshold()));
        batchLimitSpinner.setEditable(true);
        var batchLimitFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                AIConfig.MIN_BATCH_LIMIT, AIConfig.MAX_BATCH_LIMIT, AIConfig.getBatchLimit());
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
    }

    private void configureOtherSettings() {
        originalScanDirectory = settingsDao.getValueOrDefault("scan_directory", "");
        scanDirField.setText(originalScanDirectory);
        String themeBackground = settingsDao.getValueOrDefault(ThemeUtil.THEME_BACKGROUND_PATH, "");
        themeBackgroundField.setText(themeBackground);
        ThemeUtil.markThemedSurface(settingsContentPane);
        updateThemePreview();
        themeBackgroundField.textProperty().addListener((obs, oldVal, newVal) -> updateThemePreview());

        int currentInterval = 3;
        try {
            currentInterval = Integer.parseInt(settingsDao.getValueOrDefault("slideshow_interval", "3"));
        } catch (NumberFormatException ignored) {
        }
        intervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, currentInterval));

        orderComboBox.setItems(FXCollections.observableArrayList("顺序播放", "随机播放"));
        String order = settingsDao.getValueOrDefault("slideshow_order", "SEQUENTIAL");
        orderComboBox.getSelectionModel().select("RANDOM".equals(order) ? 1 : 0);
    }

    @FXML
    private void onAddEndpoint() {
        saveCurrentEndpointForm();
        AIEndpointConfig endpoint = new AIEndpointConfig("备用" + (endpoints.size() + 1), "", "", "", true);
        endpoints.add(endpoint);
        endpointListView.getSelectionModel().select(endpoint);
        endpointListView.refresh();
    }

    @FXML
    private void onRemoveEndpoint() {
        AIEndpointConfig endpoint = endpointListView.getSelectionModel().getSelectedItem();
        if (endpoint == null) {
            return;
        }
        int index = endpointListView.getSelectionModel().getSelectedIndex();
        endpoints.remove(endpoint);
        endpointListView.getSelectionModel().select(Math.min(index, endpoints.size() - 1));
        endpointListView.refresh();
    }

    @FXML
    private void onMoveEndpointUp() {
        moveEndpoint(-1);
    }

    @FXML
    private void onMoveEndpointDown() {
        moveEndpoint(1);
    }

    private void moveEndpoint(int delta) {
        saveCurrentEndpointForm();
        int index = endpointListView.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= endpoints.size()) {
            return;
        }
        AIEndpointConfig endpoint = endpoints.remove(index);
        endpoints.add(target, endpoint);
        endpointListView.getSelectionModel().select(target);
        endpointListView.refresh();
    }

    @FXML
    private void onRefreshModels() {
        saveCurrentEndpointForm();
        AIEndpointConfig endpoint = selectedEndpoint;
        if (endpoint == null) {
            AlertUtil.showWarning("无法获取模型列表", "请先新增并选中一个端点");
            return;
        }
        if (endpoint.getBaseUrl().isBlank() || endpoint.getApiKey().isBlank()) {
            AlertUtil.showWarning("无法获取模型列表", "请先填写 Base URL 和 API Key");
            return;
        }
        if (!isHttpUrl(endpoint.getBaseUrl())) {
            AlertUtil.showWarning("配置无效", "Base URL 必须以 http:// 或 https:// 开头");
            return;
        }

        refreshModelsButton.setDisable(true);
        modelComboBox.setDisable(true);
        modelStatusLabel.setText("正在从 /models 获取模型列表...");

        Thread modelThread = new Thread(() -> {
            try {
                List<String> models = AIModelClient.fetchModelIds(endpoint.getBaseUrl(), endpoint.getApiKey());
                Platform.runLater(() -> {
                    if (selectedEndpoint == endpoint) {
                        applyModelList(models, endpoint);
                    } else {
                        refreshModelsButton.setDisable(false);
                        modelComboBox.setDisable(false);
                        modelStatusLabel.setText("模型列表已返回，请重新选择端点后刷新");
                    }
                });
            } catch (Exception e) {
                logger.warn("获取模型列表失败: {}", e.getMessage());
                Platform.runLater(() -> {
                    refreshModelsButton.setDisable(false);
                    modelComboBox.setDisable(false);
                    modelStatusLabel.setText("获取模型列表失败：" + e.getMessage());
                });
            }
        });
        modelThread.setDaemon(true);
        modelThread.setName("AI-Model-List");
        modelThread.start();
    }

    private void applyModelList(List<String> models, AIEndpointConfig endpoint) {
        modelComboBox.setItems(FXCollections.observableArrayList(models));
        String currentModel = endpoint.getModel();
        if (!currentModel.isBlank() && models.contains(currentModel)) {
            modelComboBox.getSelectionModel().select(currentModel);
        } else {
            AIModelClient.preferredModel(models).ifPresent(model -> {
                modelComboBox.getSelectionModel().select(model);
                endpoint.setModel(model);
            });
        }
        saveEndpointForm(endpoint);
        refreshModelsButton.setDisable(false);
        modelComboBox.setDisable(false);
        modelStatusLabel.setText("已获取 " + models.size() + " 个模型，请选择后保存");
        endpointListView.refresh();
    }

    @FXML
    private void onValidateAllEndpoints() {
        saveCurrentEndpointForm();
        List<AIEndpointConfig> toValidate = endpoints.stream()
                .filter(AIEndpointConfig::isEnabled)
                .filter(endpoint -> !endpoint.getBaseUrl().isBlank() && !endpoint.getApiKey().isBlank())
                .map(AIEndpointConfig::copy)
                .toList();
        if (toValidate.isEmpty()) {
            AlertUtil.showWarning("无法验证", "没有可验证的启用端点");
            return;
        }

        testResultArea.setText("正在逐个验证 /models...\n");
        Thread validationThread = new Thread(() -> {
            StringBuilder result = new StringBuilder();
            for (AIEndpointConfig endpoint : toValidate) {
                try {
                    List<String> models = AIModelClient.fetchModelIds(endpoint.getBaseUrl(), endpoint.getApiKey());
                    String preferred = AIModelClient.preferredModel(models).orElse("");
                    result.append("[通过] ")
                            .append(endpoint.getName())
                            .append("：")
                            .append(models.size())
                            .append(" 个模型");
                    if (!preferred.isBlank()) {
                        result.append("，建议 ").append(preferred);
                    }
                    result.append("\n");
                } catch (Exception e) {
                    result.append("[失败] ")
                            .append(endpoint.getName())
                            .append("：")
                            .append(e.getMessage())
                            .append("\n");
                }
                Platform.runLater(() -> testResultArea.setText(result.toString()));
            }
        });
        validationThread.setDaemon(true);
        validationThread.setName("AI-Endpoint-Validate");
        validationThread.start();
    }

    @FXML
    private void onResetCircuitBreakers() {
        AIFallbackManager.resetAll();
        modelStatusLabel.setText("已重置本次会话中的熔断状态");
    }

    @FXML
    private void onRestoreDefaultFallback() {
        try {
            AISettings settings = AIConfig.restoreRecoverableSettings();
            applyAiSettings(settings);
            AIFallbackManager.resetAll();
            testResultArea.setText("已恢复本机默认 fallback 配置。\n配置文件：" + AIConfig.getConfigPath()
                    + "\n默认来源：" + AIConfig.getDefaultConfigPath());
            AlertUtil.showInfo("已恢复", "已从本机默认/last-good 配置恢复 fallback 列表。");
        } catch (IOException e) {
            logger.error("恢复本机默认 AI fallback 失败", e);
            AlertUtil.showError("恢复失败", "没有可恢复的本机默认 fallback，或配置文件无法读取：" + e.getMessage());
        }
    }

    @FXML
    private void onTestWithImage() {
        saveCurrentEndpointForm();
        String delay = delayField.getText().trim();
        String circuitBreaker = circuitBreakerField.getText().trim();
        int batchLimit = getBatchLimitOrWarn();
        if (batchLimit < 0 || !validateAiConfig(delay, circuitBreaker) || !validateEndpointList()) {
            return;
        }
        AISettings testSettings = buildAiSettings(delay, circuitBreaker, batchLimit);
        if (AIConfig.configuredEndpoints(testSettings).isEmpty()) {
            AlertUtil.showWarning("无法测试", "没有启用且完整的 fallback 端点，请先恢复默认或新增端点。");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择用于 AI 测试的图片");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp")
        );
        File chosen = chooser.showOpenDialog(testResultArea.getScene().getWindow());
        if (chosen == null) {
            return;
        }

        testResultArea.setText("正在发送测试图片，请稍候...\n" + chosen.getAbsolutePath());
        Thread testThread = new Thread(() -> {
            String result = AIConfig.withTemporarySettings(testSettings,
                    () -> new OpenAICompatibleService().testConnection(chosen));
            Platform.runLater(() -> testResultArea.setText(result));
        });
        testThread.setDaemon(true);
        testThread.setName("AI-Image-Test");
        testThread.start();
    }

    private void loadEndpointForm(AIEndpointConfig endpoint) {
        if (endpoint == null) {
            clearEndpointForm();
            return;
        }
        endpointNameField.setDisable(false);
        endpointBaseUrlField.setDisable(false);
        endpointApiKeyField.setDisable(false);
        modelComboBox.setDisable(false);
        endpointEnabledCheckBox.setDisable(false);
        refreshModelsButton.setDisable(false);

        endpointNameField.setText(endpoint.getName());
        endpointBaseUrlField.setText(endpoint.getBaseUrl());
        endpointApiKeyField.setText(endpoint.getApiKey());
        modelComboBox.setItems(FXCollections.observableArrayList());
        if (!endpoint.getModel().isBlank()) {
            modelComboBox.getItems().add(endpoint.getModel());
            modelComboBox.getSelectionModel().select(endpoint.getModel());
            modelComboBox.getEditor().setText(endpoint.getModel());
        } else {
            modelComboBox.getEditor().clear();
        }
        endpointEnabledCheckBox.setSelected(endpoint.isEnabled());
        modelStatusLabel.setText(endpoint.isComplete()
                ? "可刷新 /models 以验证当前端点"
                : "请填写 Base URL、API Key 和模型");
    }

    private void clearEndpointForm() {
        selectedEndpoint = null;
        endpointNameField.clear();
        endpointBaseUrlField.clear();
        endpointApiKeyField.clear();
        modelComboBox.setItems(FXCollections.observableArrayList());
        modelComboBox.getEditor().clear();
        endpointEnabledCheckBox.setSelected(false);

        endpointNameField.setDisable(true);
        endpointBaseUrlField.setDisable(true);
        endpointApiKeyField.setDisable(true);
        modelComboBox.setDisable(true);
        endpointEnabledCheckBox.setDisable(true);
        refreshModelsButton.setDisable(true);
        modelStatusLabel.setText("请先新增一个端点");
    }

    private void saveCurrentEndpointForm() {
        if (selectedEndpoint != null) {
            saveEndpointForm(selectedEndpoint);
        }
    }

    private void saveEndpointForm(AIEndpointConfig endpoint) {
        if (endpoint == null || endpointNameField == null || endpointNameField.isDisabled()) {
            return;
        }
        endpoint.setName(endpointNameField.getText());
        endpoint.setBaseUrl(endpointBaseUrlField.getText());
        endpoint.setApiKey(endpointApiKeyField.getText());
        endpoint.setModel(getSelectedModel());
        endpoint.setEnabled(endpointEnabledCheckBox.isSelected());
    }

    private String getSelectedModel() {
        String selected = modelComboBox.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isBlank()) {
            return selected.trim();
        }
        String typed = modelComboBox.getEditor() == null ? "" : modelComboBox.getEditor().getText();
        return typed == null ? "" : typed.trim();
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

    @FXML
    private void onReopenWelcome() {
        settingsDao.upsert("show_welcome", "true");
        AlertUtil.showInfo("提示", "下次启动应用时将显示首次启动向导。");
    }

    @FXML
    private void onSave() {
        saveSettingsAndClose(false);
    }

    @FXML
    private void onScanCurrentDirectory() {
        saveSettingsAndClose(true);
    }

    private void saveSettingsAndClose(boolean forceScan) {
        saveCurrentEndpointForm();
        String delay = delayField.getText().trim();
        String circuitBreaker = circuitBreakerField.getText().trim();
        int batchLimit = getBatchLimitOrWarn();
        String scanDirectory = scanDirField.getText().trim();
        String themeBackground = themeBackgroundField.getText().trim();

        if (batchLimit < 0 || !validateAiConfig(delay, circuitBreaker)) {
            return;
        }
        if (!validateEndpointList()) {
            return;
        }
        if (!hasAnyCompleteEndpoint(endpoints) && AIConfig.hasRecoverableFallbackSettings()) {
            AlertUtil.showWarning("已阻止清空 fallback",
                    "当前 AI fallback 列表为空。为避免误删演示配置，本次不会覆盖本机配置；请点击“恢复默认 fallback”后再保存。");
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

        AISettings settings = buildAiSettings(delay, circuitBreaker, batchLimit);
        try {
            AIConfig.saveSettings(settings);
        } catch (IOException e) {
            logger.error("保存本机 AI 配置失败", e);
            AlertUtil.showError("保存失败", "无法写入本机 AI 配置：" + e.getMessage());
            return;
        }

        settingsDao.upsert("scan_directory", scanDirectory);
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_PATH, themeBackground);
        settingsDao.upsert(ThemeUtil.THEME_BACKGROUND_OPACITY, ThemeUtil.DEFAULT_OPACITY);
        settingsDao.upsert("slideshow_interval", String.valueOf(intervalSpinner.getValue()));
        int selectedOrder = orderComboBox.getSelectionModel().getSelectedIndex();
        settingsDao.upsert("slideshow_order", selectedOrder == 1 ? "RANDOM" : "SEQUENTIAL");

        boolean aiChanged = !endpointSignature(endpoints).equals(originalEndpointSignature)
                || !delay.equals(originalDelay)
                || !String.valueOf(batchLimit).equals(originalBatchLimit);
        boolean scanDirChanged = !scanDirectory.equals(originalScanDirectory);
        saved = true;
        savedScanDirectory = scanDirectory;
        scanRequested = !scanDirectory.isBlank() && (forceScan || scanDirChanged || aiChanged);

        Stage stage = (Stage) saveButton.getScene().getWindow();
        stage.close();
    }

    private boolean validateEndpointList() {
        for (AIEndpointConfig endpoint : endpoints) {
            if (!endpoint.getBaseUrl().isBlank() && !isHttpUrl(endpoint.getBaseUrl())) {
                AlertUtil.showWarning("配置无效", endpoint.getName() + " 的 Base URL 必须以 http:// 或 https:// 开头");
                return false;
            }
            if (endpoint.isEnabled() && (!endpoint.getBaseUrl().isBlank()
                    || !endpoint.getApiKey().isBlank()
                    || !endpoint.getModel().isBlank())
                    && !endpoint.isComplete()) {
                AlertUtil.showWarning("配置不完整", endpoint.getName() + " 启用时必须填写 Base URL、API Key 和模型");
                return false;
            }
        }
        return true;
    }

    private AISettings buildAiSettings(String delay, String circuitBreaker, int batchLimit) {
        AISettings settings = new AISettings();
        settings.setEndpoints(endpoints.stream().map(AIEndpointConfig::copy).toList());
        settings.setRequestDelay(delay);
        settings.setMaxRetries(AIConfig.DEFAULT_MAX_RETRIES);
        settings.setBatchLimit(batchLimit);
        settings.setCircuitBreakerThreshold(Integer.parseInt(circuitBreaker));
        return settings;
    }

    private void applyAiSettings(AISettings settings) {
        AISettings normalized = settings == null ? new AISettings() : settings;
        endpoints.setAll(normalized.getEndpoints());
        delayField.setText(normalized.getRequestDelay());
        circuitBreakerField.setText(String.valueOf(normalized.getCircuitBreakerThreshold()));
        if (batchLimitSpinner.getValueFactory() != null) {
            int limit = Math.max(AIConfig.MIN_BATCH_LIMIT,
                    Math.min(normalized.getBatchLimit(), AIConfig.MAX_BATCH_LIMIT));
            batchLimitSpinner.getValueFactory().setValue(limit);
        }
        selectedEndpoint = null;
        endpointListView.getSelectionModel().clearSelection();
        endpointListView.refresh();
        if (!endpoints.isEmpty()) {
            endpointListView.getSelectionModel().selectFirst();
        } else {
            clearEndpointForm();
        }
        originalEndpointSignature = endpointSignature(endpoints);
        originalDelay = normalized.getRequestDelay();
        originalBatchLimit = String.valueOf(AIConfig.getBatchLimit());
    }

    private boolean hasAnyCompleteEndpoint(List<AIEndpointConfig> endpointList) {
        for (AIEndpointConfig endpoint : endpointList) {
            if (endpoint != null && endpoint.isEnabled() && endpoint.isComplete()) {
                return true;
            }
        }
        return false;
    }

    private boolean validateAiConfig(String delay, String circuitBreaker) {
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
        try {
            int threshold = Integer.parseInt(circuitBreaker);
            if (threshold < 1 || threshold > 20) {
                AlertUtil.showWarning("配置无效", "熔断阈值需要在 1 到 20 之间");
                return false;
            }
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("配置无效", "熔断阈值必须是数字");
            return false;
        }
        return true;
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

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String endpointSignature(List<AIEndpointConfig> endpointList) {
        List<String> rows = new ArrayList<>();
        for (AIEndpointConfig endpoint : endpointList) {
            rows.add(endpoint.getName() + "|" + endpoint.getBaseUrl() + "|"
                    + endpoint.getModel() + "|" + endpoint.isEnabled());
        }
        return String.join("\n", rows);
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
}
