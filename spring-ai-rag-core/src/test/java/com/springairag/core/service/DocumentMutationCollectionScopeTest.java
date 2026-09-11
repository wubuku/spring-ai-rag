package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * updateLocal 的集合作用域变更分支（resolveUpdateCollection）：键
 * 指向新集合 → COLLECTION_MOVE 版本记录；无键 → 解绑（scopeChanged）；
 * 受限键禁止解绑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentMutationCollectionScopeTest {

    @Mock RagDocumentRepository documentRepository;
    @Mock RagEmbeddingRepository embeddingRepository;
    @Mock CollectionIdentityResolver collectionIdentityResolver;
    @Mock DocumentVersionService versionService;
    @Mock EmbeddingDispatchService dispatchService;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock DocumentLifecycleService lifecycleService;
    @Mock JdbcTemplate jdbcTemplate;

    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                collectionIdentityResolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);

        document = document(10L, "old-col");
        when(documentRepository.findById(41L)).thenReturn(java.util.Optional.of(document));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(5));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("RETURNING mutation_sequence"), eq(Long.class),
                anyLong(), anyString())).thenReturn(1L);
        when(collectionIdentityResolver.beginActiveWrites(any()))
                .thenReturn(List.of());
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(lifecycle("INDEXING"));
    }

    private RagDocument document(Long collectionId, String collectionKey) {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setVersion(2L);
        value.setDocumentRevision(4L);
        value.setTitle("Current title");
        value.setContent("Current searchable body");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current searchable body"));
        value.setSource("manual");
        value.setDocumentType("text");
        value.setMetadata(Map.of("locale", "en-US"));
        value.setEnabled(true);
        value.setProcessingStatus("COMPLETED");
        value.setCollectionId(collectionId);
        return value;
    }

    private com.springairag.core.entity.RagCollection collection(long id, String key) {
        RagCollection value = new RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        return value;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }

    private DocumentUpdateRequest updateRequest() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(4L);
        return request;
    }

    private DocumentLifecycleResponse lifecycle(String embeddingStatus) {
        return new com.springairag.api.dto.DocumentLifecycleResponse(
                "LIVE", "SEARCHABLE", "COMPLETED", embeddingStatus, "profile",
                null, null, null, false);
    }

    @Test
    void collectionKeyPresentMovesDocumentAndRecordsCollectionMove() {
        when(collectionIdentityResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(false), eq("LOCAL_PATCH")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "profile", UUID.randomUUID(), null, null));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(lifecycle("INDEXING"));

        DocumentUpdateRequest request = updateRequest();
        request.setCollectionKey("target-col");

        var response = service.updateLocal(41L, request);

        assertEquals("UPDATED", response.action());
        assertEquals(20L, document.getCollectionId());
        // 跨集合迁移走 COLLECTION_MOVE 版本记录。
        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("COLLECTION_MOVE"), anyString());
        verify(collectionIdentityResolver).beginActiveWrites(
                eq(Arrays.asList(10L, 20L)));
    }

    @Test
    void updateWithoutCollectionKeyKeepsCurrentCollection() {
        DocumentUpdateRequest request = updateRequest();
        request.setMetadata(Map.of("locale", "zh-CN"));

        var response = service.updateLocal(41L, request);

        assertEquals("UPDATED", response.action());
        // 无 collectionKey → 保留当前集合（不触达解析器）。
        assertEquals(10L, document.getCollectionId());
        verify(collectionIdentityResolver, never())
                .requireActive(any(), anyString());
    }

    @Test
    void restrictedKeyCannotUnassignDocument() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new com.springairag.core.security.ApiAccessPolicy() {
                    @Override public String getPrincipalId() { return "db:restricted"; }
                    @Override public String getCredentialId() { return "rag_k_1"; }
                    @Override public com.springairag.core.entity.ApiKeyRole getRole() {
                        return com.springairag.core.entity.ApiKeyRole.NORMAL; }
                    @Override public String getAllowedCollectionIds() { return "10"; }
                    @Override public java.time.LocalDateTime getExpiresAt() { return null; }
                });
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            DocumentUpdateRequest restrictedRequest = updateRequest();
            restrictedRequest.setTitle("New title");
            // collectionKeyPresent=true 且值为 null → 受限键禁止解绑。
            restrictedRequest.setCollectionKey(null);
            assertThrows(SecurityException.class,
                    () -> service.updateLocal(41L, restrictedRequest));
        } finally {
            org.springframework.web.context.request.RequestContextHolder
                    .resetRequestAttributes();
        }
    }
}
