package com.imagemanager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * AI service configuration loaded from one machine-private local file.
 */
public final class AIConfig {

    private static final Logger logger = LoggerFactory.getLogger(AIConfig.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ThreadLocal<AISettings> RUNTIME_SETTINGS = new ThreadLocal<>();

    public static final String DEFAULT_DELAY = "1500";
    public static final String DEFAULT_MAX_RETRIES = "3";
    public static final String DEFAULT_BATCH_LIMIT = "100";
    public static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5;
    public static final int MIN_BATCH_LIMIT = 1;
    public static final int MAX_BATCH_LIMIT = 500;

    private AIConfig() {
    }

    public static Path getConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "ImageManager", "ai-fallbacks.json")
                    .toAbsolutePath()
                    .normalize();
        }
        return Path.of(System.getProperty("user.home"), ".image-manager", "ai-fallbacks.json")
                .toAbsolutePath()
                .normalize();
    }

    public static AISettings loadSettings() {
        AISettings runtime = RUNTIME_SETTINGS.get();
        if (runtime != null) {
            return normalize(runtime.copy());
        }

        Path configPath = getConfigPath();
        if (!Files.isRegularFile(configPath)) {
            return new AISettings();
        }
        try {
            return normalize(objectMapper.readValue(configPath.toFile(), AISettings.class));
        } catch (IOException e) {
            logger.error("读取本机 AI 配置失败: {}", configPath, e);
            return new AISettings();
        }
    }

    public static void saveSettings(AISettings settings) throws IOException {
        AISettings normalized = normalize(settings == null ? new AISettings() : settings.copy());
        Path configPath = getConfigPath();
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writeValue(configPath.toFile(), normalized);
    }

    public static List<AIEndpointConfig> getConfiguredEndpoints() {
        return configuredEndpoints(loadSettings());
    }

    public static List<AIEndpointConfig> configuredEndpoints(AISettings settings) {
        List<AIEndpointConfig> endpoints = new ArrayList<>();
        for (AIEndpointConfig endpoint : normalize(settings).getEndpoints()) {
            if (endpoint != null && endpoint.isEnabled() && endpoint.isComplete()
                    && !AIFallbackManager.isCircuitOpen(endpoint)) {
                endpoints.add(endpoint.copy());
            }
        }
        return endpoints;
    }

    public static Optional<AIEndpointConfig> firstConfiguredEndpoint() {
        List<AIEndpointConfig> endpoints = getConfiguredEndpoints();
        return endpoints.isEmpty() ? Optional.empty() : Optional.of(endpoints.get(0));
    }

    public static String getBaseUrl() {
        return firstConfiguredEndpoint().map(AIEndpointConfig::getBaseUrl).orElse("");
    }

    public static String getApiKey() {
        return firstConfiguredEndpoint().map(AIEndpointConfig::getApiKey).orElse("");
    }

    public static String getModel() {
        return firstConfiguredEndpoint().map(AIEndpointConfig::getModel).orElse("");
    }

    public static long getRequestDelay() {
        try {
            long delay = Long.parseLong(loadSettings().getRequestDelay());
            return Math.max(0, Math.min(delay, 60000));
        } catch (NumberFormatException e) {
            return Long.parseLong(DEFAULT_DELAY);
        }
    }

    public static int getMaxRetries() {
        try {
            int retries = Integer.parseInt(loadSettings().getMaxRetries());
            return Math.max(1, Math.min(retries, 10));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_MAX_RETRIES);
        }
    }

    public static int getBatchLimit() {
        int limit = loadSettings().getBatchLimit();
        return Math.max(MIN_BATCH_LIMIT, Math.min(limit, MAX_BATCH_LIMIT));
    }

    public static int getCircuitBreakerThreshold() {
        int threshold = loadSettings().getCircuitBreakerThreshold();
        return Math.max(1, Math.min(threshold, 20));
    }

    public static boolean isConfigured() {
        return !getConfiguredEndpoints().isEmpty();
    }

    public static void saveConfig(String baseUrl, String apiKey, String model) throws IOException {
        AISettings settings = loadSettings();
        settings.setEndpoints(List.of(new AIEndpointConfig("主端点", baseUrl, apiKey, model, true)));
        saveSettings(settings);
    }

    public static <T> T withTemporarySettings(AISettings settings, Supplier<T> action) {
        AISettings previous = RUNTIME_SETTINGS.get();
        RUNTIME_SETTINGS.set(settings == null ? new AISettings() : settings.copy());
        try {
            return action.get();
        } finally {
            if (previous == null) {
                RUNTIME_SETTINGS.remove();
            } else {
                RUNTIME_SETTINGS.set(previous);
            }
        }
    }

    static AISettings normalize(AISettings settings) {
        AISettings normalized = settings == null ? new AISettings() : settings;
        List<AIEndpointConfig> endpointCopies = new ArrayList<>();
        for (AIEndpointConfig endpoint : normalized.getEndpoints()) {
            endpointCopies.add(endpoint == null ? new AIEndpointConfig() : endpoint.copy());
        }
        normalized.setEndpoints(endpointCopies);

        if (normalized.getRequestDelay().isBlank()) {
            normalized.setRequestDelay(DEFAULT_DELAY);
        }
        if (normalized.getMaxRetries().isBlank()) {
            normalized.setMaxRetries(DEFAULT_MAX_RETRIES);
        }
        normalized.setBatchLimit(Math.max(MIN_BATCH_LIMIT,
                Math.min(normalized.getBatchLimit(), MAX_BATCH_LIMIT)));
        normalized.setCircuitBreakerThreshold(Math.max(1,
                Math.min(normalized.getCircuitBreakerThreshold(), 20)));
        return normalized;
    }
}
