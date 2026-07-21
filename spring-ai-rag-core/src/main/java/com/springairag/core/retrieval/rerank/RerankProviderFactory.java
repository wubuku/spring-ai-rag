package com.springairag.core.retrieval.rerank;

import com.springairag.core.config.RagEmbeddingProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRerankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Selects {@link RerankProvider} from {@code rag.rerank.provider}.
 */
@Component
public class RerankProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(RerankProviderFactory.class);

    private final RagRerankProperties rerankProperties;
    private final RagEmbeddingProperties embeddingProperties;

    public RerankProviderFactory(RagProperties ragProperties) {
        this.rerankProperties = ragProperties.getRerank();
        this.embeddingProperties = ragProperties.getEmbedding();
    }

    public RerankProvider create() {
        String name = rerankProperties.getProvider() != null
                ? rerankProperties.getProvider().trim().toLowerCase()
                : "heuristic";

        return switch (name) {
            case "none", "noop", "off" -> new NoOpRerankProvider();
            case "http", "api", "siliconflow", "remote" -> {
                ensureHttpCredentials();
                HttpRerankProvider http = new HttpRerankProvider(rerankProperties);
                log.info("Rerank provider: http (model={}, available={})",
                        rerankProperties.getModel(), http.isAvailable());
                yield http;
            }
            default -> {
                log.info("Rerank provider: heuristic");
                yield new HeuristicRerankProvider(rerankProperties);
            }
        };
    }

    private void ensureHttpCredentials() {
        if ((rerankProperties.getApiKey() == null || rerankProperties.getApiKey().isBlank())
                && embeddingProperties != null
                && embeddingProperties.getApiKey() != null
                && !embeddingProperties.getApiKey().isBlank()) {
            rerankProperties.setApiKey(embeddingProperties.getApiKey());
            log.debug("Rerank HTTP api-key inherited from rag.embedding.api-key");
        }
        if ((rerankProperties.getBaseUrl() == null || rerankProperties.getBaseUrl().isBlank())
                && embeddingProperties != null
                && embeddingProperties.getBaseUrl() != null) {
            rerankProperties.setBaseUrl(embeddingProperties.getBaseUrl());
        }
    }
}
