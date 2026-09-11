package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * hasFreshEmbedding 的前置守卫分支：null document、null id、
 * null/空白 contentHash 均返回 false。
 */
class DocumentEmbedHasFreshEmbeddingTest {

    private final RagDocumentRepository documentRepository =
            mock(RagDocumentRepository.class);
    private final EmbeddingProfileProvider profileProvider =
            mock(EmbeddingProfileProvider.class);
    private final com.springairag.core.service.EmbeddingPersistenceService persistenceService =
            mock(com.springairag.core.service.EmbeddingPersistenceService.class);
    private final EmbeddingBatchService batchService =
            mock(EmbeddingBatchService.class);
    private final RagProperties properties = new RagProperties();

    private DocumentEmbedService service() {
        return new DocumentEmbedService(
                documentRepository, batchService, persistenceService,
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
