package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG Unified Configuration Class
 *
 * <p>Consolidates all rag.* prefix business configuration, replacing scattered @Value injections.
 * Configuration is mapped from the rag: node in application.yml.
 *
 * <p>Example:
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

    private final RagEmbeddingProperties embedding = new RagEmbeddingProperties();
    private final RagRetrievalProperties retrieval = new RagRetrievalProperties();
    private final RagQueryRewriteProperties queryRewrite = new RagQueryRewriteProperties();
    private final RagRerankProperties rerank = new RagRerankProperties();
    private final RagMemoryProperties memory = new RagMemoryProperties();
    private final RagChunkProperties chunk = new RagChunkProperties();
    private final RagAsyncProperties async = new RagAsyncProperties();
    private final RagSecurityProperties security = new RagSecurityProperties();
    private final RagRateLimitProperties rateLimit = new RagRateLimitProperties();
    private final RagCorsProperties cors = new RagCorsProperties();
    private final RagCacheProperties cache = new RagCacheProperties();
    private final RagTracingProperties tracing = new RagTracingProperties();
    private final RagCircuitBreakerProperties circuitBreaker = new RagCircuitBreakerProperties();
    private final EmbeddingCircuitBreakerProperties embeddingCircuitBreaker = new EmbeddingCircuitBreakerProperties();
    private final RagTimeoutProperties timeout = new RagTimeoutProperties();
    private final RagProxyProperties proxy = new RagProxyProperties();
    private final RagSlowQueryProperties slowQuery = new RagSlowQueryProperties();
    private final RagSseProperties sse = new RagSseProperties();
    private final RagPdfProperties pdf = new RagPdfProperties();

    public RagEmbeddingProperties getEmbedding() {
        return embedding;
    }

    public RagRetrievalProperties getRetrieval() {
        return retrieval;
    }

    public RagQueryRewriteProperties getQueryRewrite() {
        return queryRewrite;
    }

    public RagRerankProperties getRerank() {
        return rerank;
    }

    public RagMemoryProperties getMemory() {
        return memory;
    }

    public RagChunkProperties getChunk() {
        return chunk;
    }

    public RagAsyncProperties getAsync() {
        return async;
    }

    public RagSecurityProperties getSecurity() {
        return security;
    }

    public RagRateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public RagCorsProperties getCors() {
        return cors;
    }

    public RagCacheProperties getCache() {
        return cache;
    }

    public RagTracingProperties getTracing() {
        return tracing;
    }

    public RagCircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public EmbeddingCircuitBreakerProperties getEmbeddingCircuitBreaker() {
        return embeddingCircuitBreaker;
    }

    public RagTimeoutProperties getTimeout() {
        return timeout;
    }

    public RagProxyProperties getProxy() {
        return proxy;
    }

    public RagSlowQueryProperties getSlowQuery() {
        return slowQuery;
    }

    public RagSseProperties getSse() {
        return sse;
    }
}
