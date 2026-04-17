package com.imagemanager.ai;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;

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

    /**
     * 获取 API Base URL。
     */
    public static String getBaseUrl() {
        return settingsDao.getValueOrDefault("ai_base_url", DEFAULT_BASE_URL);
    }

    /**
     * 获取 API Key。
     */
    public static String getApiKey() {
        return settingsDao.getValueOrDefault("ai_api_key", "");
    }

    /**
     * 获取模型名称（如 qwen-vl-plus, gpt-4o-mini 等）。
     */
    public static String getModel() {
        return settingsDao.getValueOrDefault("ai_model", DEFAULT_MODEL);
    }

    /**
     * 获取请求间隔（毫秒），防止限流。
     */
    public static long getRequestDelay() {
        try {
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
}
