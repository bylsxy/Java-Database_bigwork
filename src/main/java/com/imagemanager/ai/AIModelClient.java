package com.imagemanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible model-list client for endpoints that expose GET /models.
 */
public final class AIModelClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String NON_CODEX_PREFIX = "non-codex/";
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private AIModelClient() {
    }

    public static List<String> fetchModelIds(String baseUrl, String apiKey) throws IOException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("Base URL 为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("API Key 为空");
        }

        String cacheKey = cacheKey(baseUrl, apiKey);
        List<String> cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        Request request = new Request.Builder()
                .url(buildModelsUrl(baseUrl))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "application/json")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String shortBody = responseBody.length() > 300
                        ? responseBody.substring(0, 300) + "..."
                        : responseBody;
                throw new IOException("获取模型列表失败：HTTP " + response.code() + "，" + shortBody);
            }

            List<String> models = parseModelIds(responseBody);
            if (models.isEmpty()) {
                throw new IOException("模型列表为空或响应格式不兼容");
            }
            CACHE.put(cacheKey, models);
            return models;
        }
    }

    public static Optional<String> firstAvailableModel(String baseUrl, String apiKey) {
        try {
            return preferredModel(fetchModelIds(baseUrl, apiKey));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> preferredModel(List<String> models) {
        if (models == null || models.isEmpty()) {
            return Optional.empty();
        }
        for (String model : models) {
            if (model != null && model.startsWith(NON_CODEX_PREFIX)) {
                return Optional.of(model);
            }
        }
        for (String model : models) {
            if (model != null && !model.isBlank()) {
                return Optional.of(model.trim());
            }
        }
        return Optional.empty();
    }

    private static String buildModelsUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        if (trimmed.endsWith("/models")) {
            return trimmed;
        }
        return trimmed.endsWith("/") ? trimmed + "models" : trimmed + "/models";
    }

    private static List<String> parseModelIds(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.has("data") ? root.get("data") : root;
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();

        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String id = "";
                if (item.isTextual()) {
                    id = item.asText("");
                } else if (item.has("id")) {
                    id = item.get("id").asText("");
                }
                if (!id.isBlank()) {
                    modelIds.add(id.trim());
                }
            }
        }
        return new ArrayList<>(modelIds);
    }

    private static String cacheKey(String baseUrl, String apiKey) {
        return baseUrl.trim() + "#" + Integer.toHexString(Objects.hash(apiKey));
    }
}
