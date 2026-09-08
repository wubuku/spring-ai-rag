package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * importDocument 外部分支（externalId 非空 → upsertExternalInTransaction）：
 * 新建文档全字段落库、同 revision 同状态 UNCHANGED 幂等重放、
 * 空 sourceNamespace 归一化为 default。json-record 校验分支由
 * DocumentMutationImportTest 覆盖，此处不重复。
 */
class DocumentMutationImportExternalTest {

    private static final long COLLECTION_ID = 5L;

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver.ActiveCollectionToken writeToken;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(3);
                    return version;
                });

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
                new RagProperties(),
                transactionManager);

        writeToken = new CollectionIdentityResolver.ActiveCollectionToken(
                COLLECTION_ID, 1L);
        when(resolver.beginActiveWrite(COLLECTION_ID)).thenReturn(writeToken);
    }

    private CollectionImportRequest.ImportedDocument externalImport() {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("External Doc");
        imported.setContent("External content");
        imported.setExternalId("cms:ext-1");
        imported.setSourceNamespace("crm");
        imported.setSourceRevision("rev-1");
        imported.setSource("crm-sync");
        imported.setDocumentType("text");
        imported.setMetadata(Map.of("locale", "zh-CN"));
        imported.setEnabled(false);
        return imported;
    }

    @Test
    void externalImportCreatesNewDocumentWithSkipPolicy() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "cms:ext-1"))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq(COLLECTION_ID), eq("crm")))
                .thenReturn(7L);
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(99L);
                    return doc;
                });
        when(documentRepository.findById(99L))
                .thenReturn(Optional.of(new RagDocument()));

        service.importDocument(COLLECTION_ID, "kb", externalImport());

        ArgumentCaptor<RagDocument> saved =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        RagDocument document = saved.getValue();
        assertEquals("cms:ext-1", document.getExternalId());
        assertEquals("crm", document.getSourceNamespace());
        assertEquals("rev-1", document.getSourceRevision());
        assertEquals(1L, document.getDocumentRevision());
        assertEquals("External Doc", document.getTitle());
        assertEquals("External content", document.getContent());
        assertEquals("crm-sync", document.getSource());
        assertEquals("text", document.getDocumentType());
        assertEquals(DigestUtils.sha256("External content"),
                document.getContentHash());
        assertEquals(Boolean.FALSE, document.getEnabled());
        assertEquals(Long.valueOf(7L), document.getSourceMutationSequence());
        // enabled=false 不触发派生；SKIP 策略不排队也不标记嵌入。
        verify(documentEmbedService, never()).hasFreshEmbedding(any());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
        verify(dispatchService, never()).markNotRequestedInCurrentTransaction(
                any());
        // 写入令牌在事务收尾确认。
        verify(resolver).confirmActiveWrite(writeToken);
    }

    @Test
    void externalImportSameRevisionAndStateIsUnchangedReplay() {
        RagDocument existing = new RagDocument();
        existing.setId(77L);
        existing.setEnabled(Boolean.TRUE);
        existing.setTitle("External Doc");
        existing.setContentHash(DigestUtils.sha256("External content"));
        existing.setSource("crm-sync");
        existing.setDocumentType("text");
        existing.setMetadata(Map.of("locale", "zh-CN"));
        existing.setSourceRevision("rev-1");
        existing.setDocumentRevision(4L);
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "cms:ext-1"))
                .thenReturn(Optional.of(existing));
        when(documentRepository.findById(77L))
                .thenReturn(Optional.of(existing));
        when(versionService.getLatestVersion(77L))
                .thenReturn(Optional.empty());

        service.importDocument(COLLECTION_ID, "kb", externalImport());

        // 幂等重放：不落库、不记版本、不分配序列。
        verify(documentRepository, never()).saveAndFlush(any());
        verify(versionService, never()).forceRecordVersion(
                any(), anyString(), anyString());
        verify(jdbcTemplate, never())
                .update(anyString(), any(), any());
        verify(resolver).confirmActiveWrite(writeToken);
    }

    @Test
    void externalImportBlankSourceNamespaceNormalizesToDefault() {
        CollectionImportRequest.ImportedDocument imported = externalImport();
        imported.setExternalId("cms:ext-2");
        imported.setSourceNamespace("  ");
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "default", "cms:ext-2"))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq(COLLECTION_ID),
                eq("default")))
                .thenReturn(1L);
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(100L);
                    return doc;
                });
        when(documentRepository.findById(100L))
                .thenReturn(Optional.of(new RagDocument()));

        service.importDocument(COLLECTION_ID, "kb", imported);

        ArgumentCaptor<RagDocument> saved =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals("default", saved.getValue().getSourceNamespace());
    }
}
