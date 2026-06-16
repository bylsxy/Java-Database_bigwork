package com.imagemanager.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine-private AI settings persisted outside the project tree.
 */
public class AISettings {

    private List<AIEndpointConfig> endpoints = new ArrayList<>();
    private String requestDelay = AIConfig.DEFAULT_DELAY;
    private String maxRetries = AIConfig.DEFAULT_MAX_RETRIES;
    private int batchLimit = Integer.parseInt(AIConfig.DEFAULT_BATCH_LIMIT);
    private int circuitBreakerThreshold = AIConfig.DEFAULT_CIRCUIT_BREAKER_THRESHOLD;

    public List<AIEndpointConfig> getEndpoints() {
        if (endpoints == null) {
            endpoints = new ArrayList<>();
        }
        return endpoints;
    }

    public void setEndpoints(List<AIEndpointConfig> endpoints) {
        this.endpoints = endpoints == null ? new ArrayList<>() : endpoints;
    }

    public String getRequestDelay() {
        return requestDelay == null || requestDelay.isBlank() ? AIConfig.DEFAULT_DELAY : requestDelay.trim();
    }

    public void setRequestDelay(String requestDelay) {
        this.requestDelay = requestDelay == null ? AIConfig.DEFAULT_DELAY : requestDelay.trim();
    }

    public String getMaxRetries() {
        return maxRetries == null || maxRetries.isBlank() ? AIConfig.DEFAULT_MAX_RETRIES : maxRetries.trim();
    }

    public void setMaxRetries(String maxRetries) {
        this.maxRetries = maxRetries == null ? AIConfig.DEFAULT_MAX_RETRIES : maxRetries.trim();
    }

    public int getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(int batchLimit) {
        this.batchLimit = batchLimit;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public AISettings copy() {
        AISettings copy = new AISettings();
        List<AIEndpointConfig> endpointCopies = new ArrayList<>();
        for (AIEndpointConfig endpoint : getEndpoints()) {
            endpointCopies.add(endpoint == null ? new AIEndpointConfig() : endpoint.copy());
        }
        copy.setEndpoints(endpointCopies);
        copy.setRequestDelay(getRequestDelay());
        copy.setMaxRetries(getMaxRetries());
        copy.setBatchLimit(batchLimit);
        copy.setCircuitBreakerThreshold(circuitBreakerThreshold);
        return copy;
    }
}
