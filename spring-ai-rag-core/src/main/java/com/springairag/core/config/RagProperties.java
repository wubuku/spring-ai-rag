package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RAG 统一配置类
 *
 * <p>整合所有 rag.* 前缀的业务配置，替代分散的 @Value 注入。
 * 配置映射自 application.yml 的 rag: 节点。
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   embedding:
 *     api-key: sk-xxx
 *     base-url: https://api.siliconflow.cn/v1
 *     model: BAAI/bge-m3
 *     dimensions: 1024
 *   retrieval:
 *     vector-weight: 0.5
 *     fulltext-weight: 0.5
 *     default-limit: 10
 *     min-score: 0.3
 *   query-rewrite:
 *     enabled: true
 *     padding-count: 2
 *   rerank:
 *     enabled: false
 *     diversity-weight: 0.2
 *   memory:
 *     max-messages: 20
 *   chunk:
 *     default-chunk-size: 1000
 *     default-chunk-overlap: 100
 * </pre>
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final QueryRewrite queryRewrite = new QueryRewrite();
    private final Rerank rerank = new Rerank();
    private final Memory memory = new Memory();
    private final Chunk chunk = new Chunk();
    private final Async async = new Async();
    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Cors cors = new Cors();

    public Embedding getEmbedding() {
        return embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public QueryRewrite getQueryRewrite() {
        return queryRewrite;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Memory getMemory() {
        return memory;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public Async getAsync() {
        return async;
    }

    public Security getSecurity() {
        return security;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Cors getCors() {
        return cors;
    }

    // ==================== 缓存配置 ====================

    private final Cache cache = new Cache();

    public Cache getCache() {
        return cache;
    }

    /**
     * 嵌入模型配置
     */
    public static class Embedding {
        private String apiKey = "";
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String model = "BAAI/bge-m3";
        private int dimensions = 1024;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    /**
     * 检索配置
     */
    public static class Retrieval {
        private float vectorWeight = 0.5f;
        private float fulltextWeight = 0.5f;
        private int defaultLimit = 10;
        private float minScore = 0.3f;

        public float getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(float vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public float getFulltextWeight() {
            return fulltextWeight;
        }

        public void setFulltextWeight(float fulltextWeight) {
            this.fulltextWeight = fulltextWeight;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public float getMinScore() {
            return minScore;
        }

        public void setMinScore(float minScore) {
            this.minScore = minScore;
        }
    }

    /**
     * 查询改写配置
     */
    public static class QueryRewrite {
        private boolean enabled = true;
        private int paddingCount = 2;
        private Map<String, String[]> synonymDictionary = Collections.emptyMap();
        private List<String> domainQualifiers = Collections.emptyList();
        private boolean llmEnabled = false;
        private int llmMaxRewrites = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPaddingCount() { return paddingCount; }
        public void setPaddingCount(int paddingCount) { this.paddingCount = paddingCount; }
        public Map<String, String[]> getSynonymDictionary() { return synonymDictionary; }
        public void setSynonymDictionary(Map<String, String[]> synonymDictionary) { this.synonymDictionary = synonymDictionary; }
        public List<String> domainQualifiers() { return domainQualifiers; }
        public void setDomainQualifiers(List<String> domainQualifiers) { this.domainQualifiers = domainQualifiers; }
        public boolean isLlmEnabled() { return llmEnabled; }
        public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }
        public int getLlmMaxRewrites() { return llmMaxRewrites; }
        public void setLlmMaxRewrites(int llmMaxRewrites) { this.llmMaxRewrites = llmMaxRewrites; }

        // 保持向后兼容的 getter 名
        public List<String> getDomainQualifiers() { return domainQualifiers; }
    }

    /**
     * 重排序配置
     */
    public static class Rerank {
        private boolean enabled = false;
        private float diversityWeight = 0.2f;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public float getDiversityWeight() {
            return diversityWeight;
        }

        public void setDiversityWeight(float diversityWeight) {
            this.diversityWeight = diversityWeight;
        }
    }

    /**
     * 对话记忆配置
     */
    public static class Memory {
        private int maxMessages = 20;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }

    /**
     * 文档分块配置
     */
    public static class Chunk {
        private int defaultChunkSize = 1000;
        private int defaultChunkOverlap = 100;
        private int minChunkSize = 100;

        public int getDefaultChunkSize() {
            return defaultChunkSize;
        }

        public void setDefaultChunkSize(int defaultChunkSize) {
            this.defaultChunkSize = defaultChunkSize;
        }

        public int getDefaultChunkOverlap() {
            return defaultChunkOverlap;
        }

        public void setDefaultChunkOverlap(int defaultChunkOverlap) {
            this.defaultChunkOverlap = defaultChunkOverlap;
        }

        public int getMinChunkSize() {
            return minChunkSize;
        }

        public void setMinChunkSize(int minChunkSize) {
            this.minChunkSize = minChunkSize;
        }
    }

    /**
     * 异步线程池配置
     */
    public static class Async {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 100;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * 安全认证配置
     *
     * <p>配置示例：
     * <pre>
     * rag:
     *   security:
     *     api-key: ${RAG_API_KEY:}
     *     enabled: false
     * </pre>
     */
    public static class Security {
        private String apiKey = "";
        private boolean enabled = false;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * API 限流配置
     *
     * <p>基于令牌桶算法，按客户端 IP 限流。
     *
     * <p>配置示例：
     * <pre>
     * rag:
     *   rate-limit:
     *     enabled: true
     *     requests-per-minute: 60
     * </pre>
     */
    public static class RateLimit {
        private boolean enabled = false;
        private int requestsPerMinute = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
    }

    /**
     * CORS 跨域配置
     *
     * <p>配置示例：
     * <pre>
     * rag:
     *   cors:
     *     enabled: true
     *     allowed-origins:
     *       - "https://example.com"
     *       - "http://localhost:3000"
     *     allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
     *     allowed-headers: "*"
     *     max-age: 3600
     * </pre>
     */
    public static class Cors {
        private boolean enabled = false;
        private List<String> allowedOrigins = List.of("*");
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
        private long maxAge = 3600;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public String getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(String allowedMethods) { this.allowedMethods = allowedMethods; }
        public String getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(String allowedHeaders) { this.allowedHeaders = allowedHeaders; }
        public long getMaxAge() { return maxAge; }
        public void setMaxAge(long maxAge) { this.maxAge = maxAge; }
    }

    /**
     * 缓存配置
     */
    public static class Cache {
        private long maximumSize = 2000;
        private int expireAfterWriteMinutes = 30;
        private long embeddingMaximumSize = 10000;
        private int embeddingExpireAfterWriteHours = 2;

        public long getMaximumSize() { return maximumSize; }
        public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }
        public int getExpireAfterWriteMinutes() { return expireAfterWriteMinutes; }
        public void setExpireAfterWriteMinutes(int expireAfterWriteMinutes) { this.expireAfterWriteMinutes = expireAfterWriteMinutes; }
        public long getEmbeddingMaximumSize() { return embeddingMaximumSize; }
        public void setEmbeddingMaximumSize(long embeddingMaximumSize) { this.embeddingMaximumSize = embeddingMaximumSize; }
        public int getEmbeddingExpireAfterWriteHours() { return embeddingExpireAfterWriteHours; }
        public void setEmbeddingExpireAfterWriteHours(int embeddingExpireAfterWriteHours) { this.embeddingExpireAfterWriteHours = embeddingExpireAfterWriteHours; }
    }
}
