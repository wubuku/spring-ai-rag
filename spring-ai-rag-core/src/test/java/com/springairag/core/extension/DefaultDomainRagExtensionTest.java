package com.springairag.core.extension;

import com.springairag.api.dto.RetrievalConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultDomainRagExtension 单元测试
 */
class DefaultDomainRagExtensionTest {

    private DefaultDomainRagExtension extension;

    @BeforeEach
    void setUp() {
        extension = new DefaultDomainRagExtension();
    }

    @Test
    void domainId_isDefault() {
        assertEquals("default", extension.getDomainId());
    }

    @Test
    void domainName_isGeneralRag() {
        assertEquals("General RAG", extension.getDomainName());
    }

    @Test
    void systemPromptTemplate_containsPlaceholder() {
        String template = extension.getSystemPromptTemplate();
        assertNotNull(template);
        assertTrue(template.contains("{context}"),
                "System prompt template must contain {context} placeholder");
    }

    @Test
    void retrievalConfig_hasSensibleDefaults() {
        RetrievalConfig config = extension.getRetrievalConfig();
        assertNotNull(config);
        assertTrue(config.getMaxResults() > 0, "maxResults should be positive");
        assertTrue(config.getMinScore() >= 0 && config.getMinScore() <= 1,
                "minScore should be between 0 and 1");
        assertTrue(config.getVectorWeight() > 0, "vectorWeight should be positive");
        assertTrue(config.getFulltextWeight() > 0, "fulltextWeight should be positive");
        assertEquals(1.0, config.getVectorWeight() + config.getFulltextWeight(), 0.01,
                "Weights should sum to 1.0");
    }

    @Test
    void retrievalConfig_hybridSearchEnabled() {
        RetrievalConfig config = extension.getRetrievalConfig();
        assertTrue(config.isUseHybridSearch(), "Hybrid search should be enabled by default");
        assertTrue(config.isUseRerank(), "Rerank should be enabled by default");
    }
}
