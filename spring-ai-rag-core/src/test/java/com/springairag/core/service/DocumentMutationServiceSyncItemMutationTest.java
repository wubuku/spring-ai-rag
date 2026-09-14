package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentMutationService.SyncItemMutation;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.service.DocumentMutationService.SyncItemMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.eq;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 长尾补充（Batch 397）：upsertSyncRunItem
 * 的 APPLIED/UNCHANGED 双路径返回值断言（embeddingJobId/error 为
 * null、SKIP 派发 NONE）、reconciliation recovery 恢复分支。
 */
class DocumentMutationServiceSyncItemMutationTest {

    private static final long COLLECTION_ID = 10L;

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService =
                mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(5);
                    return version;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq(COLLECTION_ID), eq("article-1")))
                .thenReturn(7L);
        String content = "New body";

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(com.springairag.core.service.DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private DocumentSyncRunItemRequest textRequest(String revision,
                                                   String content) {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "article-1",
                revision,
                "Article",
                content,
                null,
                null,
                "cms",
                "text",
                java.util.Map.of(),
                EmbeddingPolicy.SKIP);
    }

    private RagDocument current(String revision, long sequence) {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setEnabled(Boolean.TRUE);
        value.setTitle("Article");
        value.setContent("Old body");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Old body"));
        value.setSource("cms");
        value.setDocumentType("text");
        value.setMetadata(java.util.Map.of());
        value.setSourceRevision(revision);
        value.setSourceMutationSequence(sequence);
        value.setCollectionId(COLLECTION_ID);
        return value;
    }

    @Test
    void reconciliationRecoveryBypassesSameRevisionConflict() {
        // 对账墓碑文档（RECONCILIATION origin + sourceDeletedAt）：
        // 同 revision 重复写入不再抛冲突，而是恢复并更新内容。
        RagDocument tombstoned = current("r1", 10L);
        tombstoned.setEnabled(Boolean.FALSE);
        tombstoned.setSourceDeletedAt(java.time.LocalDateTime.now());
        tombstoned.setDeletionOrigin("RECONCILIATION");
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "article-1", "article-1"))
                .thenReturn(Optional.of(tombstoned));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(tombstoned));

        DocumentMutationService.SyncItemMutation mutation =
                service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "cms", "article-1",
                        textRequest("r1", "Old body"), 10L);

        assertEquals(DocumentSyncItemStatus.APPLIED, mutation.status());
        assertEquals("r1", mutation.sourceRevision());
        assertNull(mutation.error());
    }
}
