package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * JsonRecordService.scopeAllows 真值表（Batch 443）：文档缺失/
 * 禁用/类型不符拒绝、documentIds 过滤、NONE/ANY_ASSIGNED/SELECTED
 * 三种集合过滤语义。
 */
class JsonRecordServiceScopeAllowsTailTest {

    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        service = new JsonRecordService(
                mock(com.springairag.core.repository.RagDocumentRepository.class),
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                mock(com.springairag.core.retrieval.HybridRetrieverService.class),
                mock(com.springairag.core.retrieval.ReRankingService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                new RagProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                null,
                null);
    }

    private boolean scopeAllows(RetrievalScope scope, RagDocument document)
            throws Exception {
        Method method = JsonRecordService.class.getDeclaredMethod(
                "scopeAllows", RetrievalScope.class, RagDocument.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, scope, document);
    }

    private RagDocument record(Long id, Long collectionId, boolean enabled) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collectionId);
        document.setEnabled(enabled);
        document.setDocumentType(RagDocument.JSON_RECORD);
        return document;
    }

    @Test
    void nullDisabledOrNonJsonRecordDocumentsAreOutOfScope() throws Exception {
        RetrievalScope none = RetrievalScope.unscoped();

        assertFalse(scopeAllows(none, null));
        RagDocument disabled = record(1L, 7L, false);
        assertFalse(scopeAllows(none, disabled));
        RagDocument textDoc = record(1L, 7L, true);
        textDoc.setDocumentType("text");
        assertFalse(scopeAllows(none, textDoc));
    }

    @Test
    void documentIdFilterExcludesUnlistedDocuments() throws Exception {
        RetrievalScope scope = RetrievalScope.forDocumentIds(List.of(5L));

        assertFalse(scopeAllows(scope, record(1L, 7L, true)));
        assertTrue(scopeAllows(scope, record(5L, 7L, true)));
    }

    @Test
    void noneFilterAcceptsAnyAssignedDocument() throws Exception {
        RetrievalScope scope = RetrievalScope.unscoped();
        assertTrue(scopeAllows(scope, record(1L, 7L, true)));
    }

    @Test
    void anyAssignedRequiresNonNullCollectionId() throws Exception {
        RetrievalScope scope = RetrievalScope.anyAssigned(List.of(), null);
        assertTrue(scopeAllows(scope, record(1L, 7L, true)));
        assertFalse(scopeAllows(scope, record(1L, null, true)));
    }

    @Test
    void selectedScopeRequiresCollectionMembership() throws Exception {
        RetrievalScope selected = RetrievalScope.selectedCollections(
                List.of(9L), List.of(), RagDocument.JSON_RECORD);
        assertTrue(scopeAllows(selected, record(1L, 9L, true)));
        assertFalse(scopeAllows(selected, record(1L, 8L, true)));
    }
}
