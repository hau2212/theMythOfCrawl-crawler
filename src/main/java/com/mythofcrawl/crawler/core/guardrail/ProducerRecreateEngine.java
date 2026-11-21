package com.mythofcrawl.crawler.core.guardrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stage-2 Producer-Recreate Engine:
 * Pairs a safe POST producer endpoint (which creates disposable test data and populates {@link LiveIdPool})
 * with PUT/PATCH/DELETE consumer endpoints so mutations execute on fresh synthetic records instead of real data.
 */
public final class ProducerRecreateEngine {

    public static final class ProducerPair {
        public final String resourceKey;
        public final String producerMethod;
        public final String producerPath;
        public final String contentType;
        public final String bodyTemplate;
        public final String consumerPattern;
        public final long updatedAtMs;

        public ProducerPair(
            String resourceKey,
            String producerMethod,
            String producerPath,
            String contentType,
            String bodyTemplate,
            String consumerPattern
        ) {
            this.resourceKey = resourceKey != null ? resourceKey.toLowerCase(Locale.ROOT) : "default";
            this.producerMethod = producerMethod != null ? producerMethod.toUpperCase(Locale.ROOT) : "POST";
            this.producerPath = PolicyRule.normalizePathOnly(producerPath);
            this.contentType = contentType != null ? contentType : "application/json";
            this.bodyTemplate = bodyTemplate != null ? bodyTemplate : "{}";
            this.consumerPattern = consumerPattern != null ? consumerPattern : (this.producerPath + "/*");
            this.updatedAtMs = System.currentTimeMillis();
        }
    }

    private final Object lock = new Object();
    private final Map<String, ProducerPair> pairsByResource = new LinkedHashMap<>();

    public void registerProducer(
        String producerMethod,
        String producerPath,
        String contentType,
        String bodyTemplate,
        String consumerPattern
    ) {
        String resource = LiveIdPool.inferResourceFromPath(producerPath);
        ProducerPair pair = new ProducerPair(
            resource, producerMethod, producerPath, contentType, bodyTemplate, consumerPattern);
        synchronized (lock) {
            pairsByResource.put(resource, pair);
        }
    }

    public ProducerPair findProducerForConsumer(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        String m = method.trim().toUpperCase(Locale.ROOT);
        if (!"PUT".equals(m) && !"PATCH".equals(m) && !"DELETE".equals(m)) {
            return null;
        }
        String resource = LiveIdPool.inferResourceFromPath(path);
        synchronized (lock) {
            return pairsByResource.get(resource);
        }
    }

    public List<ProducerPair> snapshotPairs() {
        synchronized (lock) {
            return new ArrayList<>(pairsByResource.values());
        }
    }

    public void clear() {
        synchronized (lock) {
            pairsByResource.clear();
        }
    }
}
