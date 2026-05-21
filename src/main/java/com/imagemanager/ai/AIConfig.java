package com.imagemanager.ai;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;

import java.util.function.Supplier;

/**
 * AI 服务配置 — 管理 OpenAI 兼容 API 的连接参数。
 * <p>
 * 从数据库 app_settings 表读取配置，支持动态切换模型和API端点。
 * 默认连接 CPA 代理节点，兼容 OpenAI API 格式。
 */
public class AIConfig {

    private static final SettingsDao settingsDao = new SettingsDaoImpl();

    // 默认值
    public static final String DEFAULT_BASE_URL = "https://cpa.ystone.top/v1";
    public static final String DEFAULT_MODEL = "";
    public static final String DEFAULT_DELAY = "1500";
    public static final String DEFAULT_MAX_RETRIES = "3";
    public static final String DEFAULT_BATCH_LIMIT = "100";
    public static final int MIN_BATCH_LIMIT = 1;
    public static final int MAX_BATCH_LIMIT = 500;
    private static final ThreadLocal<RuntimeConfig> RUNTIME_CONFIG = new ThreadLocal<>();

    public record RuntimeConfig(String baseUrl, String apiKey, String model,
                                String requestDelay, String maxRetries) {}

    /**
     * 获取 API Base URL。
     */
    public static String getBaseUrl() {
        RuntimeConfig config = RUNTIME_CONFIG.get();
        if (config != null && config.baseUrl() != null && !config.baseUrl().isBlank()) {
            return config.baseUrl();
        }
        String envValue = firstEnv("DIMS_AI_BASE_URL", "CPA_BASE_URL", "OPENAI_BASE_URL");
        if (!envValue.isBlank()) {
            return envValue;
        }
        return settingsDao.getValueOrDefault("ai_base_url", DEFAULT_BASE_URL);
    }

    /**
     * 获取 API Key。
     */
    public static String getApiKey() {
        RuntimeConfig config = RUNTIME_CONFIG.get();
        if (config != null && config.apiKey() != null) {
            return config.apiKey();
        }
        String envValue = getEnvironmentApiKey();
        if (!envValue.isBlank()) {
            return envValue;
        }
        return settingsDao.getValueOrDefault("ai_api_key", "");
    }

    /**
     * 获取模型名称。未保存时会尝试从兼容 /models 接口取第一个可用模型。
     */
    public static String getModel() {
        RuntimeConfig config = RUNTIME_CONFIG.get();
        if (config != null && config.model() != null && !config.model().isBlank()) {
            return config.model();
        }
        String envValue = firstEnv("DIMS_AI_MODEL", "CPA_MODEL", "OPENAI_MODEL");
        if (!envValue.isBlank()) {
            return envValue;
        }
        String savedModel = settingsDao.getValueOrDefault("ai_model", DEFAULT_MODEL);
        if (!savedModel.isBlank()) {
            return savedModel;
        }
        if (isConfigured()) {
            return AIModelClient.firstAvailableModel(getBaseUrl(), getApiKey()).orElse(DEFAULT_MODEL);
        }
        return DEFAULT_MODEL;
    }

    /**
     * 获取请求间隔（毫秒），防止限流。
     */
    public static long getRequestDelay() {
        try {
            long delay;
            RuntimeConfig config = RUNTIME_CONFIG.get();
            if (config != null && config.requestDelay() != null && !config.requestDelay().isBlank()) {
                delay = Long.parseLong(config.requestDelay());
            } else {
                delay = Long.parseLong(settingsDao.getValueOrDefault("ai_request_delay", DEFAULT_DELAY));
            }
            return Math.max(0, Math.min(delay, 60000));
        } catch (NumberFormatException e) {
            return Long.parseLong(DEFAULT_DELAY);
        }
    }

    /**
     * 获取最大重试次数。
     */
    public static int getMaxRetries() {
        try {
            int retries;
            RuntimeConfig config = RUNTIME_CONFIG.get();
            if (config != null && config.maxRetries() != null && !config.maxRetries().isBlank()) {
                retries = Integer.parseInt(config.maxRetries());
            } else {
                retries = Integer.parseInt(settingsDao.getValueOrDefault("ai_max_retries", DEFAULT_MAX_RETRIES));
            }
            return Math.max(1, Math.min(retries, 10));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_MAX_RETRIES);
        }
    }

    /**
     * 获取单次 AI 识别批处理上限。界面展示时统一写作 N(max)。
     */
    public static int getBatchLimit() {
        try {
            int limit = Integer.parseInt(settingsDao.getValueOrDefault("ai_batch_limit", DEFAULT_BATCH_LIMIT));
            return Math.max(MIN_BATCH_LIMIT, Math.min(limit, MAX_BATCH_LIMIT));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_BATCH_LIMIT);
        }
    }

    /**
     * 检查 API 是否已配置（有 key 且不为空）。
     */
    public static boolean isConfigured() {
        String key = getApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * 保存配置到数据库。
     */
    public static void saveConfig(String baseUrl, String apiKey, String model) {
        settingsDao.upsert("ai_base_url", baseUrl);
        settingsDao.upsert("ai_api_key", apiKey);
        settingsDao.upsert("ai_model", model);
    }

    public static String getEnvironmentApiKey() {
        return firstEnv("DIMS_AI_API_KEY", "CPA_API_KEY", "HAJIMI", "OPENAI_API_KEY");
    }

    public static <T> T withTemporaryConfig(RuntimeConfig config, Supplier<T> action) {
        RuntimeConfig previous = RUNTIME_CONFIG.get();
        RUNTIME_CONFIG.set(config);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                RUNTIME_CONFIG.remove();
            } else {
                RUNTIME_CONFIG.set(previous);
            }
        }
    }

    private static String firstEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
