package com.imagemanager.ai;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;

import java.util.function.Supplier;

/**
 * AI 服务配置 — 管理 OpenAI 兼容 API 的连接参数。
 * <p>
 * 从数据库 app_settings 表读取配置，支持动态切换模型和API端点。
 * 默认使用通义千问VL（qwen-vl-plus），兼容 OpenAI API 格式。
 */
public class AIConfig {

    private static final SettingsDao settingsDao = new SettingsDaoImpl();

    // 默认值
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-vl-plus";
    private static final String DEFAULT_DELAY = "1500";
    private static final String DEFAULT_MAX_RETRIES = "3";
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
        return settingsDao.getValueOrDefault("ai_api_key", "");
    }

    /**
     * 获取模型名称（如 qwen-vl-plus, gpt-4o-mini 等）。
     */
    public static String getModel() {
        RuntimeConfig config = RUNTIME_CONFIG.get();
        if (config != null && config.model() != null && !config.model().isBlank()) {
            return config.model();
        }
        return settingsDao.getValueOrDefault("ai_model", DEFAULT_MODEL);
    }

    /**
     * 获取请求间隔（毫秒），防止限流。
     */
    public static long getRequestDelay() {
        try {
            RuntimeConfig config = RUNTIME_CONFIG.get();
            if (config != null && config.requestDelay() != null && !config.requestDelay().isBlank()) {
                return Long.parseLong(config.requestDelay());
            }
            return Long.parseLong(settingsDao.getValueOrDefault("ai_request_delay", DEFAULT_DELAY));
        } catch (NumberFormatException e) {
            return Long.parseLong(DEFAULT_DELAY);
        }
    }

    /**
     * 获取最大重试次数。
     */
    public static int getMaxRetries() {
        try {
            RuntimeConfig config = RUNTIME_CONFIG.get();
            if (config != null && config.maxRetries() != null && !config.maxRetries().isBlank()) {
                return Integer.parseInt(config.maxRetries());
            }
            return Integer.parseInt(settingsDao.getValueOrDefault("ai_max_retries", DEFAULT_MAX_RETRIES));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_MAX_RETRIES);
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
}
