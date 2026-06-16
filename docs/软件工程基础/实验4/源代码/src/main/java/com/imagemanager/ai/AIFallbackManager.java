package com.imagemanager.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime-only failure accounting for fallback endpoints.
 */
public final class AIFallbackManager {

    private static final Logger logger = LoggerFactory.getLogger(AIFallbackManager.class);
    private static final Map<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();

    private AIFallbackManager() {
    }

    public static boolean isCircuitOpen(AIEndpointConfig endpoint) {
        return failureCount(endpoint) >= AIConfig.getCircuitBreakerThreshold();
    }

    public static int failureCount(AIEndpointConfig endpoint) {
        AtomicInteger count = FAILURES.get(endpoint.stateKey());
        return count == null ? 0 : count.get();
    }

    public static void reportSuccess(AIEndpointConfig endpoint) {
        FAILURES.remove(endpoint.stateKey());
    }

    public static void reportFailure(AIEndpointConfig endpoint, String reason) {
        int count = FAILURES
                .computeIfAbsent(endpoint.stateKey(), ignored -> new AtomicInteger())
                .incrementAndGet();
        if (count >= AIConfig.getCircuitBreakerThreshold()) {
            logger.warn("AI 端点已熔断: name={}, failures={}, reason={}",
                    endpoint.getName(), count, reason);
        } else {
            logger.warn("AI 端点请求失败，暂时降级到下一个端点: name={}, failures={}, reason={}",
                    endpoint.getName(), count, reason);
        }
    }

    public static void reset(AIEndpointConfig endpoint) {
        FAILURES.remove(endpoint.stateKey());
    }

    public static void resetAll() {
        FAILURES.clear();
    }
}
