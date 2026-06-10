package com.imagemanager.ai;

/**
 * One OpenAI-compatible endpoint in the local, machine-private AI fallback list.
 */
public class AIEndpointConfig {

    private String name = "";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private boolean enabled = true;

    public AIEndpointConfig() {
    }

    public AIEndpointConfig(String name, String baseUrl, String apiKey, String model, boolean enabled) {
        this.name = normalize(name);
        this.baseUrl = normalize(baseUrl);
        this.apiKey = normalize(apiKey);
        this.model = normalize(model);
        this.enabled = enabled;
    }

    public AIEndpointConfig copy() {
        return new AIEndpointConfig(name, baseUrl, apiKey, model, enabled);
    }

    public boolean isComplete() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    public String displayName() {
        String label = name.isBlank() ? "未命名端点" : name;
        if (!enabled) {
            return label + "（停用）";
        }
        if (!isComplete()) {
            return label + "（未完整）";
        }
        return label;
    }

    public String stateKey() {
        return name + "|" + baseUrl + "|" + model;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = normalize(name);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normalize(baseUrl);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = normalize(apiKey);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = normalize(model);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return displayName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
