package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService sourceDelete 委派与 tombstoneExternal 墓碑
 * （Batch 381）：mutation service 缺失拒绝、jsonRecord 标志透传、
 * 幂等 UNCHANGED、revision 冲突拒绝与墓碑写入。
 */
class JsonRecordDeleteTombstoneTest {

    private static final String KEY = "kb";
    private static final long COLLECTION_ID = 7L;

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService mutationService;
    private CollectionIdentityResolver resolver;
    private DocumentMutationService mutationServiceForTombstone;
    private JsonRecordService jsonRecordService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        mutationService = mock(DocumentMutationService.class);
        resolver = mock(CollectionIdentityResolver.class);

        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(8);
                    return version;
                });
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(5L);
        lenient().when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class))).thenReturn(null);
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentRepository.findById(41L))
                .thenAnswer(invocation -> java.util.Optional.of(externalDocument(false, "rev-2")));

        jsonRecordService = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(EmbeddingProfileProvider.class),
                resolver,
                new RagProperties(),
                new ObjectMapper(),
                jdbcTemplate,
                mock(PlatformTransactionManager.class));
        jsonRecordService.setMutationService(mutationService);

        mutationServiceForTombstone = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                mock(PlatformTransactionManager.class));
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey(KEY);
        return collection;
    }

    private RagDocument externalDocument(boolean enabled, String revision) {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setTitle("Doc");
        value.setContent("content");
        value.setExternalId("ext-1");
        value.setSourceRevision(revision);
        value.setEnabled(enabled);
        value.setDocumentType("text");
        value.setCollectionId(COLLECTION_ID);
        return value;
    }

    private void stubCollectionLookup() {
        when(resolver.requireActive(null, KEY)).thenReturn(collection());
        when(documentRepository.findByCollectionIdAndSourceNamespaceAndExternalId(
                COLLECTION_ID, "default", "ext-1"))
                .thenReturn(Optional.of(externalDocument(true, "rev-1")));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(new DocumentLifecycleResponse(
                        "ACTIVE", "SEARCHABLE", "CURRENT", "COMPLETED",
                        "profile", null, null, null, false));
    }

    @Test
    void sourceDeleteRejectsWhenMutationServiceMissing() {
        JsonRecordService bare = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                mock(EmbeddingProfileProvider.class),
                resolver,
                new RagProperties(),
                new ObjectMapper(),
                jdbcTemplate,
                mock(PlatformTransactionManager.class));

        assertThrows(IllegalStateException.class,
                () -> bare.sourceDelete(KEY, "default", "ext-1",
                        "rev-2", "rev-1"));
    }

    @Test
    void sourceDeleteDelegatesTombstoneWithJsonRecordFlag() {
        ExternalDocumentDeleteResponse canned =
                new ExternalDocumentDeleteResponse(
                        41L, KEY, "ext-1", "rev-2", "DELETED", 3, false,
                        LocalDateTime.now(), null, null, "default", 5L, null);
        when(mutationService.tombstoneExternal(
                eq(KEY), eq("default"), eq("ext-1"),
                eq("rev-2"), eq("rev-1"), eq(true)))
                .thenReturn(canned);

        assertSame(canned, jsonRecordService.sourceDelete(
                KEY, "default", "ext-1", "rev-2", "rev-1"));
        verify(mutationService).tombstoneExternal(
                KEY, "default", "ext-1", "rev-2", "rev-1", true);
    }

    @Test
    void tombstoneIsIdempotentForAlreadyTombstonedSameRevision() {
        stubCollectionLookup();
        RagDocument tombstoned =
                externalDocument(false, "rev-2");
        tombstoned.setSourceDeletedAt(LocalDateTime.now());
        when(documentRepository.findByCollectionIdAndSourceNamespaceAndExternalId(
                COLLECTION_ID, "default", "ext-1"))
                .thenReturn(Optional.of(tombstoned));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(tombstoned));

        ExternalDocumentDeleteResponse response =
                mutationServiceForTombstone.tombstoneExternal(
                        KEY, "default", "ext-1", "rev-2", null, false);

        assertEquals("UNCHANGED", response.action());
    }

    @Test
    void tombstoneRejectsSameRevisionOnEnabledDocument() {
        stubCollectionLookup();

        assertThrows(DocumentRevisionConflictException.class,
                () -> mutationServiceForTombstone.tombstoneExternal(
                        KEY, "default", "ext-1", "rev-1", null, false));
    }

    @Test
    void tombstoneWritesTombstoneAndReturnsResponse() {
        stubCollectionLookup();

        ExternalDocumentDeleteResponse response =
                mutationServiceForTombstone.tombstoneExternal(
                        KEY, "default", "ext-1", "rev-2", "rev-1", false);

        assertEquals("DELETED", response.action());
        var saved = org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getEnabled());
        assertEquals("rev-2", saved.getValue().getSourceRevision());
        assertEquals("SOURCE", saved.getValue().getDeletionOrigin());
        verify(dispatchService).cancelActiveInCurrentTransaction(41L);
    }
}
