package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * hasFreshEmbedding 的前置守卫分支：null document、null id、
 * null/空白 contentHash 均返回 false，不触达任何依赖。
 */
class DocumentEmbedHasFreshEmbeddingGuardTest {

    private final EmbeddingBatchService batchService =
            mock(EmbeddingBatchService.class);
    private final EmbeddingProfileProvider profileProvider =
            mock(EmbeddingProfileProvider.class);
    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final RagProperties properties = new RagProperties();

    private DocumentEmbedService service() {
        return new DocumentEmbedService(
                documentRepository, batchService,
                mock(com.springairag.core.service.EmbeddingPersistenceService.class),
                profileProvider, properties);
    }

    private RagDocument document(Long id, String contentHash) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setContentHash(contentHash);
        doc.setEnabled(true);
        return doc;
    }

    @Test
    void nullDocumentReturnsFalse() {
        assertFalse(service().hasFreshEmbedding(null));
    }

    @Test
    void nullIdReturnsFalse() {
        assertFalse(service().hasFreshEmbedding(document(null, "hash-1")));
    }

    @Test
    void nullContentHashReturnsFalse() {
        assertFalse(service().hasFreshEmbedding(document(1L, null)));
    }

    @Test
    void blankContentHashReturnsFalse() {
        assertFalse(service().hasFreshEmbedding(document(1L, "  ")));
    }
}
