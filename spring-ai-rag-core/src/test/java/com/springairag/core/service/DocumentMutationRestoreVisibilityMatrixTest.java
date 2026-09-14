package com.springairag.core.service;

import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.DocumentLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * restoreLocalFromVersion 快照可见性与内容变化矩阵（Batch 396）：
 * SNAPSHOT 可见性下 enabled=true 恢复（清空 disabledAt、SKIP 派发）、
 * fresh 嵌入免派发、内容变化复位 PENDING、禁用快照恢复为禁用。
 */
class DocumentMutationRestoreVisibilityMatrixTest {

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService service;
    private PlatformTransactionManager transactionManager;
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver resolver;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        resolver = mock(CollectionIdentityResolver.class);
        transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(9);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(
                any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentEmbedService.hasFreshEmbedding(
                any(RagDocument.class))).thenReturn(false);

        document = new RagDocument();
        document.setId(41L);
        document.setTitle("Current Title");
        document.setContent("Current content");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        document.setSource("manual");
        document.setDocumentType("text");
        document.setDocumentRevision(4L);
        document.setEnabled(Boolean.TRUE);

        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        service = newService();
    }

    private DocumentMutationService newService() {
        return new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                properties(),
                transactionManager);
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.getDocumentLifecycle().setVersionRestoreEnabled(true);
        return properties;
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        return tm;
    }

    private DocumentVersionRestoreRequest request(
            EmbeddingPolicy policy,
            com.springairag.api.enums.DocumentRestoreVisibilityMode mode) {
        return new DocumentVersionRestoreRequest(4L, policy, mode);
    }

    private RagDocumentVersion fullVersion() {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Snapshot Title");
        version.setContentSnapshot("Snapshot content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Snapshot content"));
        version.setSourceSnapshot("snapshot-source");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        version.setMetadataSnapshot(java.util.Map.of());
        return version;
    }

    private void stubVersion(RagDocumentVersion version) {
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));
    }

    @Test
    void snapshotVisibilityRestoresEnabledStateAndClearsDisabledAt()
            throws Exception {
        stubVersion(fullVersion());
        document.setEnabled(Boolean.FALSE);
        document.setDisabledAt(java.time.LocalDateTime.now().minusHours(1));

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SKIP,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // enabled=true 快照 → 禁用时间戳清空。
        assertNull(document.getDisabledAt());
        assertEquals(Boolean.TRUE, document.getEnabled());
    }

    @Test
    void freshEmbeddingAvoidsDispatchForUnchangedContent() {
        stubVersion(fullVersion());
        when(documentEmbedService.hasFreshEmbedding(
                any(RagDocument.class))).thenReturn(true);

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SKIP,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // 内容未变化且嵌入新鲜 → 无排队派发。
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void contentChangeMarksProcessingPending() {
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SKIP,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // 快照内容与当前不同 → 处理状态复位为 PENDING。
        assertEquals("PENDING", document.getProcessingStatus());
        assertNull(document.getProcessingError());
        assertTrue(document.getDocumentRevision() >= 4L);
    }

    @Test
    void disabledAtSnapshotRestoredUnderSnapshotVisibility() {
        RagDocumentVersion version = fullVersion();
        version.setEnabledSnapshot(Boolean.FALSE);
        version.setDisabledAtSnapshot(java.time.LocalDateTime.now());
        stubVersion(version);
        document.setEnabled(Boolean.TRUE);

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SKIP,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // SNAPSHOT 可见性 + 禁用快照 → 文档恢复为禁用。
        assertEquals(Boolean.FALSE, document.getEnabled());
        verify(dispatchService).markNotRequestedInCurrentTransaction(document);
    }
}
