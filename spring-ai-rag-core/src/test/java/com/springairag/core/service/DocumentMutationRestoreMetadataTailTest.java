package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 恢复元数据与 JSON/sync 守卫长尾（Batch
 * 495，JaCoCo 驱动）：restoreLocalFromVersion 的快照 original
 * Filename / metadata / jsonbPayload 三类元数据漂移（UPDATE 版本原
 * 因含 metadata）、upsertJsonRecord 的 JSON null payload 拒绝、
 * upsertSyncRunItem 的空请求 NPE。
 */
class DocumentMutationRestoreMetadataTailTest {

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentEmbedService documentEmbedService;
    private RagProperties properties;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        var lifecycleService = mock(DocumentLifecycleService.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        properties = new RagProperties();
        properties.getDocumentLifecycle().setVersionRestoreEnabled(true);
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                properties,
                transactionManager);

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
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentEmbedService.hasFreshEmbedding(
                any(RagDocument.class))).thenReturn(true);
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(9);
                    return version;
                });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 内容与当前一致 → contentChanged=false，仅元数据参与判定。 */
    private RagDocumentVersion metadataOnlyVersion() {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(2);
        version.setSnapshotCompleteness("FULL");
        version.setTitleSnapshot("Current Title");
        version.setContentSnapshot("Current content");
        version.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        version.setSourceSnapshot("manual");
        version.setDocumentTypeSnapshot("text");
        version.setEnabledSnapshot(Boolean.TRUE);
        return version;
    }

    private DocumentVersionRestoreRequest restoreRequest() {
        return new DocumentVersionRestoreRequest(4L, EmbeddingPolicy.SKIP, null);
    }

    @Test
    void snapshotOriginalFilenameDrivesMetadataChange() {
        RagDocumentVersion version = metadataOnlyVersion();
        version.setOriginalFilenameSnapshot("legacy-manual.txt");
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));

        var response = service.restoreLocalFromVersion(
                41L, 2, restoreRequest());

        assertEquals("RESTORED_VERSION", response.action());
        assertTrue(response.metadataChanged());
        assertEquals("legacy-manual.txt", document.getOriginalFilename());
    }

    @Test
    void snapshotMetadataDrivesMetadataChange() {
        RagDocumentVersion version = metadataOnlyVersion();
        version.setMetadataSnapshot(Map.of("locale", "zh-CN"));
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));

        var response = service.restoreLocalFromVersion(
                41L, 2, restoreRequest());

        assertEquals("RESTORED_VERSION", response.action());
        assertTrue(response.metadataChanged());
        assertEquals("zh-CN", document.getMetadata().get("locale"));
    }

    @Test
    void snapshotPayloadDrivesMetadataChange() throws Exception {
        RagDocumentVersion version = metadataOnlyVersion();
        version.setJsonbPayloadSnapshot(new ObjectMapper().readTree(
                "{\"record\":1}"));
        when(versionService.getVersion(41L, 2))
                .thenReturn(Optional.of(version));

        var response = service.restoreLocalFromVersion(
                41L, 2, restoreRequest());

        assertEquals("RESTORED_VERSION", response.action());
        assertTrue(response.metadataChanged());
        assertNotNull(document.getJsonbPayload());
    }

    @Test
    void jsonRecordUpsertRejectsJsonNullPayload() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setExternalId("rec-1");
        request.setSourceRevision("rev-1");
        request.setTitle("Record");
        request.setEmbed(false);
        request.setJsonbPayload(NullNode.getInstance());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upsertJsonRecord(
                        request, 7L, null, null, null));
        assertEquals("jsonbPayload must not be JSON null", error.getMessage());
    }

    @Test
    void syncRunItemWrapperRejectsNullRequest() {
        assertThrows(NullPointerException.class,
                () -> service.upsertSyncRunItem(
                        7L, "kb", "default", null, 0L));
        // 请求对象非空时进入事务回调（此处仅验证空参守卫）。
        verify(documentRepository,
                org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
