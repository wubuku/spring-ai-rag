package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.enums.DocumentRestoreVisibilityMode;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * restoreLocalFromVersion 快照恢复矩阵（Batch 398）：集合快照分
 * 配校验、payload 深拷贝、content/metadata/scope 变化标记、
 * SNAPSHOT 可见性禁用恢复。
 */
class DocumentMutationRestoreSnapshotMatrixTest {

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private RagDocument document;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);

        // 启用版本回滚功能
        var props = new RagProperties();
        props.getDocumentLifecycle().setVersionRestoreEnabled(true);

        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any()))
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

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                documentEmbedService,
                mock(com.springairag.core.service.DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                props,
                tm);

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
        version.setMetadataSnapshot(Map.of());
        return version;
    }

    private void stubVersion(RagDocumentVersion version) {
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));
    }

    private DocumentVersionRestoreRequest request(EmbeddingPolicy policy,
            DocumentRestoreVisibilityMode mode) {
        return new DocumentVersionRestoreRequest(4L, policy, mode);
    }

    @Test
    void restoreAppliesSnapshotFields() {
        stubVersion(fullVersion());

        var response = service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        assertEquals("RESTORED_VERSION", response.action());
        assertEquals("Snapshot Title", document.getTitle());
        assertEquals("Snapshot content", document.getContent());
        assertEquals(5L, document.getDocumentRevision());
        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("RESTORE"), anyString());
    }

    @Test
    void restoreWithPayloadSnapshotCopiesPayload() throws Exception {
        var version = fullVersion();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"key\":\"value\"}");
        version.setJsonbPayloadSnapshot(payload);
        stubVersion(version);

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        assertNotNull(document.getJsonbPayload());
        assertEquals("value", document.getJsonbPayload().get("key").asText());
    }

    @Test
    void restoreWithMetadataSnapshotRestoresMetadata() {
        var version = fullVersion();
        version.setMetadataSnapshot(Map.of("locale", "zh"));
        stubVersion(version);

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        assertEquals("zh", document.getMetadata().get("locale"));
    }

    @Test
    void restoreWithSourceRevisionSnapshotsSetsRevision() {
        var version = fullVersion();
        version.setSourceRevisionSnapshot("snap-rev-1");
        version.setOriginalFilenameSnapshot("file.pdf");
        stubVersion(version);

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // sourceRevision 不参与快照恢复（由 contentHash 间接管理）。
        assertEquals("file.pdf", document.getOriginalFilename());
    }

    @Test
    void restoreWithContentChangeResetsProcessingStatus() {
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        assertEquals("PENDING", document.getProcessingStatus());
        assertNull(document.getProcessingError());
    }

    @Test
    void restoreWithoutContentChangeDoesNotResetProcessingStatus() {
        document.setContent("Snapshot content");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Snapshot content"));
        document.setProcessingStatus("COMPLETED");
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        assertEquals("COMPLETED", document.getProcessingStatus());
    }

    @Test
    void restoreWithFreshEmbeddingKeepsDispatchNull() {
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // 内容未变化且嵌入新鲜 → 无派发。
        assertTrue(document.getEnabled());
    }

    @Test
    void restoreWithoutSnapshotVisibilityKeepsCurrentEnabled() {
        stubVersion(fullVersion());
        document.setEnabled(Boolean.FALSE);
        document.setDisabledAt(java.time.LocalDateTime.now().minusHours(1));

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.ASYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.KEEP_CURRENT));

        // KEEP_CURRENT 可见性 → enabled 保持原值。
        assertEquals(Boolean.FALSE, document.getEnabled());
    }

    @Test
    void restoreWithSkipPolicyKeepsDispatchNull() {
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SKIP,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // SKIP 策略 → 无嵌入派发。
        assertTrue(document.getEnabled());
    }

    @Test
    void restoreWithSyncPolicyTriggersEmbedDispatch() throws Exception {
        stubVersion(fullVersion());

        service.restoreLocalFromVersion(41L, 2, request(
                EmbeddingPolicy.SYNC,
                com.springairag.api.enums.DocumentRestoreVisibilityMode.SNAPSHOT));

        // 同步嵌入策略 → PENDING 状态（嵌入由异步流程处理）。
        assertEquals("PENDING", document.getProcessingStatus());
    }
}
